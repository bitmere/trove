# Manual TMDB partial title search

## What the user asked for

Allow a manual TMDB title search such as "pushpa" to list "Pushpa:
The Rise" and "Pushpa 2: The Rule". Keep automatic matching exact,
and leave the UI, link/id searches, and pick path unchanged.

## Verified current state

All line numbers are main @ `2d1089a6` (Trove 0.1.38).

- `core/src/main/java/app/trove/debrid/core/TmdbResolver.kt:293`
  rejects search results unless the normalized title or original title
  exactly equals the normalized query.
- `core/src/main/java/app/trove/debrid/core/MediaMetadata.kt:189`
  normalizes titles by stripping diacritics, lowercasing, and removing
  non-alphanumeric characters. "Pushpa 2: The Rule" becomes
  "pushpa2therule", which does not equal "pushpa".
- `core/src/main/java/app/trove/debrid/core/TmdbResolver.kt:143`
  routes manual title searches through `searchCandidates`, sharing
  the exact gate. That method caps results at five in TMDB order
  and builds poster URLs from configuration (lines 67–76).
- `core/src/main/java/app/trove/debrid/core/TmdbResolver.kt:309`
  uses `searchMatches` for automatic resolution.
  `core/src/main/java/app/trove/debrid/core/MediaMetadata.kt:462`
  uses `searchCandidates(query)` for the automatic picker fallback.

## Change

Manual title searches accept an exact normalized title/original-title
match or a prefix match when the normalized query has at least three
characters. Short queries remain exact-only. Preserve the five-result
cap, TMDB order, year handling, link/id paths, pick path, and the
"No TMDB match" message.

## Seam

Add `allowPrefix: Boolean = false` to `searchCandidates` and
`searchMatches`, forwarding it from the former to the latter.
Only `searchManualLink`'s `ByTitle` branch passes `true`.
`searchHit` and `CinemetaResolver.candidates` keep their default
exact gate. Update the public search doc comments accordingly.
