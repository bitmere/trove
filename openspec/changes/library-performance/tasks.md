# Tasks — library performance

Sizes: S ≤ 1 day, M 2–4 days, L 1–2 weeks. All tasks are unchecked:
nothing here is approved until the user approves item by item.

## 1 — Batch metadata publications (M, medium risk) — DONE Batch 2, gate green 2026-11-19 (702 tests, lint 0 errors)

- [x] Accumulate hydration results in `DownloadsViewModel` and emit
  every ~10 rows or ~200 ms instead of one StateFlow emission + full
  map copy per row. Guard with the existing `refreshVersion` so stale
  batches never publish after refresh/account switch.
- [ ] Optionally bound resolver concurrency (2–3 in flight) once
  batching lands — do not raise it carelessly (extra Cinemeta traffic).
  NOT done: resolution stays sequential by design.
- [x] Add focused tests first: batching emits fewer updates, stale batch
  after refresh is dropped, poster arrival order preserved.
- [x] Run focused tests, then full JVM suite, then lint. Phone-check:
  hydration of a large library, no UI jank, no stale metadata after a
  quick refresh.

## 2 — Search/sort off Main + hoist prefer-words (M, medium risk) — DONE Batch 2

- [x] Prepare `visibleByTab` in the ViewModel on a background
  dispatcher: partition by kind once, reuse one normalized preference
  set + precomputed sort keys (no `lowercase()` inside comparators, no
  per-row set rebuilds).
- [x] Add a 150–250 ms results debounce (matching `AddViewModel`'s
  250 ms precedent) with immediate clear/reset.
- [x] Add focused tests first: stable ties, missing-date/size ordering,
  counts, offscreen-tab readiness, clear/reset timing.
- [x] Run focused tests, then full JVM suite, then lint. Phone-check:
  typing in search on a large library, no dropped frames.

## 3 — Memoize per-card derivation (S, low risk) — DONE Batch 1

- [x] `remember(download, metadata)` the identity in `DownloadCover`;
  hoist `addedDate`'s `DateTimeFormatter` to a static; only parse the
  name when metadata is absent.
- [x] Add focused tests first (memo keys include the name so a rename
  is not stale).
- [x] Run focused tests, then full JVM suite, then lint.

## 4 — Parallelise the 3 TorBox `mylist` calls (S, low–medium risk) — DONE Batch 1

- [x] `async` + `awaitAll` for torrent/web/usenet in
  `listDownloadsInternal`; keep the existing usenet failure isolation.
  (Implemented with three `async`s awaited in deterministic order —
  torrent error wins — rather than `awaitAll`, which would pick an
  arbitrary first failure.)
- [x] Add focused tests first: error precedence when kinds fail
  differently, rate-budget interaction (3 concurrent inside the 5/s
  burst).
- [x] Run focused tests, then full JVM suite, then lint. Phone-check
  refresh latency on a slow network.

## 5 — Decode posters outside the `PosterLoader` mutex (S, low risk) — DONE Batch 1

- [x] Lock only for cache-check/dedup; decode after release. Guard
  duplicate in-flight decodes of the same URL with an in-flight map if
  it matters.
- [x] Add focused tests first.
- [x] Run focused tests, then full JVM suite, then lint.

## 6 — Housekeeping with any nearby change (S, low risk) — DONE Batch 1 + Batch 2

- [x] Delete `downloadFormat()` or call it from the UI (dead in
  production, tested only).
- [x] Hoist `getSharedPreferences("trove_updates")` out of composition
  in `TroveApp` (`MainActivity.kt:227-230`).
- [x] Give `CinemetaResolver` success entries a TTL (e.g. 24 h) instead
  of `Long.MAX_VALUE`. (Done in the Phase 4 worker: success 24 h,
  no-match/ambiguous 1 h, transient failures 60 s.)
- [x] Run focused tests, then full JVM suite, then lint.
