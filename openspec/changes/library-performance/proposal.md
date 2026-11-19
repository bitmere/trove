# Library performance: fix the real hot paths, not the render path

## What the user asked for

The code has grown large instead of optimized, best-algorithm,
architect-level design. Make the library fast on low-memory devices.

## Verified current state (0.1.23, from code)

Static analysis only — no device heap/frame measurements yet. The render
path is clean (LazyColumn with stable keys, `derivedStateOf` scroll
observers, settle-gated backdrop blur); the real problems are narrow:

1. **The one genuine O(n²): metadata hydration.**
   `DownloadsViewModel.kt:139-162` resolves one row at a time and emits
   one StateFlow update + a full copy of the growing metadata map per
   success — at 3,000 successes ≈ 4.5M prior-entry copies and up to
   3,000 emissions, each recomposing the screen scope. The resolver is
   also fully serial behind one global mutex (1–2 catalog calls + 1
   detail call per miss, one row at a time).
2. **Per-keystroke full re-sort on Main.** `setQuery` writes a StateFlow
   per keystroke with no debounce; `DownloadsScreen.kt:147-148`
   recomputes `visibleDownloads` for **all three tabs** in composition
   (3× filter + 3× sort). `preferScore` rebuilds its normalized
   preference set per row, and `NAME_*` sorts `lowercase()` inside the
   comparator. `AddViewModel` already debounces 250 ms — the pattern
   exists in-repo; Downloads just doesn't use it.
3. **Per-card re-parsing.** `DownloadCover` calls `downloadIdentity()`
   → `parseMediaName()` on every composition, even when metadata is
   present; `addedDate()` builds a `DateTimeFormatter` per call per
   card. Only `parseReleaseDetails` is memoized.
4. **Serial TorBox loads.** `listDownloadsInternal` makes 3 sequential
   `mylist` calls; refresh latency is the sum of 3 round trips. The
   5/sec burst budget allows 3 concurrent calls.
5. **Poster decode serialized.** `PosterLoader` holds one global mutex
   across fetch + decode, so visible posters decode one at a time.
6. **Latent trap: `getDownload(id)` re-fetches up to 3 full 1,000-item
   lists** to resolve a kind before deleting one torrent
   (`TorBoxProvider.kt:169-181`). The kind-aware overload exists and the
   kind-aware callers already use it, but any future caller of the
   interface method pays 3 full lists. (Fixed structurally by the
   `DownloadRef` item in `provider-account-unification`.)

Memory (from the architecture audit): steady state is modest (~3–9 MiB
feature data at 5 files/row + ≤4 MiB poster LRU). The risk is transient
parse peaks and the uncapped `files=true` payload, not steady state.

## Decisions for review

1. **Batch metadata publications** (M, medium): accumulate hydration
   results and emit every ~10 rows or ~200 ms instead of one emission +
   full map copy per row. Guard with the existing `refreshVersion` so a
   stale batch never publishes after refresh/account switch.
2. **Move search/sort off Main** (M, medium): prepare `visibleByTab` in
   the ViewModel on a background dispatcher, partition by kind once,
   reuse one normalized preference set + precomputed sort keys; add a
   150–250 ms results debounce (matching `AddViewModel`'s 250 ms
   precedent) with immediate clear/reset.
3. **Memoize per-card derivation** (S, low): `remember(download,
   metadata)` the identity; hoist `addedDate`'s formatter to a static;
   only parse the name when metadata is absent.
4. **Parallelise the 3 TorBox `mylist` calls** (S, low–medium):
   `async` + `awaitAll`; usenet failure isolation already exists.
5. **Decode posters outside the `PosterLoader` mutex** (S, low): lock
   only for cache-check/dedup, decode after release.
6. **Do not do speculative paging for memory.** Real paging is 1–2
   weeks, high risk, and changes search/sort/selection semantics; only
   after measurement shows libraries regularly hitting the cap. The
   paging work lives in `torbox-paging`.
7. **Housekeeping with any nearby change** (S, low): delete
   `downloadFormat()` or call it from the UI (dead in production, tested
   only); hoist `getSharedPreferences("trove_updates")` out of
   composition in `TroveApp`; give `CinemetaResolver` success entries a
   TTL (e.g. 24 h) instead of `Long.MAX_VALUE`.

## Out of scope

- `files=false` list pages + on-demand file fetch (the real memory
  lever, but it changes the Play path and card file counts — measure
  first, decide separately; see the plan doc).
- Any refactor of the updater stack (954 lines, well-factored, 60+
  tests — leave it alone).
