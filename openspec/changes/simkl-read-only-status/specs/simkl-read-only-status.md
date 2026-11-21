# SIMKL read-only status

## Sign-in

- The app registers one V2 application (see Q2) with client type
  "Mobile, desktop & browser apps" and scope `media:read` only.
  No `client_secret` is minted or shipped.
- Sign-in uses the device/PIN flow: `POST /oauth2/device` returns
  a `user_code` (8 chars), a `verification_uri_complete`, an
  expiry (15 min) and a poll interval (5 s). The screen shows the
  code and a button opening the verification URI in the browser.
- The app polls `POST /oauth2/token` (device-code grant) at the
  returned interval until the user confirms or the code expires.
  Cancelling the screen stops the poll; expiry shows a "code
  expired, try again" state, never a silent failure.
- On success the access token (7 days) and refresh token (180
  days sliding) are stored encrypted. The username is fetched once
  from `GET /users/settings` and cached for the Settings row.
- Sign-out calls `POST /oauth2/revoke`, clears tokens and the
  snapshot, and removes all badges immediately.

## Token refresh

- Every user call attaches `Authorization: Bearer <access>`. A
  401 triggers one refresh (`grant_type=refresh_token`) and a
  single retry of that call; a second 401 signs the user out.
- Refresh is single-flight: concurrent callers wait on the one
  in-flight refresh instead of each refreshing.
- A token expiring within a day is refreshed proactively at the
  next sync, so a normal sync never hits a 401 first.
- A refresh failure (revoked, expired refresh token) signs the
  user out and clears the snapshot. It never retries in a loop.

## Activities-gated sync

- Sync triggers are on-demand only: details-sheet open,
  pull-to-refresh, and the Settings sync action. There is no timer,
  no background job, and no sync on app start.
- Each sync first calls `GET /sync/activities` and compares the
  `all` watermark with the saved snapshot. If unchanged, the sync
  ends there — no `/sync/all-items` call is made.
- If changed, the sync fetches
  `GET /sync/all-items/{type}/{status}?date_from=<saved all
  timestamp>` for each type (movie, show, anime) and status
  (`watching`, `plantowatch`, `hold`, `completed`, `dropped`;
  movies skip `watching`/`hold`), with `extended=full`, and merges
  the delta into the snapshot.
- If the `removedFromList` timestamp moved, removals are
  reconciled with an ids-only fetch (`simkl_ids_only`), and entries
  absent from it are dropped from the snapshot.
- The first sync for a signed-in user is a bootstrap: all
  types/statuses with no `date_from`.
- The snapshot (watermark, entries, last-checked time) is written
  to disk after every successful sync. A failed sync leaves the
  last snapshot untouched and records the failure for Settings
  only — the list never shows an error for a stale badge.

## Status index

- The snapshot is indexed by IMDb id, TMDB id, and SIMKL id, so a
  row's status lookup is O(1). The index is rebuilt on every
  snapshot save.
- A download with no enrichment id (no automatic match, no pick)
  has no index entry and shows no badge. That is the normal state
  for unidentified titles, not an error.
- When a title has both an IMDb and a TMDB id, either key resolves
  to the same entry.

## Badges

- List rows show a compact badge for `watching` ("Watching"),
  `plantowatch` ("Watchlist"), and `completed` ("Completed").
  `hold` and `dropped` show no row badge.
- The details sheet shows the full status for any of the five
  statuses, plus for shows the next episode and progress from
  `extended=full` — e.g. "S2 E4 · 12/20 episodes" — derived from
  `next_to_watch_info` and `watched_episodes_count`.
- Every badge and every sheet status links to that item's
  `simkl.com` page. Attribution is mandatory and is not a
  tappable extra: the status text itself is the link.
- Badges fill in when the snapshot lands; the list never waits on
  a sync to render. A sync in flight shows no spinner on rows.

## Rate limits and quota

- Reads are capped at 10 GET/sec; the client never issues parallel
  all-items fetches beyond that. Token exchange is the only POST
  besides sign-out, so the 1 POST/sec limit never binds a sync.
- `429` responses are honoured via `Retry-After`; the backed-off
  call is not retried in the same sync.
- `X-RateLimit-Remaining` is read on every response. A
  quota-exhausted response stops further sync attempts until the
  daily reset (midnight America/New_York) and is recorded in
  Settings.

## Non-goals

- No two-way actions: no add-to-list, no mark-watched, no ratings.
  The requested scope is `media:read` only, and widening it later
  would force the user through consent again.
- No scrobbling or playback sync, no Custom Lists, no per-title
  `/sync/watched` checks, no anime-id handling beyond what
  all-items already returns.
- No automatic or timed sync of any kind. Nuvio's 15-minute
  automatic interval is deliberately not adopted: on-demand only
  is the safest shape against the suspension rule.
