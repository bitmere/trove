# tmdb-manual-partial-titles

## Gate

- Normalize the query, localized title, and original title using
  the existing `normalizedTitle` function.
- Accept a candidate if either normalized title equals the query.
- Additionally accept a candidate if `allowPrefix` is true, the
  normalized query has at least three characters, and either
  normalized title starts with that query.
- Queries shorter than three normalized characters remain exact-only.

## Parameter threading

- `searchCandidates(query, allowPrefix: Boolean = false)` passes
  the flag to `searchMatches(query, key, allowPrefix: Boolean = false)`.
- Only `searchManualLink`'s `ByTitle` branch passes
  `allowPrefix = true`.
- `searchHit` (used by primary, full, and poster resolution) and
  `CinemetaResolver.candidates(query)` retain the default exact gate.
- Keep the cap of five, TMDB result order, year handling, manual
  link/id paths, pick path, and "No TMDB match" message unchanged.
  No UI changes are required.

## Tests

All cases use existing fake transports, with no live network.

- Manual "pushpa" returns "Pushpa: The Rise" (2021, id 1) then
  "Pushpa 2: The Rule" (2024, id 2), with configured w185 poster
  URLs for /rise.jpg and /rule.jpg.
- Manual "pushpa" accepts id 3 through original title "Pushpa:
  The Rise" when its localized title does not match.
- Manual "pu" returns NoMatch for the same two Pushpa results.
- Default `searchCandidates(MediaQuery("pushpa", null, "movie"))`
  returns an empty list for those results.
- With an empty Cinemeta catalog and the existing `tmdbTransport`
  fake returning "Example Film", automatic
  `candidates(MediaQuery("Example", null, "movie"))` reaches the
  TMDB fallback and returns an empty list.
