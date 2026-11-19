# Tasks — metadata enrichment

Sizes: S ≤ 1 day, M 2–4 days, L 1–2 weeks. All tasks are unchecked:
nothing here is approved until the user approves item by item.

## Phase 1 — show what Cinemeta already returns (S, low risk) — DONE Batch 1

- [x] Extend `MediaMetadata` (`core/.../MediaMetadata.kt:14-16`) with
  `imdbId`, `overview`, `genres` (cap 5), `cast` (cap 5 names),
  `director`, `imdbRating`, `runtime`; parse them from the detail
  response the resolver already fetches. Store the validated IMDb id.
- [x] Show synopsis + a facts row (year · genres · runtime · IMDb rating)
  in the details sheet (`DownloadDetailsSheet.kt`). No new network calls.
- [x] Add focused tests first: detail-response parsing incl. missing
  fields, IMDb id stored, fallback when a field is absent.
- [x] Run focused tests, then full JVM suite, then lint. (Do not compile
  before the user approves this spec.)

## Phase 2 — series episode list (S, low risk) — DONE Batch 1

- [x] Parse `videos[]` from the series detail response into a compact
  season/episode list (season, episode, title, released — no per-episode
  overviews or thumbnails, to keep entries small).
- [x] Show the episode list in the details sheet, highlighting the episode
  matching `parseReleaseDetails`; cap the rendered list (current season +
  scroll) so a 300-episode series does not compose hundreds of rows.
- [x] Add focused tests first: episode parsing, matching highlight,
  empty `videos[]`.
- [x] Run focused tests, then full JVM suite, then lint.

## Phase 3 — recall fixes (S, low risk) — DONE Batch 1

- [x] Year ±1 fallback: if the exact-year match misses, retry allowing
  year ±1 (exact title and single-candidate rule unchanged).
- [x] Strip a trailing absolute episode number before matching
  (`"Show 1071"` → `"Show"`, type series) when no `SxxExx` token was
  found. Guard added: only strip when the number is 3–4 digits AND
  > 100, so `Apollo 13` and `The 100` keep their titles.
- [x] Add regression tests incl. `Blade Runner 2049` without a release
  year (year-like sequel number must not become the year).
- [x] Run focused tests, then full JVM suite, then lint.

## Phase 4 — durable metadata disk cache (M, medium risk) — DONE Batch 2 + wiring 2026-11-19

- [x] Add `MetadataDiskCache` in `core/` next to `PosterDiskCache`: one
  JSON file per entry under `filesDir/metadata/`, SHA-256 filename of
  `normalizedTitle|year|type`, 512 entries / 2 MB, 180-day TTL, LRU by
  `lastModified`, temp-file + atomic rename write, `schemaVersion` in
  each entry (old entries ignored, not migrated). Stale-while-revalidate:
  show the stale entry immediately, re-fetch in the background.
  (Per-entry cap 128 KB added beyond the spec — a 4,000-episode series
  entry is rejected rather than blowing the budget; confirm.)
- [x] Wire the resolver to consult disk after the memory LRU and before
  the network. The ViewModel/repository shape does not change.
  (Resolver side done. App-layer wiring in `DebridRepository` — pass a
  `MetadataDiskCache(File(context.filesDir, "metadata"))` into the
  resolver — is still pending, after the unification worker finishes.)
- [x] Bound the in-memory no-match cache: give no-match/ambiguous results
  a 1 h TTL instead of `Long.MAX_VALUE`.
- [x] Add a "Clear cached metadata" row in Settings → Library.
  DONE 2026-11-19 after unification: `DebridRepository` takes an
  optional `metadataDiskCache` (production secondary ctor passes
  `MetadataDiskCache(File(context.filesDir, "metadata"))` with a
  revalidation scope), `clearCachedMetadata()` passthrough on
  `TroveRepository` (default no-op), ViewModel action + `LibraryContent`
  row with confirmation feedback. Focused test in
  `DebridRepositoryTest` seeds the cache and asserts it empties.
- [x] Add focused tests first: round-trip, eviction, TTL expiry, stale
  entry revalidation, corrupt file handling.
- [x] Run focused tests, then full JVM suite, then lint. Phone-check:
  cold start with a large library shows cached titles immediately,
  offline cold start shows cached metadata with no spinner.

## Phase 5 — candidate picker + per-download override (M, medium risk) — DONE Batch 3a, gate green 2026-11-19 (751 tests, lint 0 errors)

