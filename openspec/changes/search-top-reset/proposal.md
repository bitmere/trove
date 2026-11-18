# Search: go to top and reset search

## What the user asked for

Search in Trove has a clear (X) option, but no way to jump back to the top
of a long result list and no way to reset the search. Add both.

## Verified current state (0.1.18, from code)

- Search field: `DownloadsScreen.kt` — a Material 3 `SearchBar` at the top
  of the Downloads screen. Its trailing icon is `Icons.Filled.Clear`
  (`R.string.clear_search`), shown only while the query is non-empty, and
  it calls `viewModel.setQuery("")`. The empty state
  (`LibraryEmptyState.kt`) also offers a "Clear search" button when a query
  matches nothing. Clearing only clears the query text.
- Search state lives in `DownloadsUiState` (`DownloadsViewModel.kt`):
  - `query: String` — in-memory only, default `""`. Set by `setQuery`.
  - `sort: DownloadSort` — persisted through
    `repository.saveDownloadSort` / `loadDownloadSort`, default `NEWEST`.
    `setSort` writes through a mutex-guarded coroutine, so a reset that
    calls `setSort(NEWEST)` persists the same way.
  - `tab: DownloadKind` — in-memory, driven by the pager. This is
    navigation, not search state.
  - There are no other filters. Filtering is per tab, then name contains
    (case-insensitive), then the selected sort
    (`visibleDownloads` in `DownloadsViewModel.kt`).
- List scroll state: one `rememberLazyListState()` per tab
  (`torrentListState`, `webListState`, `usenetListState`), held above the
  `HorizontalPager` so a tab keeps its position. `listState` is the
  currently visible tab's state. A repo-wide grep for
  `scrollToItem` / `scrollToTop` / `resetSearch` finds nothing — no
  programmatic scrolling and no reset exist anywhere in the app.
- Floating cluster (`Glass.kt`, `FloatingActions`): bottom-right
  liquid-glass Search button (48 dp, focuses the search field) above the
  Add FAB (56 dp). No scroll-to-top control. The cluster itself is not
  changed by this proposal.

## Decisions for review

1. **"Reset search" means: query back to empty AND sort back to Newest
   (the default), persisted.** Clearing the query alone already exists
   (the X icon); the missing part is that a changed sort survives a
   clear, so results stay in a non-default order. Reset also scrolls the
   current tab's list to the top, because the result set is re-filtered
   and re-sorted — the top is the expected landing. The tab is NOT
   changed: switching tabs is navigation, not search state.
2. **"Go to top" scrolls only; it changes no state.** It acts on the
   currently visible tab's list only.
3. **Placement (chosen by the user, 2026-11-18):**
   - Reset: a "Reset" text button in the search row, between the search
     field and the sort icon, visible only while search is dirty
     (`query.isNotBlank() || sort != NEWEST`). It sits next to the two
     things it resets, and it is visible exactly when there is something
     to reset. Cost: the sort icon shifts horizontally when the button
     appears/disappears.
   - The sort-menu alternative was considered and not chosen.
   - Go to top: a small circular button (48 dp, same tonal treatment as
     the Search button in the cluster) that fades in above the floating
     action cluster when the visible list is scrolled down
     (`firstVisibleItemIndex >= 10`), and fades out at the top. Icon
     `Icons.Filled.KeyboardArrowUp`, label "Go to top". (`VerticalAlignTop`
     was the first choice, but it lives in the extended icons artifact
     and the app depends only on `material-icons-core`, so the core
     chevron-up icon is used instead — no new dependency.) Placed in
     `DownloadsScreen`, above the cluster — `FloatingActions` itself is
     untouched.
   - Alternative for reset, if the user prefers zero layout change: a
     "Reset search" item at the bottom of the sort dropdown menu. Same
     behaviour, less discoverable. The user picks one; the code change is
     the same `resetSearch()` underneath. **Not chosen** — the user picked
     the search-row button on 2026-11-18.
4. **No new dependency, no new state holder.** One `derivedStateOf` for
   the go-to-top visibility, one conditional button, and
   `LazyListState.animateScrollToItem(0)` on the existing per-tab list
   states. No new blur, no new library, no footprint change worth
   measuring on low-memory phones.

## Files to change

- `app/src/main/java/app/trove/debrid/ui/downloads/DownloadsViewModel.kt`
  — add `resetSearch()`: `setQuery("")` + `setSort(DownloadSort.NEWEST)`
  (reuses the existing persisting sort path). Also carries the two
  deferred wirings folded into this change: `preferWords` and
  `showClutterFiles` in `DownloadsUiState`, loaded from the repository
  in `init` and on every `refresh()`, with `visibleDownloads` passing
  the prefer words into `sortDownloads`.
- `app/src/main/java/app/trove/debrid/ui/downloads/DownloadsScreen.kt`
  — Reset button in the search row (dirty-only); go-to-top button above
  the floating cluster (scrolled-down-only); `animateScrollToItem(0)`
  calls via the existing `scope`; `visibleByTab` recomputes when
  `preferWords` changes; the details sheet receives
  `showClutterFiles` from state.
- `app/src/main/res/values/strings.xml` — two new strings:
  `reset_search` ("Reset search"), `go_to_top` ("Go to top"). The
  existing `clear_search` is reused unchanged for the X icon.
- Tests: extend the ViewModel tests (`DownloadsViewModelTest.kt` /
  `DownloadsOrderingRegressionTest.kt` pattern — the fake repository
  already implements `saveDownloadSort` / `loadDownloadSort`):
  reset clears the query, restores `NEWEST`, and persists it; reset is a
  no-op when already clean.
- `openspec/changes/search-top-reset/` — this proposal, tasks, specs.

## Non-goals

- No change to filtering semantics (name contains, case-insensitive),
  sort options, tab behaviour, or the clear (X) icon.
- No change to `FloatingActions` (Add/Search cluster), the sort menu, or
  the empty state.
- No new dependency and no new persistence key.

## Validation

- Focused new ViewModel tests first, then the full JVM suite, then lint
  (house rule: focused tests first, lint once before a PR).
- Phone check (user / on-device): scroll deep in a long list and tap go
  to top on each tab; change sort, type a query, tap Reset and confirm
  query cleared, sort back to Newest, list at top; kill and relaunch and
  confirm sort stayed Newest; confirm the clear (X) still works on its
  own and the Add/Search cluster is unchanged. A passing JVM suite is not
  proof of on-device behaviour.
