# Tasks

- [ ] SIMKL transport + models (M, med): new `core` client on the
  existing `HttpTransport` — no new dependency, no OkHttp (Trove
  core is Android-free and dependency-light). Typed models for
  activities, all-items entries (`extended=full`), and the
  device-flow payloads. Every request carries `client_id`,
  `app-name`, `app-version` + `User-Agent`; user calls carry
  `Authorization: Bearer`. A 301 from `/redirect` is read from the
  `Location` header and never followed. Failures are typed
  (auth / rate-limited / network) and never crash the sheet.
- [ ] Encrypted token store (S, low-med): access + refresh tokens
  and scope in the encrypted credential store alongside the TorBox
  key — never plain `SharedPreferences` (Matrix's plain prefs are
  not the bar). Single-flight refresh: refresh a day early or
  after a 401, one in-flight refresh at a time (Nuvio's
  interceptor pattern, reimplemented fresh). A refresh failure
  surfaces as signed-out, never as a retry storm.
- [ ] Device/PIN sign-in flow (S, low-med): `POST /oauth2/device`
  with scope `media:read` only → screen shows the 8-char
  `user_code` + an "Open simkl.com/pin" button (Custom Tab /
  browser) → poll `POST /oauth2/token` every 5 s until the user
  confirms or the 15-min expiry lapses. Cancelling the screen
  stops the poll. No `client_secret` anywhere.
- [ ] Activities-gated sync engine + disk snapshot cache (M, med):
  snapshot holds the activities watermark, the entry list, and the
  last-checked time (Matrix's `SimklDiskCache` shape; Nuvio's
  `SimklSyncEngine` shape). On a sync: fetch activities; if the
  `all` watermark is unchanged, update last-checked and stop — no
  all-items call. If changed, fetch
  `all-items/{type}/{status}?date_from=<saved all timestamp>` per
  type/status and merge the delta; reconcile removals with an
  ids-only fetch when the `removedFromList` timestamp moved. The
  first sync is a bootstrap (all types/statuses, no `date_from`).
  No timer, no background work: sync triggers are details-sheet
  open, pull-to-refresh, and Settings only.
- [ ] Status index keyed by IMDb/TMDB id (S, low): in-memory index
  built from the snapshot — IMDb id → entry, TMDB id → entry,
  SIMKL id → entry (Nuvio's `SimklEntryIndex` alias-key idea).
  Lookup is O(1) per row. A title with no enrichment id has no
  index entry and shows no badge. The index is rebuilt on every
  snapshot save, never mutated in place by the UI.
- [ ] Repository wiring + ViewModel state (S, low-med): `TroveRepository`
  exposes the sync trigger + the status lookup; `DownloadsViewModel`
  holds the snapshot version and maps rows to statuses. A sync never
  blocks the list render — rows render first, badges fill in when
  the snapshot lands. A failed sync leaves the last snapshot (and
  its badges) in place and records the failure for Settings only.
- [ ] Badge UI — list rows + details sheet (M, low-med): compact
  badge on the row for watching / watchlist / completed; the sheet
  shows the full status plus, for shows, "S2 E4 · 12/20 episodes"
  from `extended=full` (`next_to_watch_info` +
  `watched_episodes_count`). Hold/dropped show a muted label in the
  sheet only, no row badge. Every badge/sheet status links to that
  item's `simkl.com` page (mandatory attribution). No badge for
  unidentified titles. Impeccable review pass + detector on the
  changed UI files.
- [ ] Settings sign-in entry + sign-out (S, low): a SIMKL group in
  Settings showing signed-in state (username from
  `GET /users/settings`, cached), "Sign in with SIMKL", and sign-out
  (`POST /oauth2/revoke` + clear tokens + clear snapshot). Signing
  out removes all badges immediately.
- [ ] Rate-limit / quota handling (S, low): honour
  `X-RateLimit-Remaining` and `429` + `Retry-After` on every SIMKL
  call; a 429 backs off that call and surfaces nothing in the list.
  Daily quota is a non-issue for on-demand sync (Free 500/day), but
  a quota-exhausted response is recorded and stops further sync
  attempts until the next day's reset (midnight America/New_York).
- [ ] Tests (M, low): fake-transport tests for the activities gate
  (unchanged watermark → no all-items call), delta merge,
  removal reconciliation, bootstrap, 429/`Retry-After`, token
  refresh (single-flight, refresh-a-day-early, 401 retry once),
  encrypted-store round-trip, and sign-out clearing. ViewModel
  tests: badge appears for an indexed id, no badge without an id,
  failed sync keeps the last snapshot. No live SIMKL account is
  needed for any test.
- [ ] Gates (XS, low): full suite green (baseline 665 tests after
  the TMDB picker), lint 0 errors, detector 0 findings on changed
  UI files, `git diff --check` clean.
