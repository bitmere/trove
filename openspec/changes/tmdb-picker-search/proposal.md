# Manual TMDB search in the details-sheet picker

## What the user asked for

When automatic enrichment finds no match for a download, the user asked
whether they can search and link it using TMDB. They approved adding
TMDB search to the picker.

One correction to the mental model, verified from the code: the picker
has **no text input today**. It lists candidates automatically for the
parsed release title when the automatic match fails. So this change
adds TMDB as a fallback source for that same parsed title — it does
not add a free-text search field. Whether a typed search field is
also wanted is open question Q1 below.

## Verified current state

All line numbers are main @ `f05b4bf8` (Trove 0.1.32).

- The picker is Cinemeta-only. `CinemetaResolver.candidates(query)`
  (`core/.../MediaMetadata.kt:471-480`) calls `fetchTitleMatches`
  (`:520-530`), which hits Cinemeta's catalog search
  (`catalog/$type/top/search=...json`) and maps matches to
  `MediaCandidate(imdbId, title, year, type)` (`:80`). No TMDB
  call happens anywhere in the candidate path.
- `MediaCandidate` carries an IMDb id as its identity
  (`imdbId: String`, non-null). A TMDB candidate has no IMDb id
  until its detail is fetched, so the type must change to carry one.
- `resolveById(imdbId, type)` (`MediaMetadata.kt:485-493`) fetches
  Cinemeta detail by IMDb id, with TMDB only as a poster fallback
  (`resolvePoster`). It is deliberately NOT stored in the shared disk
  cache: a pick is per download.
- `TmdbResolver` (`core/.../TmdbResolver.kt`) has title search
  internally but no list API: private `searchHit(query, key, strict)`
  calls `/3/search/movie` + `/3/search/tv`, filters to normalized
  title equality, and returns a single `TmdbHit?`. Private
  `detail(hit, imdbId, config, key, primary)` fetches detail +
  credits; `primary = true` appends `external_ids`, which is how a
  verified IMDb id is obtained. Public methods today:
  `resolvePrimary`, `resolve`, `resolvePoster`, `resolveById`
  (IMDb id via `/3/find`), `validateKey`. `configuredKey()` is null
  when no key is set, and every public method returns null (zero
  TMDB requests) in that case. HTTP 429 honours `Retry-After` and
  sets a shared `blockedUntil` cooldown; public methods return null
  on failure.
- The TMDB-primary + Cinemeta-backfill merge already exists in
  `lookupAndStore` (`MediaMetadata.kt:318-345`): TMDB primary, then
  `lookupById(primaryId, primaryType)` backfill (rating, episodes,
  overview/genres/cast/director/runtime, poster), provenance
  `TMDB_CINEMETA_BACKFILL` when the merge changes anything, and an
  exact-year Cinemeta artwork title search when still posterless
  (provenance `TMDB_CINEMETA_POSTER`). A TMDB pick should reuse
  this merge, not duplicate it.
