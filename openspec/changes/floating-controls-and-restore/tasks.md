# Tasks — floating controls overlap + cold-start reload

Sizes: S ≤ 1 day, M 2–4 days. Implementation and test authoring are
tracked below. Execution and visual verification remain with the
coordinator.

## Phase 1 — floating stack: one column, real gaps (S, low risk)

- [x] Move the go-to-top button into `FloatingActions` (`ui/Glass.kt`)
      as an optional top item (`goToTopVisible: Boolean`,
      `onGoToTop: () -> Unit`), in the same `Column` as Search and Add,
      all `CenterHorizontally` aligned. Remove the separate
      `AnimatedVisibility` placement from `DownloadsScreen.kt`.
- [x] Gaps: 16dp (`TroveSpace.medium`) between go-to-top and Search,
      12dp (`TroveSpace.compact`) between Search and Add. Column is
      56dp wide, bottom-end aligned with 20dp (`TroveSpace.page`)
      padding, as today.
- [x] Go-to-top enter/exit: `fadeIn() + expandVertically()` /
      `fadeOut() + shrinkVertically()` so the column grows smoothly.
      Keep the 48dp circle, border, shadow, translucent fill, and
      content description unchanged.
- [x] List bottom content padding: 200dp → 212dp (stack height 200dp +
      12dp breathing room) so the last row scrolls fully clear.
- [x] Add a regression test that pins the stack geometry: the three
      controls share one center x, and the vertical gaps are 16dp and
      12dp (measure with a Compose layout test or `onGloballyPositioned`
      in a test harness — do not assert from code reading alone).
- [ ] Render/screenshot the Downloads screen at phone density and
      confirm the three buttons are distinct with visible gaps.
      NOTE (2026-11-19, coordinator): Robolectric `captureToImage()`
      times out in this VM in both NATIVE and LEGACY graphics modes,
      even after advancing the test clock 1000ms, so no PNG was
      produced. The geometry assertions in `FloatingStackLayoutTest`
      pass (shared center-x within 1px, gaps 16dp/12dp within 1dp,
      measured from the real layout pass at 400dp width) — that is the
      verification so far. A true visual still needs the user's phone.

## Phase 2 — library disk cache + cold-start restore (M, medium risk)

- [x] Add `LibrarySnapshot` (account service name, savedAt, per-tab
      loaded rows capped at 100 per tab / 300 total, per-tab
      `endReached`/`nextOffset`) and `LibraryDiskCache` in Android-free
      core, following the `MetadataDiskCache` pattern: single JSON
      file under `filesDir/library-cache/`, atomic temp+rename write,
      schema version, corrupt file deleted on read. No secrets in the
      file; rows stored without `files` and without live fields
      (speed/eta/seeds/peers).
- [x] Add `loadLibrarySnapshot(): LibrarySnapshot?` and
      `saveLibrarySnapshot(snapshot: LibrarySnapshot)` to the
      `TroveRepository` seam with default no-op implementations, and
      implement them in `DebridRepository` via `LibraryDiskCache`
      (constructed like `MetadataDiskCache`).
- [x] `DownloadsViewModel.init`: load the snapshot on IO before the
      first `refresh()`; when the snapshot's service matches the
      current connection and rows are non-empty, publish them into
      `pagedByTab` (loaded rows, nextOffset, endReached, visited) and
      `downloads` with `loading = false`, then refresh in the
      background (refreshing indicator only — rows stay visible). A
      missing/mismatched/corrupt snapshot behaves exactly as today
      (full-screen spinner + network load).
- [x] Save the snapshot after successful page publishes (union of loaded rows,
      capped), debounced and fire-and-forget on IO. Never block the
      list render on a disk write.
- [x] Scroll-anchor restore works against the restored rows (existing
      `pendingAnchor` mechanism after provider identity is available.
      Cover cold-start anchor resolution with a cached list).
- [x] Add focused tests first: snapshot encode/decode round-trip
      (rows without files, caps enforced, corrupt file → null, wrong
      service → ignored); ViewModel cold start with a snapshot shows
      rows with `loading = false` and refreshes in the background;
      cold start without a snapshot behaves as today; snapshot saved
      after a page lands.
- [x] Run focused tests, then full JVM suite, then lint, then
      `assembleDebug`. Verify the layout visually (Phase 1 render).
      DONE 2026-11-19: focused tests green (LibraryDiskCacheTest 3/3,
      DownloadsLibraryRestoreTest 6/6, FloatingStackLayoutTest 1/1,
      ProvidersVisualListTest 2/2); full gate green (see Gates).
      Visual render: geometry assertions pass; PNG capture unavailable
      in this VM (see the render/screenshot note above).

## Gates (both phases)

- [x] Full `./gradlew test lint assembleDebug` BUILD SUCCESSFUL,
      0 test failures, lint 0 errors.
      DONE 2026-11-19: BUILD SUCCESSFUL in 6m 43s; 582 tests
      (321 app debug + 261 core), 0 failures, 0 errors, 0 skipped;
      lint 0 errors, 25 warnings (baseline 24; +1 is the
      NewerVersionAvailable notice for the pinned Robolectric 4.14.1),
      1 hint; debug APK built (app.trove.debrid.debug 24/0.1.24-dev).
      Senior-rewrite guard passed; release-gate unit tests 52 OK.
- [ ] Visual check of the Downloads screen at phone density: three
      distinct floating controls with visible gaps, list clears the
      stack at the bottom. (Geometry assertions pass; PNG capture
      unavailable in this VM — needs the user's phone.)
- [x] No functionality lost: go-to-top, Search, Add, pull-to-refresh,
      paging, anchor restore, provider switching, sign-out all behave
      as before (covered by existing tests + the new ones above).
      Full suite green; on-device confirmation still pending.
