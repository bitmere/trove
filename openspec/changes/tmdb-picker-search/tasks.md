# Tasks

- [ ] TmdbResolver list search (S, low): extract the match-gathering
  part of private `searchHit` into `searchMatches(query, key):
  List<SearchMatch>`; add public `searchCandidates(query):
  List<MediaCandidate>` mapping the top matches (cap 5, same as
  `MAX_CANDIDATES`) to TMDB-sourced candidates. Same
  normalized-title filter as `searchHit`, no year filter for the
  picker list, TMDB result order. No key means an empty list and
  zero requests; failures (including the 429 cooldown) mean an
  empty list. Keep `searchHit`'s observable behaviour identical —
  existing `TmdbResolverTest` cases must pass unchanged.
- [ ] TmdbResolver resolve-by-TMDB-id (S, low): add public
  `resolveByTmdbId(tmdbId: Int, type: String): MediaMetadata?`
  fetching detail with `primary = true` so `external_ids` supplies
  a verified IMDb id when TMDB knows one. Invalid id/type returns
  null without a request; failures return null.
- [ ] Candidate identity (S, low): extend `MediaCandidate` with
  `tmdbId: Int? = null` and make `imdbId` `String?`. Document the
  invariant that exactly one of the two ids is present; the source
  is derived from which one is set (`tmdbId != null` means TMDB).
  Default values keep existing call sites and test constructions
  compiling. Grep every `MediaCandidate(` / `.imdbId` use before
  landing and fix any missed non-null assumption.
- [ ] Candidates TMDB fallback (S, low): in
  `CinemetaResolver.candidates()`, when the Cinemeta list is empty
  and a TMDB key is configured, return the TMDB search results for
  the same query. When Cinemeta returns anything, return it
  unchanged with no TMDB request. With no key, behaviour is exactly
  as today. The fallback runs inside the existing `network` permit
  (Semaphore 4); no new concurrency budget.
- [ ] Resolve a TMDB pick (M, med): add
  `CinemetaResolver.resolveByTmdbId(tmdbId: Int, type: String)`.
  Extract the TMDB-primary + Cinemeta-backfill merge block from
  `lookupAndStore` into a shared private
  `mergeTmdbPrimary(primary, query)` helper used by both paths so
  they cannot drift. With a verified IMDb id, backfill supplies
  rating, episodes, and missing fields (provenance
  `TMDB_CINEMETA_BACKFILL`); with no poster, run the exact-year
  Cinemeta artwork title search (provenance
  `TMDB_CINEMETA_POSTER`). With no verifiable IMDb id, serve the
  TMDB metadata as-is (provenance `TMDB`; no rating/episodes).
  Never store a pick in the shared disk cache, as with
  `resolveById`.
- [ ] Repository + override persistence (M, low-med): add
  `resolveMetadataByTmdbId(tmdbId: Int, type: String)` to
  `TroveRepository` (default null) and delegate it in
  `DebridRepository`. Extend `MetadataOverride` with
  `tmdbId: Int? = null` and make `imdbId` `String?`.
  `PreferencesStore` adds a fourth key `...:tmdb` (decimal string),
  writes it when present and removes it (and the imdb key) when
  absent. Load returns null only when both ids are absent, so
  legacy overrides (imdb only) load and resolve exactly as today.
- [ ] ViewModel pick/restore branching (S, low-med): `pickCandidate`
  branches on `candidate.tmdbId`; `changeMetadataOverride`
  resolves a TMDB pick via `resolveMetadataByTmdbId` and an IMDb
  pick via `resolveMetadataById`; `loadCandidatesFor` restores a
  stored override through the matching method. Candidates state
  flow, keys, and guards are unchanged.
- [ ] Sheet UI labels + no-key hint (S, low): TMDB rows get a
  " · TMDB" suffix (new string `match_picker_tmdb`); picker
  title/hint unchanged. When metadata is null, candidates are
  empty, and no TMDB key is configured, show a one-line note (new
  string `match_picker_no_key_hint`) pointing at Settings. Needs
  `tmdbConfigured: Boolean` in `DownloadDetailsState` (default
  false), fed by a small `CinemetaResolver.isTmdbConfigured()` +
  repository passthrough. The hint is droppable without affecting
  the core flow (see Q3). Run the Impeccable detector on the
  changed UI file.
- [ ] Tests (M, low): `TmdbResolverTest` — search returns top
  matches capped at 5, empty with no key (assert zero requests),
  empty on 429/transport failure, non-matching titles excluded.
  `MediaMetadataTest` — candidates fall back to TMDB only when
  Cinemeta is empty; no TMDB request when Cinemeta is non-empty;
  no TMDB request with no key; `resolveByTmdbId` merges backfill
  (rating/episodes/poster, `TMDB_CINEMETA_BACKFILL`) and serves
  TMDB-only metadata with no verifiable IMDb id (`TMDB`, no
  rating/episodes); mismatched detail id / invalid type return null
  without extra requests. App tests — override round-trip with
  tmdbId and legacy override compat. ViewModel — pick a TMDB
  candidate stores the tmdbId override and resolves it; a stored
  TMDB override restores on sheet open (extend the existing test
  fake with the new repository methods).
- [ ] Gates (XS, low): focused tests, full suite, lint 0 errors,
  Impeccable detector 0 findings on changed UI files. Report the
  test count and baseline delta. No version.properties or
  CHANGELOG.md changes; no PR, merge, tag, or release in this
  stage.
