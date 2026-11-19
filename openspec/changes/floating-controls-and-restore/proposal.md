# Floating controls overlap + cold-start reload (0.1.24, user report)

## What the user asked for

On 0.1.24 (clean install, OnePlus 12), with a screenshot as evidence:
1. "I am not satisfied with Trove — see even if I minimize and load app it
   reloads, and see the UI alignments for plus, search and scroll to top."
2. The go-to-top (^) button visibly overlaps the Search (magnifier) button;
   the +, Search and go-to-top stack looks misaligned and floats over list
   text. Minimizing the app and reopening it reloads the whole list from
   scratch.

## Verified root causes (measured, not assumed)

### Issue 1 — the overlap is two compounding layout defects

Screenshot `~/workspace/user/files/59508_0_7nns.jpg` (388x816 crop of the
right edge), measured with OpenCV Hough circles:

| Control | Center (px) | Diameter (px) | Top | Bottom |
| --- | --- | --- | --- | --- |
| Go to top (^) | (211, 179) | 186 | 86 | 272 |
| Search | (195, 366) | 186 | 273 | 459 |
| Add (+) | (196, 624) | 222 | 513 | 735 |

- **Zero vertical gap, by construction.** Go-to-top bottom padding is
  `page + 48 + compact + 56 = 136dp`; the cluster top is also at 136dp
  (`page 20 + 48 Search + 12 gap + 56 FAB = 136dp`). Measured gap between
  the ^ bottom (272) and the Search top (273) is **1px, i.e. 0dp**.
- **4dp horizontal center offset.** The go-to-top is a 48dp box
  edge-aligned to the right (`padding(end = 20dp)`), while Search is a
  48dp button centered inside the cluster's 56dp-wide column, so their
  centers differ by (56 - 48) / 2 = **4dp**. Measured: 211 - 195 = 16px,
  i.e. ~4.1dp at the screenshot's ~3.9 px/dp scale.
- Two dark translucent circles that touch with offset centers have their
  1dp borders intersect, so the eye reads them as one overlapping blob —
  and the ^ button reads as misaligned against the Search/Add axis, whose
  centers agree to 1px.
- **The FAB is not the culprit.** Material 3 1.4.0 source
  (`FloatingActionButton.kt`, `defaultMinSize` with 56dp baseline tokens,
  caller's `size(56.dp)` on the Surface) realizes at exactly 56dp; the
  pixel ratio FAB/Search is 222/186 = 1.19 vs the expected 56/48 = 1.17.
  The cluster is exactly 116dp tall. There is no hidden size growth from
  shadow, border, density, insets, or `AnimatedVisibility` (fade only,
  no size animation). The static math was the whole story — but it has
  two parts, and the 4dp offset is the non-obvious one.
- **The stack also covers list text** while scrolling (the list's last
  rows pass under the translucent buttons). The 200dp bottom list padding
  barely clears the current 184dp-tall stack, so text sits right against
  it.

### Issue 2 — cold start always reloads from the network

Verified in code (`DownloadsViewModel.kt`, `MainActivity.kt`):

- `DownloadsViewModel.init` calls `refresh()` unconditionally, and
  `refresh()` sets `loading = true` whenever the list is empty, which
  renders the full-screen spinner until page 0 arrives from the network.
- There is **no disk cache of the download list** — only metadata and
  posters are cached. Every process restart starts from an empty list.
- `MainActivity` does **not** refresh on resume (only a clipboard check
  and an account-change refresh), so the reload is purely a cold-start
  problem: OxygenOS aggressively kills background apps, so minimize +
  reopen often means a new process, a new ViewModel, and a full reload.
- Scroll-anchor state IS saved (`rememberSaveable`: query, anchor kind/id/
  index/sort/query/account), but there is no list to anchor to after a
  restart, so it restores against an empty list and drops.

## Decisions for review

1. **One shared column for the whole stack (structural fix, not new
   magic numbers).** `FloatingActions` gains the go-to-top as an optional
   top item in the same `Column` (48dp go-to-top, 48dp Search, 56dp Add,
   all `CenterHorizontally` aligned). One column = one axis, so the 4dp
   offset cannot recur; gaps are declared once in the column instead of
   duplicated as padding arithmetic in two places.
2. **Real gaps, from the token scale.** Within the cluster (Search to
   Add) stays 12dp (`compact`); between the groups (go-to-top to Search)
   is 16dp (`medium`) — related items close, separate groups further
   apart. The go-to-top's enter/exit becomes fade + expand/shrink
   vertically, so the column grows smoothly instead of jumping when the
   button appears.
3. **The stack stops crowding the list.** List bottom content padding
   goes 200dp to 212dp (new stack height 200dp + 12dp breathing room),
   so the last row can scroll fully clear of the buttons. The liquid-glass
   floating cluster design the user asked for is unchanged in look — only
   spacing and alignment change.
4. **Library disk cache + instant cold-start restore.** A
   `LibraryDiskCache` in Android-free core, following the `MetadataDiskCache`
   pattern (single JSON file under `filesDir/library-cache/`, atomic
   temp+rename write, size caps, no secrets — rows carry no credentials):
   loaded rows per tab (max 100 per tab, 300 total) with per-tab
   `endReached`/`nextOffset`, keyed by provider service name. The
   ViewModel restores the snapshot on init (rows visible immediately,
   `loading = false`), then refreshes in the background (pull indicator
   only, rows stay visible). Successful page publishes schedule a debounced background write. Cached rows render with no network; the scroll anchor
   restores against them. Known limitation: the snapshot is keyed by
   service, not by account, so swapping the credential for a different
   account on the same service shows the old rows until the background
   refresh lands (seconds) — same class of staleness the metadata cache
   already accepts.
5. **No new dependencies, no core changes to the list model.** Cache rows
   are `DebridDownload` without `files` (opening a row with empty files
   already triggers `loadFiles()`) and without live fields
   (speed/eta/seeds/peers). Repository seam gains
   `loadLibrarySnapshot`/`saveLibrarySnapshot` with default no-op
   implementations, so existing fakes keep compiling.

## Impeccable review (design lens, applied to the fixed UI)

Read `https://impeccable.style/designing`, the command reference, and the
anti-pattern catalog (62 checks). Applied commands and findings:

- **layout** — one shared axis for the stack (the 4dp offset broke it);
  group gaps larger than within-group gaps (monotonous-spacing rule).
- **harden** — "Text covered by another element": the stack covered list
  text; fixed with the larger bottom padding. Cold start with no network
  now renders the cached library instead of a spinner (missing-data case).
- **animate** — go-to-top enter/exit gains expand/shrink so the column
  grows smoothly; no bounce or elastic easing.
- **polish** — with a real gap, each button's 1dp border + 6dp shadow
  reads separately instead of merging; blur stays only on the Search
  button, where it solves a real layering problem (glassmorphism rule).
- **clarify** — content descriptions unchanged; the three controls keep
  one job each (top / search / add).
- **audit** — 48dp minimum touch targets kept; theme tokens only, no
  hardcoded colors; TalkBack labels unchanged.
- **distill / typeset** — no new text or decoration added to the stack.

## Out of scope

- No redesign of the buttons (same sizes, colors, glass treatment).
- No change to paging, search, sort, or provider behavior.
- No Room/DataStore dependency for the cache.
