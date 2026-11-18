# Scrollable tab row diagnosis

Source/bytecode review only. No build, test, lint, or device command was run.

## What is established

The cached `material3-android:1.4.0` `classes.jar` contains this sequence in
`TabRowKt$ScrollableTabRowImpl$1$1$2$1.measure` (`TabRow.kt` source lines):

- 584: query each tab's `maxIntrinsicHeight(Constraints.Infinity)` (bytecode 140).
- 595: measure the tabs (bytecode 315).
- 600: query each tab's `maxIntrinsicWidth(Constraints.Infinity)` (bytecode 417).
- 604 onward: construct tab positions; subsequently publish them and measure the indicator.

The cached Compose UI `MeasurePassDelegate.onIntrinsicsQueried()` calls
`LayoutNode.requestRemeasure()`. Thus line 600 invalidates tabs *after* they
were measured. This is a concrete invalidation mechanism, even with unchanged
constraints and text. The changing input is the tab's pending-measure state;
no alternating numeric width, height, padding, or scroll offset is established.

AndroidX documents and fixes this ordering in
[commit c61c795](https://android.1.googlesource.com/platform/frameworks/support/+/c61c795a526420d0af596221e5f34a8b62e020ee)
(bug 576397706): query intrinsic widths before measurement, and implement
explicit Tab baseline intrinsics to avoid simulated measure passes.

The supplied decoded samples at 8s/15s identify line 600's intrinsic-width
path; the 12s sample identifies line 584's height path. `TabBaselineLayout`,
Row/Column, and text intrinsic frames identify the expensive descendants.
`remeasureAndRelayoutIfNeeded`/`onLayout` identify traversal work. These samples
are consistent with the defect above, but do **not** prove that it alone
causes the entire 13-second stall. The upstream report describes an extra
measurement pass, not an unbounded loop. The supplied stacks do not capture
`onIntrinsicsQueried()` or a repeated state write, nor contain successive input
values. A snapshot getter is not proof of a write; a stale-font getter is not
proof its result was true. The merged lambda remains unidentified.

## Local mitigation

`app/src/main/java/app/trove/debrid/ui/downloads/CountPill.kt:18` now uses
`wrapContentWidth(unbounded = true)` instead of
`requiredWidth(IntrinsicSize.Min)`. The cached foundation bytecode confirms
that the former measures with an unbounded width, whereas the latter queries
`minIntrinsicWidth` during measurement. An unbroken, non-wrapping count retains
its full content width and centered overflow under narrow constraints. Text,
padding, colors, shape, count formatting, tab selection, pager navigation,
scrolling, and the stock indicator are retained; no dependencies change.

This removes the nested intrinsic query in Trove's CountPill measure path.
It does **not** backport the Material 3 ordering fix. Treat it as a candidate
mitigation pending device verification, not a proven resolution of the stall.
DownloadsScreen and dependency versions are unchanged.

## Coverage and parent verification

Existing `DownloadPresentationTest.tab counts retain all digits including zero`
covers formatting for 0, 75, 1000, and 10000; it does not execute Compose layout.
No test was added or run. There is no configured Compose instrumentation test
dependency in `app/build.gradle.kts`, and no new dependency was introduced.

The parent should verify cold start on the affected device with the same
sampler/draw probe: first draw must fire and traversal must settle. Compare
the tab/pill geometry for those counts, count changes, narrow widths, large
font scale, and RTL. Exercise all three tabs by tap and pager swipe, horizontal
scrolling, and selected/unselected colors and indicator movement.

If the stall remains, capture per-pass tab constraints, intrinsic height,
intrinsic width, measured size, tab positions, and pending-measure transitions.
That distinguishes unchanged-value invalidation from changing geometry or font
resolution. The current samples cannot establish which input never settles.
