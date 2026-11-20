# Tasks

- [x] Reconcile hydration against loaded rows: add a reconcile step
  that enqueues loaded rows holding no poster, and call it when a
  snapshot is shown, when a page load fails, and when a tab becomes
  active. Keep the existing enqueue guards, viewport priority, and
  batch size of 4.
- [x] Define retry eligibility for posterless / no-match rows and
  re-present eligible rows on every reconcile; the resolver's miss
  and failure TTLs decide whether a re-presentation performs network
  work. No background timer.
- [x] Carry the explicit-refresh bypass to every page load under that
  refresh's version (page-0 replace, later appends, other tabs'
  first page loads), not just page 0 of the active tab. Keep the
  poster-bearing skip and cooldown-window guard.
- [x] Make a mounted row observe a successful shared image load for
  its current URL: publish a load-generation counter from the poster
  loader, bumped on every successful bitmap store, and re-attempt
  the row's load when the generation changes and its slot holds no
  bitmap for the URL. Read the shared in-memory bitmap cache first
  so the fast path performs no network and does not disturb
  prefetch. Report an image failure at most once per (URL, retry)
  failure episode.
- [x] Close the TMDB-primary IMDb-id artwork gap behind a failing
  fixture first: when TMDB supplies an id but no poster and the
  by-ID backfill supplies no poster either, fall through to the
  exact-year Cinemeta artwork title search. Keep the strict identity
  checks in `lookup()` and keep the TMDB-resolved identity (title,
  year, IMDb id) when the artwork is accepted.
- [x] Add focused regressions: snapshot restore + page-0 failure;
  cancel-before-offscreen-flush; sheet open with absent vs posterless
  metadata, asserting what the row shows after the sheet closes;
  pull-to-refresh bypass beyond page 0 and across tabs; both
  resolver orders for the artwork gap; failure-then-same-URL-success
  with no remount.
- [x] Coordinator runs focused tests, full suite, lint, and the
  Impeccable detector on changed UI files; reports the test count
  and baseline delta, and verifies the fix against the reported
  scenario (a row never-enriched by the list gains its poster once
  the sheet opens it, and stays gained after the sheet closes).
  Gates 2026-11-21: 636 tests, 0 failures (baseline 630, +6 net);
  lint 0 errors; detector 0 findings on changed UI files.
