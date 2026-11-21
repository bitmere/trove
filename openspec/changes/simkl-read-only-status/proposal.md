# SIMKL read-only status badges

## What the user asked for

"Is it okay to simkl too see like progress is watcher or in watch list etc" —
"we are basically make it like nuvio now". The user chose **read-only
status badges**: for each download, show whether that title is in the
user's SIMKL library — watching, on the watchlist, completed — plus
episode progress for shows ("S2 E4 · 12/20 episodes"). Sign-in happens
in Trove; the user reviews this plan before any SIMKL code is written.

## Verified current state

All line numbers are main @ `f05b4bf8` (Trove 0.1.32).

- Trove has **zero SIMKL code today** — no client, no auth, no
  storage, no UI. Enrichment carries an IMDb id for matched titles
  (and a TMDB id when TMDB-first resolves), which is the identity
  SIMKL accepts directly.
- Matrix (`bitmere/matrix-launcher` @ `605fd6ed`) already has a full
  **read-only Auth V2** integration to copy the *architecture* from
  (never the code): `SimklClient.java`, `SimklDiskCache`,
  `SimklRequestPolicy`/`SimklCachePolicy`. Precedent: device/PIN flow,
  `media:read` only, tokens in plain `SharedPreferences "simkl_v2"`
  with single-flight refresh. Trove improves on that last point:
  tokens are stored **encrypted**, like the TorBox key.
- Nuvio (`Br0ck25/NuvioTVLauncher`, branch `dev`, GPL-3.0) has a
  large SIMKL layer (62 files) as a second architecture reference —
  study only, never copy code. The read-only-relevant parts, skimmed
  2026-11-21: `SimklSyncEngine` (activities watermark → delta fetch
  → removal reconciliation), `SimklRefreshPolicy` (automatic refresh
  at most every 15 min), `SimklAuthRepository` (device/PIN flow,
  `user_code` + verification URI + single-flight `pollPin`),
  `SimklAuthV2Interceptor` (adds `client_id`, refreshes a day early
  or after a 401, single-flight refresh lock),
  `SimklEntryIndex` (index keyed by SIMKL id + external-id aliases),
  `SimklLibraryProjection` (UI projection). Nuvio also does two-way
  sync — mutations, scrobble, playback reconciliation — which is
  **out of scope here**. Nuvio uses Dagger/Hilt and OkHttp; Trove
  has no DI framework and its own `HttpTransport`, so the token
  attach/refresh logic is reimplemented fresh in Trove's own terms.

## SIMKL facts (verified from https://api.simkl.org/llms-full.txt)

- **Auth V2 only.** V1 retires ~April 2027. Register a V2 app at
  `simkl.com/settings/developer/` — free, no approval. Client type
  **"Mobile, desktop & browser apps"** (no `client_secret` minted;
  a redirect URI is still required) — or "TV, devices & command
  line" if only the device flow is ever used. The type is fixed at
  registration.
- `client_id` is **public and safe to ship in the app / commit in the
  repo** per the docs. Never ship a `client_secret` in an installable
  binary. There is none for this client type.
- **Device/PIN flow:** `POST /oauth2/device` (scope `media:read`
  ONLY for read-only — scope must be requested up front; widening
  later forces the user through consent again) → show the 8-char
  `user_code` + open `verification_uri_complete` in a browser →
  poll `POST /oauth2/token` (device-code grant) every 5 s, 15-min
  expiry. Access token **7 days**, refresh token **180 days
  sliding** (regular use never needs re-consent). Sign-out =
  `POST /oauth2/revoke` + clear local storage.
- **Sync model — hard rule:** never poll `/sync/all-items` on a
  timer. Apps that do get their `client_id` **suspended without
  warning, no appeal**. Correct loop: `GET /sync/activities` (tiny
  last-modified timestamps per category) → only if changed,
  `GET /sync/all-items/{type}/{status}?date_from=<saved all
  timestamp>` → save the new snapshot. Trove is stricter than
  Nuvio's 15-minute automatic interval: sync is **on demand only**
  (details-sheet open, pull-to-refresh, Settings) — no timer at all.
- Statuses: `watching`, `plantowatch`, `hold`, `completed`,
  `dropped` (movies skip `watching`/`hold`). `extended=full` adds
  per-episode `seasons[].episodes[]`, `watched_episodes_count`,
  `user_rating`, `next_to_watch_info`.
- Identity: SIMKL accepts IMDb/TMDB/SIMKL ids inline, so a separate
  resolve step is usually unnecessary. `GET /redirect?imdb=tt…` (or
  `?tmdb=…&type=movie|tv`) returns 301 — read the `Location`
  header, never follow it. **Titles with no enrichment match have
  no usable id → no badge.** They just show no badge; that is not
  an error.
- Every call under `https://api.simkl.com` needs `client_id`,
  `app-name`, `app-version` params + `User-Agent`; user calls need
  `Authorization: Bearer`.
- Rate limits: 10 GET/sec, 1 POST/sec; daily quota per user (V2):
  Free 500, PRO 1,000, VIP 10,000; resets midnight
  America/New_York. Honour `X-RateLimit-Remaining` + `429` /
  `Retry-After`. Read-only means GETs only, so the 1 POST/sec
  limit never binds except token exchange.
- **Attribution is mandatory** wherever SIMKL data appears: link
  back to that specific item's `simkl.com` page, not just the
  homepage. Commercial use without attribution needs a license;
  Trove is a free app with attribution.

## Scope

In scope: (a) read-only status — new `core` SIMKL client
(transport + encrypted token store + disk snapshot cache,
activities-gated `date_from` deltas), status index keyed by
IMDb/TMDB id, badge in list rows and the details sheet; (c) sign-in
flow — device/PIN screen, token exchange + refresh, encrypted
storage, sign-out.

Out of scope: two-way actions (`POST /sync/add-to-list`,
`/sync/history`, `/sync/ratings`) — the user chose read-only, and
the scope was requested up front as `media:read` only, so a write
would force re-consent anyway. Also out: scrobbling/playback sync,
Custom Lists (beta, PRO/VIP-gated), anime ids, per-title
`/sync/watched` checks (the docs say not to use it when all-items
is already pulled).

## Open questions

- Q1 — Does the user have a SIMKL account, and which plan? Free's
  500 requests/day is plenty for on-demand sync.
- Q2 — App registration: register the V2 app under bitmere and ship
  its `client_id` in Trove (docs-blessed, recommended), or a
  user-entered client ID in Settings (Matrix precedent)?
- Q3 — Sync shape: full-library sync indexed by id (recommended —
  matches Trove's list UI and the docs' two-phase model) rather
  than per-title `/sync/watched` checks?
