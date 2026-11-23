# trove-download-progress spec

## Status mapping

- An actively downloading row maps to `DOWNLOADING` regardless of the `cached`,
  `download_present`, or `download_finished` flags, unless `download_finished`
  is true **and** reported progress is >= 1 (stale-state guard → READY).
- `queuedDL` maps to `QUEUED`. `allocating`, `checkingDL`, `forcedDL` map to
  `DOWNLOADING` at reported progress. `metadl`, `checkingResumeData`,
  `stalled*` stay `DOWNLOADING`. `uploading`, `seeding`, `cached` (state) stay
  `READY`. `completed` with `download_finished: false` stays `UNKNOWN`.
  `paused` → `PAUSED`, `queued` → `QUEUED`, error/fail → `ERROR`, anything else
  → `UNKNOWN`.
- `progress` is forced to `1f` only when the final status is `READY`.
- `DebridDownload` carries a nullable `rawState: String?` (the lowercased
  `download_state` as received); it is never displayed.
- The mapping is shared across TORRENT, WEB, and USENET; the working web row
  (77%, speed, ETA) renders identically after the change.

## Polling

- Poll ticks run at 60 s while the visible tab contains any row with status
  `DOWNLOADING` or `QUEUED`. Polling is foreground-only when the notification
  toggle is off; when the toggle is on, ticks continue while the app is
  backgrounded or the user is on another screen, so the notification stays
  live (user decision 2026-11-23 — a deliberate change from the original
  foreground-only design).
- Each tick re-fetches page 0 of the visible tab only, `bypass_cache = true`,
  through the existing head-only merge. It never disturbs scroll position or
  rows below page 0, and never calls `requestdl`.
- Polling stops when no downloading/queued row remains, when a refresh or
  full sync runs, or — when the notification toggle is off — when the app
  backgrounds or the user leaves the Downloads screen. With the toggle on,
  background polling continues. A failed tick keeps the last rows and backs
  off to the next tick. On `RateLimited`, polling stops for the session and
  resumes on the next foreground/refresh.
- No speculative animation between ticks: the bar shows the last
  server-reported value (TorBox refreshes torrent data every 600 s).

## Refresh, pagination, search

- Pull-to-refresh fetches page 0 of the visible tab only (`headOnly = true`,
  `bypass_cache = true`); it does not traverse, does not clear rows below
  page 0, and preserves scroll position. A refresh of a 250-row tab keeps
  250 rows with the new head row merged in.
- Scrolling paginates with the existing offset mechanism (`loadNextPage`,
  `PAGE_SIZE = 100`). Sort change still traverses the full tab (the one
  deliberate exception).
- Search matches instantly against the already-loaded snapshot on each
  keystroke (debounced as today) and shows a "Searching all downloads…"
  indicator while the background `ensureFullSync` runs; results update when
  the sync completes. Clearing the query cancels the sync.

## Missing row

- Adding a download inserts the returned row at the top of its tab
  immediately with status `QUEUED`. The following head fetch merges the
  server row in place by `(kind, id)`. If a head fetch lacks the id, the
  optimistic row stays; if a second consecutive head fetch still lacks it,
  the row is dropped and the list error state is set to "Couldn't confirm
  the added download. Pull to refresh and try again." (the existing
  snackbar feedback path; the list itself loaded fine, so no load error
  is set).

## Notification

- One notification per active download, grouped via the Android group API.
- Shown only when the Settings toggle is on (default OFF) and the download
  status is `DOWNLOADING` (or an optimistic just-added `QUEUED` row).
- Updates are driven by poll ticks and only re-posted when progress moved
  ≥ 1–2% or the speed/ETA text changed.
- `POST_NOTIFICATIONS` is requested only from the toggle. The foreground
  service (`dataSync`) is started while the app is in the foreground and
  stops when the last download finishes/pauses/errors/is deleted, when the
  toggle goes off, or when the app is swiped away. With the toggle on,
  polling continues while backgrounded so the service's posted values keep
  updating; `onBackground()` deliberately never stops the service itself,
  so the last posted values stay frozen (not cleared) if polling stops.
- The notification shows title, percentage, speed, ETA; tapping opens the
  Downloads screen. It never contains an API key, a requestdl link, or a
  file path. On completion the progress notification is cancelled silently.
