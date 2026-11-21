# Tasks

- [ ] Manual link parsing (S, low): in `TmdbResolver`, add pure
  `parseManualLink(input: String, fallbackType: String?):
  ManualTmdbLink` — sealed `ById(tmdbId, type)` / `ByTitle(title)`
  / `Invalid`. Accepts `themoviedb.org/movie/<id>` and `/tv/<id>`
  links (http/https, optional `www.`, trailing path/query/fragment
  ignored), a bare numeric id (type from `fallbackType`, invalid
  when the type is unknown), and free text (trimmed, at most 160
  chars). Blank or over-long input is `Invalid`.
- [ ] Manual link search (S, low): add `searchManualLink(input,
  fallbackType): ManualTmdbSearch` — sealed `Found(candidates)` /
  `NoMatch` / `Invalid`. An id resolves its detail to a single
  candidate (title, year, type, `tmdbId`); a title reuses
  `searchCandidates` (same normalized-title gate as the picker).
  No key or any TMDB failure means `NoMatch` with zero uncaught
  exceptions; `Invalid` never makes a request.
- [ ] Repository (S, low): add `TroveRepository.searchManualTmdbLink`
  (default empty `NoMatch`); `DebridRepository` delegates to the
  registry's `TmdbResolver` on the IO dispatcher. Add a fake
  override in `FakeRepository` recording inputs.
- [ ] ViewModel (S, low): add `ManualTmdbSearchStatus` (`SEARCHING`
  / `NO_MATCH` / `INVALID`) and a per-download
  `manualTmdbSearch` map in `DownloadsUiState`. Add
  `searchManualTmdbLink(download, input)`: sets `SEARCHING`,
  cancels any in-flight manual search, calls the repository with
  the download's parsed type, then replaces that download's
  candidate list on `Found` (clearing the status), or sets
  `NO_MATCH` / `INVALID`. Prune the map wherever `candidates`
  is pruned.
- [ ] Sheet UI (S, low-med): in `DownloadDetailsState` add
  `manualTmdbSearch: ManualTmdbSearchStatus?`; in
  `DownloadDetailsSheet` add an `onManualTmdbSearch` callback and
  a manual link section shown when metadata is null and a TMDB key
  is configured: a text field (test tag
  `manual_tmdb_link_field`), a Search button (disabled while blank
  or searching), a searching indicator, and the no-match / invalid
  messages. The existing no-key hint is unchanged. Wire the state
  and callback in `DownloadsScreen`.
- [ ] Strings (XS, low): `match_picker_manual_title`,
  `match_picker_manual_label`, `match_picker_manual_search`,
  `match_picker_manual_no_match`, `match_picker_manual_invalid`.
- [ ] Tests (M, low): `TmdbResolverTest` — URL parsing (movie/tv,
  www/http/query/trailing slash, bad host, non-numeric id, bare id
  with/without type, blank, over-long, free text), id search found
  / not found, title search found / not found, no-key makes no
  requests. ViewModel tests — manual search replaces the candidate
  list, invalid input sets `INVALID`, no match sets `NO_MATCH`,
  searching status is set while running, manual search runs even
  when automatic candidates exist.
- [ ] Gates (XS, low): full test suite 0 failures, lint 0 errors,
  detector 0 findings, `git diff --check` clean.
