# Metadata enrichment: show what Cinemeta already returns, then deepen

## What the user asked for

Can Trove enrich web + torrent download data with TMDB / TVDB / Cinemeta,
and cache it?

## Verified current state (0.1.23, from code)

Enrichment is **not greenfield — it already ships**. Verified in
`core/src/main/java/app/trove/debrid/core/MediaMetadata.kt`:

- `parseMediaName` (lines 47–63) parses title/year/type from the release
  filename; `parseReleaseDetails` (lines 25–45) extracts episode + quality
  for the card subtitle.
- `CinemetaResolver` (lines 82–136) resolves keyless against
  `v3-cinemeta.strem.io`: catalog search (`catalog/{movie|series}/top/search=…`)
  then detail fetch (`meta/{type}/{id}.json`). Match rule is deliberately
  strict — exact normalized title, same year if parsed, valid `tt` id,
  **single** candidate only, re-validated against the detail response.
  Ambiguous/missing/failed keeps the parsed title, so a wrong poster is
  near-impossible; the price is low recall.
- Cards show poster + real title + year + episode/quality
  (`DownloadCard.kt:70-100`, `DownloadPresentation.kt:78-83`). The details
  sheet shows files and actions only — **no synopsis, genres, cast, or
  rating anywhere in the UI**.
- Caches: 256-entry in-memory LRU (successes and no-match results cached
  forever for the process lifetime; only transient failures get a 60 s
  expiry), 32 MB SHA-256 poster disk cache in `cacheDir/posters` with LRU
  eviction, 4 MB sampled bitmap LRU. One global mutex serializes all
  resolver requests; rows hydrate sequentially after the list renders
  (`DownloadsViewModel.kt:150-163`).

**The detail response Trove already fetches on every hit contains far more
than Trove reads** (Stremio Meta Object definition): `description`
(synopsis), `genres[]`, `cast[]`, `director`, `imdbRating`, `runtime`,
`background`, `logo`, and for series the full episode list (`videos[]`
with season/episode/title/released/overview/thumbnail). All of it is
currently read and dropped.

Discrepancies found vs the earlier description of this feature:
- Hydration runs in `viewModelScope` (Main); only the network hop is IO.
- Successful no-match results are cached forever in-session, not with a TTL.
- The validated IMDb id is thrown away — `MediaMetadata` has no `imdbId`
  field, which blocks any future TMDB bridge.

## Decisions for review

1. **Phase 1 is parsing + UI only, no new network dependency.** Show the
   synopsis, facts row, and episode list from data already fetched.
   Store the IMDb id at the same time.
2. **Recall fixes are cheap and safe, and stay behind the single-candidate
   rule:** year ±1 fallback and stripping a trailing absolute episode
   number (anime case). No fuzzy matching without a correction UI.
3. **Durable metadata cache is plain JSON files in `filesDir/metadata/`**
   (one per title, SHA-256 filename, 512 entries / 2 MB, 180-day TTL,
   LRU), reusing the `PosterDiskCache` pattern. **Not Room, not
   DataStore:** no such dependency exists and a 512-entry key/value store
   does not need SQLite; this adds zero dependencies and stays
   Android-free/JVM-testable. Cache is shared across providers (metadata
   is per-title, not per-provider).
4. **TMDB is opt-in with a per-user key only** (Settings field, encrypted
   like other credentials, as Matrix already does). A key baked into a
   public APK is extractable and revocable — never ship a shared key, and
   no developer proxy. TMDB attribution (logo + "not endorsed" notice) is
   mandatory and lands with the TMDB item. TMDB data must not be cached
   longer than 6 months.
5. **TVDB is declined** (see plan doc §4): worst licensing of the three —
   negotiated project key that cannot be safely shipped, or $11.99/year
   per-user subscription + PIN — and its unique episode data is mostly
   already available keyless from Cinemeta.
6. **Candidate picker before any fuzzy matching.** A correction UI in the
   details sheet (up to ~5 candidates, pick stored per download id) must
   exist before the automatic gate is loosened; a wrong title with no way
   to fix it is worse than a missing poster.
7. **Subtitles via Cinemeta and in-app playback are out of scope.**
   Cinemeta serves no subtitles resource; playback stays with the external
   player (already decided).

## Out of scope

- Backdrop/logo hero in the details sheet (deferred — large images on a
  low-memory phone; judge after phases 1–2 land).
- Full-text "search and correct" (defer until the picker proves users
  actually correct titles).
