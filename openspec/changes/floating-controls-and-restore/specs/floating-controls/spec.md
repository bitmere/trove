# Floating controls

## One column, one axis

The floating stack is a single `Column` in `FloatingActions`, 56dp wide,
bottom-end aligned with 20dp (`TroveSpace.page`) padding:

- Go-to-top (48dp circle) — optional, top item.
- Search (48dp circle) — 16dp (`TroveSpace.medium`) below go-to-top.
- Add (56dp FAB) — 12dp (`TroveSpace.compact`) below Search.

All three items are `CenterHorizontally` aligned, so their centers share
one vertical axis. The gap between go-to-top and Search is larger than
the gap inside the cluster because they are separate groups; the
Search/Add gap is the within-cluster gap. No padding arithmetic in the
caller duplicates the cluster's height.

## Go-to-top behavior

- Visible only once the visible tab is scrolled down
  (`firstVisibleItemIndex >= 10`), as today.
- Enter: `fadeIn() + expandVertically()`; exit: `fadeOut() +
  shrinkVertically()`. The column grows and shrinks smoothly; Add and
  Search do not move when go-to-top appears or disappears.
- Tap scrolls the visible tab to item 0, as today. No state change.
- Look unchanged: 48dp circle, 1dp `outlineVariant` border, 6dp
  floating shadow, `surfaceContainerHigh` at 0.92 alpha, `onSurface`
  `KeyboardArrowUp` icon, `go_to_top` content description.

## Search and Add

- Unchanged in look and behavior. Search keeps the liquid-glass
  treatment (backdrop blur where supported, translucent fallback); Add
  keeps the 56dp primary FAB. The blur stays only on Search, where it
  solves the layering of a floating control over list content.

## List clearance

- The list's bottom content padding is 212dp: the stack is 200dp tall
  (20 bottom + 56 Add + 12 gap + 48 Search + 16 gap + 48 go-to-top)
  plus 12dp of breathing room. The last row scrolls fully clear of the
  stack, so no readable text sits under the buttons.
