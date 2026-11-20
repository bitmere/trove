# Tasks

- [x] Restore legacy poster serving: serve a fresh poster-bearing
  legacy disk entry immediately under its original order and
  provenance when no compatible result is available, and revalidate
  under the current order in the background.
- [x] Never overwrite a disk entry holding a poster URL with a
  posterless result for the same query.
- [x] Close the artwork fallback: when TMDB resolves the title but
  supplies no usable poster, try Cinemeta artwork (by-ID when a
  verified IMDb id is available, otherwise strict title lookup
  without merging provider identities).
- [x] Retain known artwork when a new image fails to download, and
  make an explicit refresh retry posterless rows and rows affected
  by an expired cooldown.
- [x] Separate the displayed poster from metadata revalidation: keep
  the last successfully decoded poster across revalidation and order
  changes, replacing it only when a new image decodes successfully.
- [x] Add regression tests for legacy-entry serving with a
  preconfigured key and blocked network, poster-preserving writes,
  TMDB-posterless artwork fallback, refresh retry of posterless
  rows, cooldown-affected rows, and poster retention across order
  changes.
- [x] Coordinator runs focused tests, full suite, lint, and the
  Impeccable detector on changed UI files; verifies the in-place
  upgrade and key-transition scenarios with warm caches,
  cancellation, and 429s, and reports first-viewport poster count
  and count after hydration settles.
