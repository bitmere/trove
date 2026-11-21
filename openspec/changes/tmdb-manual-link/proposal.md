# Manual TMDB link in the match picker

## What the user asked for

When the automatic match fails, the user wants to link a title by
hand in the details-sheet picker — by typing a title, pasting a
TMDB link, or entering a TMDB id.

## Verified current state

All line numbers are main @ `10ae971d` (Trove 0.1.33).

- The picker is automatic only. `CinemetaResolver.candidates()`
  (`core/.../MediaMetadata.kt:449`) lists Cinemeta matches for the
  parsed release title, falling back to
  `TmdbResolver.searchCandidates` only when the Cinemeta list is
  empty. There is no text field or manual link anywhere in the
  picker (`DownloadDetailsSheet.kt:201-227`).
- `TmdbResolver.searchCandidates(query)` (`TmdbResolver.kt:49`)
  title-searches TMDB (normalized-title equality gate, cap 5, TMDB
  order) and returns `MediaCandidate`s with `tmdbId`. No key means
  an empty list and zero requests.
- `TmdbResolver.resolveByTmdbId(tmdbId, type)` (`TmdbResolver.kt:69`)
  fetches TMDB detail with `primary = true` (verified IMDb id via
  `external_ids`). `CinemetaResolver.resolveByTmdbId`
  (`MediaMetadata.kt:480`) merges it with the Cinemeta backfill,
  exactly as the automatic TMDB-first path does.
- A pick is stored per download as `MetadataOverride`
  (`...:tmdb` key) and resolved through
  `DownloadsViewModel.pickCandidate` -> `changeMetadataOverride` ->
  `repository.resolveMetadataByTmdbId`. Picks are never stored in
  the shared disk cache.
- The TMDB key field is in Settings (`tmdb_key_field`) and is
  already configured. The sheet shows a no-key hint
  (`match_picker_no_key_hint`) when there is no metadata, no
  candidates, and no key.
- `MediaQuery` carries `type: String?` (`"movie"` / `"series"` /
  null). `parseMediaName(download.name).type` is the download's
  parsed type, used as the fallback type for a bare numeric id.

## Change

- The picker gains a manual link row, shown whenever the sheet
  has no metadata and a TMDB key is configured — including when
  Cinemeta already returned candidates. A manual search is explicit
  user intent, so it runs even when the automatic fallback did
  not.
- The user can type (a) a title, (b) a TMDB link
  (`themoviedb.org/movie/<id>` or `/tv/<id>`), or (c) a bare
  numeric id. A bare id uses the download's parsed media type;
  with no parsed type it is invalid and the user is told to paste
  a TMDB link instead.
- A manual pick follows the exact same resolve/persist path as an
  automatic TMDB pick (`pickCandidate` with a `tmdbId` candidate).
- No TMDB key: the manual row is hidden and the existing no-key
  hint shows, exactly as today.
- The automatic behaviour is unchanged: the automatic list still
  loads as today, and a manual search replaces the shown candidate
  list for that download only.

## Seam

- `TmdbResolver.parseManualLink(input, fallbackType)` is pure and
  returns `ManualTmdbLink.ById` / `ByTitle` / `Invalid`.
  `TmdbResolver.searchManualLink(input, fallbackType)` returns the
  sealed `ManualTmdbSearch` (`Found` / `NoMatch` / `Invalid`):
  an id resolves its detail to a single candidate, a title reuses
  `searchCandidates` (same normalized-title gate as the picker).
- The repository exposes `searchManualTmdbLink(input,
  fallbackType)`; the ViewModel's `searchManualTmdbLink(download,
  input)` updates the candidate list and a per-download search
  status (`SEARCHING` / `NO_MATCH` / `INVALID`) in state. The
  sheet renders the field, the button, the searching state, and
  the two messages.
