# tmdb-manual-link

## Input

- The manual link row accepts a TMDB title, a themoviedb.org
  link (`/movie/<id>` or `/tv/<id>`, http or https, with or
  without `www.`, trailing path/query/fragment ignored), or a bare
  numeric TMDB id.
- A bare numeric id resolves with the download's parsed media type
  (`"movie"` / `"series"`). With no parsed type it is invalid.
- Blank input and input longer than 160 characters are invalid and
  make no network call.

## Search

- A manual search is explicit user intent: it runs even when
  Cinemeta already returned candidates, unlike the automatic TMDB
  fallback, which runs only when the Cinemeta list is empty.
- An id search resolves the TMDB detail and shows it as a single
  candidate. A title search reuses the picker's normalized-title
  gate and cap (5), in TMDB's returned order.
- With no TMDB key configured the manual row is not shown at all;
  the existing no-key hint covers that state.

## Pick

- A manual pick follows the exact same resolve/persist path as an
  automatic TMDB pick: `pickCandidate` with a `tmdbId` candidate,
  stored per download as a `MetadataOverride`, resolved via
  TMDB-primary + Cinemeta backfill, never stored in the shared
  disk cache.

## Feedback

- While a manual search runs, the row shows a searching state and
  the button is disabled.
- No match shows a no-match message; invalid input shows an
  invalid-input message. A found search replaces the shown
  candidate list for that download only and clears the message.