- [x] On ambiguous/no-match, show up to ~5 Cinemeta candidates (title +
  year + type) in the details sheet; the user's pick is stored per
  download id in the existing encrypted/preferences store (separately from
  the metadata cache), with a "clear override" path.
  (`CinemetaResolver.candidates()` reuses the catalog fetch; pick stored
  per (kind, id) as `MetadataOverride(imdbId, type, title)` in plain
  SharedPreferences, NOT the CredentialStore, separate from the disk
  cache; `resolveById` fetches the picked detail and is deliberately
  NOT written to the shared caches. Override detail is re-fetched per
  refresh for overridden rows; a transient failure on a stored pick
  falls through to the picker with the pick retained.)
- [x] Add focused tests first: picker shows candidates, pick persists,
  clear override restores the parsed title. (14 new tests; written in
  the same pass as the implementation, all specified behaviours
  covered.)
- [x] Run focused tests, then full JVM suite, then lint. Phone-check the
  picker on a remake (same title + year) and a non-English title.

## Phase 6 — TMDB as opt-in fallback (M, medium risk; only after Phases 1, 4, 5) — DONE Batch 3b, gate green 2026-11-19 (776 tests, lint 0 errors)

- [x] Settings field for a per-user TMDB key (API Read Access Token,
  Bearer header), stored encrypted like other provider credentials.
  Never ship a shared key.
  (Library group; masked with Show/Hide toggle; Save validates via
  `GET /3/configuration` first, then stores encrypted under
  `tmdb-1`; Clear removes it. One lint warning in `TmdbSettingsTest`
  — nullable-String platform-type mismatch — benign, build green.)
- [x] TMDB resolver: `GET /3/find/{imdbId}?external_source=imdb_id`
  first (bridges the stored IMDb id in one call), title search second
  (`/3/search/movie`, `/3/search/tv`); read image base URL + sizes from
  `GET /3/configuration`, never hardcode the CDN host.
  (Same strictness as Cinemeta for title search. Coordinator fixes:
  an Elvis-precedence bug — `?:` bound to the else-branch only,
  leaving the match nullable — and a nested test fake referencing
  outer `configJson` — made it `inner`.)
- [x] TMDB-sourced fields respect the 6-month cache cap (the 180-day TTL
  in Phase 4 already satisfies this; verify per entry by `source`).
  (No disk-cache change needed — it already records `source` per
  entry; TMDB results stored with `source = "tmdb"`.)
- [x] TMDB attribution in About/Settings: logo + "This product uses the
  TMDB API but is not endorsed or certified by TMDB".
  (Text notice in Settings → Library under the TMDB field, using the
  binding Terms §3 wording: "This product uses TMDB and the TMDB APIs
  but is not endorsed, certified, or otherwise approved by TMDB."
  **Logo MISSING** — no approved logo asset could be sourced cleanly;
  Terms §3 requires the logo, so an approved logo asset still needs
  adding before this ships as compliant.)
- [x] Add focused tests first: `/find` bridging, key-missing behaviour,
  attribution present.
  (25 new tests, all against fake transports; no live key exists.)
- [x] Run focused tests, then full JVM suite, then lint. Verify the exact
  TMDB Terms of Use cache/attribution clause text before shipping.
  (**Verified verbatim 2026-11-19 from themoviedb.org/api-terms-of-use:**
  §1.C — "Cache, for longer than 6 months, any information obtained
  through or from TMDB or the TMDB APIs." §3 — logo required, notice
  as quoted above. FAQ confirms the shorter notice form and that
  attribution belongs in an "About"/"Credits" section.)
- Deviations: `vote_average` is NOT mapped to `imdbRating` (the sheet
  renders that field as "IMDb x.x"; a TMDB score is not an IMDb
  rating, so TMDB results leave it null). No episode list for TV
  results (needs one call per season; episodes still come from
  Cinemeta). `/find` is implemented and tested, but the live list
  flow has no IMDb id at fallback time, so in practice the fallback
  runs via title search.

## Deferred / declined (do not implement)

- [ ] Confidence-scored fuzzy matching — only with Phase 5 in place.
  Never loosen the automatic gate without a correction path.
- [ ] Backdrop/logo hero in the details sheet — deferred (image budget on
  low-memory phones).
- [ ] TVDB integration — declined (licensing/key model; data mostly
  available keyless from Cinemeta).
- [ ] Shipped/shared TMDB key or developer proxy — declined.
- [ ] Subtitles via Cinemeta — not available; no work to do.