- The picker flow: `DownloadsViewModel.loadCandidatesFor`
  (`DownloadsViewModel.kt:944-980`) resolves a stored override first
  (via `repository.resolveMetadataById`), otherwise calls
  `repository.resolveMetadataCandidates(parseMediaName(download.name))`
  and publishes the list into `candidates` state keyed by
  (kind, id). `pickCandidate` (`:982-984`) stores
  `MetadataOverride(candidate.imdbId, candidate.type, candidate.title)`;
  `changeMetadataOverride` (`:989-...)` resolves the pick via
  `repository.resolveMetadataById(pick.imdbId, pick.type)`.
- `MetadataOverride(imdbId: String, type: String, title: String)`
  (`TroveRepository.kt:55`) is IMDb-id-keyed. `PreferencesStore`
  (`PreferencesStore.kt:175-199`) persists it as three plain-pref
  keys `metadata_override_<kind>:<id>:{imdb,type,title}`; load
  returns null unless imdb + type are present. The repository
  interface (`TroveRepository.kt:109-113`) declares
  `resolveMetadataCandidates` / `resolveMetadataById` with null
  defaults; `DebridRepository.kt:186-190` delegates both to the
  metadata resolver.
- The sheet (`DownloadDetailsSheet.kt`) renders the picker only when
  `metadata == null && candidates.isNotEmpty()`: title
  `match_picker_title` ("Which one is this?"), hint
  `match_picker_hint`, and one `TextButton` per candidate,
  "Title (year) · Movie/Series". No text field, no source label,
  no poster thumbnails. The provenance line already has TMDB
  strings (`enrichment_tmdb`, `enrichment_cinemeta_backfill`).
- Tests: `MediaMetadataTest` candidate tests (`:535`, `:555`)
  assert exact `MediaCandidate` equality; `resolveById` tests
  (`:570`, `:578`). `TmdbResolverTest.FakeTmdb` stubs
  `searchMovieJson` / `searchTvJson` and records requests; `no key
  means no TMDB request at all` (`:228`). `DownloadsViewModelTest`
  has no picker tests today. `CinemetaResolver` is constructed with
  a TMDB resolver in tests as
  `CinemetaResolver(cinemeta, tmdbResolver = TmdbResolver(http) { "key" })`.

## Proposed change

- `TmdbResolver` gains a list search, `searchCandidates(query)`,
  by extracting the match-gathering part of private `searchHit`
  into `searchMatches(query, key): List<SearchMatch>` and mapping
  the top matches (cap 5, same as `MAX_CANDIDATES`) to
  TMDB-sourced candidates. Same normalized-title filter as today,
  no year filter for the picker list, TMDB result order. Key
  required: no key means an empty list and zero requests.
  Failures (including the 429 cooldown) mean an empty list.
- `TmdbResolver` gains `resolveByTmdbId(tmdbId, type)`, which
  fetches detail with `primary = true` so `external_ids` supplies
  a verified IMDb id when TMDB knows one. Invalid id/type returns
  null without a request; failures return null.
- `MediaCandidate` carries the TMDB identity: `tmdbId: Int? = null`
  is added and `imdbId` becomes `String?`. Exactly one of the two
  ids is present (documented invariant); the source is derived from
  which one is set (`tmdbId != null` means TMDB). Default values
  keep existing call sites and test constructions compiling.
- `CinemetaResolver.candidates()` falls back to TMDB **only when
  the Cinemeta list is empty**. When Cinemeta returns anything, the
  result is unchanged and no TMDB request is made. With no TMDB
  key configured, behaviour is exactly as today (Cinemeta only).
  The fallback runs inside the existing `network` permit (Semaphore
  4), so no new concurrency budget is added.
- `CinemetaResolver` gains `resolveByTmdbId(tmdbId, type)`: TMDB
  primary via the new resolver method, then the same backfill merge
  as `lookupAndStore` — the merge block is extracted into a shared
  private `mergeTmdbPrimary(primary, query)` helper used by both
  paths so they cannot drift. With a verified IMDb id, backfill
  supplies rating, episodes, and missing fields (provenance
  `TMDB_CINEMETA_BACKFILL`); with no poster, the exact-year
  Cinemeta artwork title search runs (provenance
  `TMDB_CINEMETA_POSTER`). With no verifiable IMDb id, the TMDB
  metadata is served as-is (provenance `TMDB`; no rating/episodes
  — the accepted trade-off from PR #42). Like `resolveById`, a
  pick is never stored in the shared disk cache.
- Repository + persistence: `TroveRepository` gains
  `resolveMetadataByTmdbId(tmdbId, type)` (default null) and
  `DebridRepository` delegates it. `MetadataOverride` gains
  `tmdbId: Int? = null` and `imdbId` becomes `String?`;
  `PreferencesStore` adds a fourth key `...:tmdb` (decimal string),
  writes it when present and removes it (and the imdb key) when
  absent. Load returns null only when both ids are absent, so
  legacy overrides (imdb only) load and resolve exactly as today.
- ViewModel: `pickCandidate` branches on `candidate.tmdbId`;
  `changeMetadataOverride` resolves a TMDB pick via
  `resolveMetadataByTmdbId` and an IMDb pick via
  `resolveMetadataById`; `loadCandidatesFor` restores a stored
  override through the matching method. The candidates state flow,
  keys, and guards are unchanged.
- Sheet UI: TMDB rows get a " · TMDB" suffix (new string
  `match_picker_tmdb`); the picker title/hint are unchanged. When
  metadata is null, candidates are empty, and no TMDB key is
  configured, the sheet shows a one-line note (new string
  `match_picker_no_key_hint`) pointing at Settings. That needs a
  `tmdbConfigured: Boolean` in `DownloadDetailsState` (default
  false), fed by a small `CinemetaResolver.isTmdbConfigured()` +
  repository passthrough. The hint is droppable without affecting
  the core flow (see Q3).
- Tests pin: TMDB fallback only on empty Cinemeta, no TMDB call
  when Cinemeta is non-empty or no key is set, resolve-by-TMDB-id
  with backfill and without a verifiable IMDb id, override
  round-trip incl. legacy compat, and the picker source label.
  Gates: full suite, lint 0 errors, Impeccable detector on
  changed UI files.

## Non-goals

- No change to the strict automatic title+year match gate, the
  1 h miss TTL, or the automatic TMDB-first / Cinemeta-first
  order.
- No free-text search field in the sheet (see Q1 — that is a
  separate UI item if wanted).
- No merged Cinemeta + TMDB candidate lists and no reordering of
  Cinemeta results.
- No disk-cache storage of picks; a pick stays per download via
  the existing override, as today.
- No TMDB search in the main Downloads search bar.
- No version bump, changelog entry, PR, merge, tag, or release.
