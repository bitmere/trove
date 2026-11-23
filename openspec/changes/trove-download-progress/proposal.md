# Download list: progress, polling, notification, refresh/pagination, missing rows

## Problem

Diagnosis is complete against main `91349f65` (Trove 0.1.41). Verified current state:

- The progress bar exists (`DownloadPresentation.kt:30-31` returns `null` unless
  `status == DOWNLOADING`; `DownloadCard.kt:158` renders it when non-null), but a
  downloading torrent is classified READY whenever TorBox also reports it cached /
  present / seeding / uploading (`TorBoxProvider.kt:642-668`, READY arm first), and
  READY forces progress to `1f` (`:686`). The bar never renders. The Web tab works
  because web rows carry the active-download state without the READY-tripping flags.
- There is no live polling: the list re-fetches page 0 only on foreground (at most
  once per 10 min, `HEAD_REVALIDATION_INTERVAL_MS = 600_000L`,
  `DownloadsViewModel.kt:158`) or pull-to-refresh. A shown bar never moves.
- Pull-to-refresh traverses the whole visible tab top-to-bottom
  (`launchPage`, `DownloadsViewModel.kt:574-610`: `traversePages` is true on almost
  any `replace` of a non-empty tab). The user wants refresh to pull only the latest
  at the top and scrolling to paginate via offset.
- A just-added row is built by `resolveCreated()` (`TorBoxProvider.kt:527-546`) and
  then discarded by `onAdded()` (`DownloadsViewModel.kt:416-418`), which only
  refreshes. The user has two web downloads; the app shows one.
- No notification or foreground-service code exists (manifest has exactly 3
  permissions). The user approved a progress notification, one per download,
  grouped.
- TorBox docs: torrent list data refreshes every 600 s server-side (web: 5 s).
  The relay "Request Update On Torrent" endpoint is mentioned in the docs but its
  path/limits are not in the OpenAPI spec — unverified; polling proceeds without it.
  `/queued/getqueued` exists in the OpenAPI spec (GET, params `id`/`type`/
  `bypass_cache`/`offset`/`limit`) but its response schema is `{}` — unverified.

## Change

1. **Status mapping** (`TorBoxProvider.toDownload()` only): active-download evidence
   wins over cached/present flags; `queuedDL` → QUEUED; `allocating`/`checkingDL`/
   `forcedDL` → DOWNLOADING; progress is forced to `1f` only when the final status
   is READY; keep the raw state in a new nullable `rawState` field on
   `DebridDownload`. The bug-encoding test (`TorBoxProviderTest.kt:890-910`) is
   rewritten deliberately, not deleted.
2. **Head polling** in `DownloadsViewModel`: 60 s ticks, page 0 of
   the visible tab only via the existing head-only merge, while any visible row is
   DOWNLOADING or QUEUED; stops when none remains, or while a
   refresh/full sync runs. Stops on 429. Foreground-only when the notification
   toggle is off; with the toggle on, polling continues while backgrounded so
   the notification stays live (user decision 2026-11-23).
3. **Head-only refresh + scroll pagination + search:** `refreshFromUser()` passes
   `headOnly = true`; the implicit auto-traverse clause in `launchPage()` is
   removed (traverse only on explicit opt-in: sort change, search full sync).
   Search matches instantly against the loaded snapshot while the existing
   `ensureFullSync()` runs in the background with a "Searching all downloads…"
   indicator. Sort change keeps full traversal as the deliberate exception.
4. **Missing row:** `onAdded(kind, download)` inserts the returned row at the top
   of the tab immediately as an optimistic QUEUED row; the head fetch merges it in
   place by `(kind, id)`; it is dropped only after a second consecutive head fetch
   still lacks it, and the drop sets the list error feedback ("Couldn't confirm
   the added download. Pull to refresh and try again."). The `/queued/getqueued` read is deferred (unverified shape).
5. **Progress notification:** one notification per active download, grouped; new
   package only (`ui/downloads/notifications/`), manifest additions,
   Settings toggle default OFF, foreground service `dataSync` started
   foreground-only, updates on poll ticks with a ≥1–2% change threshold, stops
   when downloads finish/pause or the toggle goes off. No API key, requestdl link,
   or file path in any notification.
6. **Nuvio ideas as mechanisms:** local snapshot search (Phase 3), raw status kept
   separate (Phase 1), no new cache layer, no pagination change, no per-provider
   failure change. Designs only — Nuvio is GPL-3.0, no code copied.

## Scope

- In scope: the six phases above, in dependency order (diagnosis → mapping →
  polling → refresh/pagination/search → missing row → notification).
- Out of scope: general TorBox title discovery (AUP); background polling when
  the notification toggle is off; any change to the Web tab's working
  behaviour; the TMDB similar-cases fix (separate branch, PR decision
  pending); `/queued/getqueued` read (deferred, see Phase 4);
  notification completion ping (silent cancel by default).
- Gates: full suite 0 failures (baseline 765), lint 0 errors, Impeccable 0
  findings on UI changes. Build + gates only — no PR/merge/tag/release.
