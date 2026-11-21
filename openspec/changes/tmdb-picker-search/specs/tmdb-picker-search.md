# TMDB search in the details-sheet picker

## TMDB list search

- `TmdbResolver` exposes `searchCandidates(query: MediaQuery):
  List<MediaCandidate>`. It requires a configured key: with no key
  it returns an empty list and performs zero TMDB requests.
- The search calls `/3/search/movie` and `/3/search/tv` for the
  query title (both endpoints when the query type is unknown),
  reusing the existing normalized-title filter (localized title or
  original title equals the normalized query title). It does NOT
  filter by year: the picker shows all years and the user chooses.
- Results are in TMDB's returned order and capped at 5 (the same
  `MAX_CANDIDATES` as the Cinemeta picker). Each result carries
  its TMDB id, title, year, and type, with `tmdbId` set and
  `imdbId` null.
- Any failure — transport error, non-2xx, or the shared 429
  cooldown — yields an empty list, never an exception. The 429
  handling (shared `blockedUntil`, `Retry-After`) is unchanged.
- The match gathering is factored out of private `searchHit` into
  `searchMatches(query, key): List<SearchMatch>`; `searchHit`
  picks from it with its existing strict single-hit semantics, so
  automatic enrichment behaviour is unchanged.

## TMDB resolve by id

- `TmdbResolver` exposes `resolveByTmdbId(tmdbId: Int, type:
  String): MediaMetadata?`. It fetches detail with `primary =
  true`, so `external_ids` supplies a verified IMDb id when TMDB
  knows one. The detail id must equal the requested TMDB id or
  the result is null.
- An invalid TMDB id (<= 0) or a type outside `movie` / `series`
  returns null without a network request. Failures return null.
- The key is sent only in the `Authorization: Bearer` header, as
  with every other TMDB call. No key means null with zero
  requests.

## Candidate identity

- `MediaCandidate` is `MediaCandidate(val imdbId: String?, val
  title: String, val year: Int?, val type: String, val tmdbId:
  Int? = null)`.
- Exactly one of `imdbId` / `tmdbId` is present (documented
  invariant). A candidate's source is derived from which id is
  set: `tmdbId != null` means the candidate came from TMDB,
  otherwise from Cinemeta.
- Default values keep every existing construction site and test
  compiling, and existing `MediaCandidate` equality assertions
  (tmdbId null) still hold.

## Candidates fallback

- `CinemetaResolver.candidates(query)` returns the Cinemeta list
  unchanged whenever it is non-empty, and performs no TMDB request
  in that case.
- Only when the Cinemeta list is empty and a TMDB key is
  configured does it call `TmdbResolver.searchCandidates(query)`
  and return those results for the same query.
- With no TMDB key configured, `candidates()` behaves exactly as
  today (Cinemeta only, zero TMDB requests).
- A Cinemeta transport failure still propagates as today (the
  ViewModel catches it and shows no candidates); the TMDB fallback
  does not mask it.
- The fallback runs inside the existing `network` permit
  (Semaphore 4) that already guards `candidates()`; no new
  concurrency budget, lane, or cache entry is added.

## Resolving a TMDB pick

- `CinemetaResolver.resolveByTmdbId(tmdbId: Int, type: String):
  MediaMetadata?` resolves a user-picked TMDB candidate.
- It fetches the TMDB primary via `TmdbResolver.resolveByTmdbId`.
  When the primary carries a verified IMDb id, it backfills via
  `lookupById(imdbId, type)` and merges with the same rules as
  automatic TMDB-first enrichment: year/poster/overview/genres/
  cast/director/runtime from the backfill when the primary lacks
  them, `imdbRating` and `episodes` from the backfill, and
  provenance `TMDB_CINEMETA_BACKFILL` when the merge changes
  anything (otherwise `TMDB`).
- When the merged result still has no poster, the exact-year
  Cinemeta artwork title search runs (`lookup(artworkQuery(query,
  primary), exactYear = true)`); an accepted artwork sets the
  poster with provenance `TMDB_CINEMETA_POSTER`.
- When no IMDb id can be verified, the TMDB metadata is served
  as-is with provenance `TMDB`: title, year, poster, overview,
  genres, cast, director, runtime when TMDB supplies them, and no
  IMDb rating or episodes.
- The merge block is shared: the TMDB-primary + backfill merge in
  `lookupAndStore` is extracted into a private
  `mergeTmdbPrimary(primary, query)` helper used by both
  `lookupAndStore` and `resolveByTmdbId`, so the two paths cannot
  drift.
- Like `resolveById`, a pick is never stored in the shared disk
  cache: it applies to this download only, and the per-download
  override in preferences is what makes it durable.

## Override persistence

- `MetadataOverride` is `MetadataOverride(val imdbId: String?,
  val type: String, val title: String, val tmdbId: Int? = null)`.
- `PreferencesStore` persists a fourth key,
  `metadata_override_<kind>:<id>:tmdb`, holding the decimal TMDB
  id. Saving writes it when present and removes it when absent;
  the imdb key is likewise written only when present and removed
  when absent. Clearing an override removes all four keys.
- Loading returns null only when both ids are absent. A legacy
  override (imdb key only, no tmdb key) loads with `tmdbId =
  null` and resolves through the existing IMDb path, exactly as
  today. The title falls back to the imdb id, else to
  `"tmdb:<id>"`, when no title was stored.
- The repository interface gains `resolveMetadataByTmdbId(tmdbId:
  Int, type: String): MediaMetadata?` (default null) and
  `DebridRepository` delegates it to the metadata resolver with
  the existing `ioDispatcher` wrapping.

## ViewModel pick and restore

- `pickCandidate` stores a `MetadataOverride` carrying the
  candidate's TMDB id when `candidate.tmdbId != null`, and the
  IMDb id otherwise.
- `changeMetadataOverride` resolves a TMDB pick via
  `repository.resolveMetadataByTmdbId(pick.tmdbId, pick.type)`
  and an IMDb pick via `repository.resolveMetadataById(pick.imdbId,
  pick.type)`. Saving, clearing, `overrideEdits`, hydration
  queue handling, and the `current()` guards are unchanged.
- `loadCandidatesFor` restores a stored override through the
  matching method (TMDB id when present, else IMDb id) before
  loading fresh candidates. The candidates state map, its key,
  and the `key !in _state.value.metadata` early exit are
  unchanged.

## Picker UI

- The picker still renders only when `metadata == null &&
  candidates.isNotEmpty()`, with the existing title and hint.
- A TMDB-sourced row appends " · TMDB" to its label (new string
  `match_picker_tmdb`). Cinemeta rows are unchanged.
- When metadata is null, candidates are empty, and no TMDB key is
  configured, the sheet shows a one-line note (new string
  `match_picker_no_key_hint`) pointing at Settings. This needs
  `tmdbConfigured: Boolean` in `DownloadDetailsState` (default
  false), fed by `CinemetaResolver.isTmdbConfigured()` through a
  repository passthrough. The note is droppable without affecting
  the core flow.
- Tapping a TMDB row behaves like tapping a Cinemeta row today:
  the pick is stored, the sheet shows the picked title, and
  "Clear override" restores the parsed title and normal
  resolution.
- The Impeccable detector runs on the changed UI file; the change
  adds no new dependency, no new screen, and no new background
  work.
