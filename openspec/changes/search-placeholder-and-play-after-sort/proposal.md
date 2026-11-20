# Search placeholder wrap + Play dead after sort (0.1.26, user report)

## What the user asked for

On 0.1.26 (OnePlus 12), with a screenshot as evidence:
1. The search field's placeholder "Search downloads" wraps across three
   lines ("Search" / "downloa" / "ds") once the user changes the sort.
2. After sorting, the Play button on a download card does nothing when
   tapped. The user confirmed the Play button is the element that stops
   responding.

## Verified root causes (measured in code, not assumed)

### Issue 1 — the placeholder can wrap because it has no line limit

The search row (`DownloadsScreen.kt:304-340`) is a `Row`: the `SearchBar`
on `Modifier.weight(1f)`, the Reset `TextButton`, and the sort `IconButton`.
Reset appears exactly when the search is dirty
(`state.query.isNotBlank() || state.sort != DownloadSort.NEWEST`), so the
moment the user changes the sort, Reset pops in and steals ~107dp from the
SearchBar. Measured from the user's screenshot: the SearchBar shrinks to
~167dp wide while "Search downloads" needs ~180-190dp to stay on one line.

The placeholder is a bare `Text(...)` with no `maxLines`/`softWrap`/`overflow`
(`DownloadsScreen.kt:316`). Material 3 1.4.0 keeps typed text single-line
(`SearchBarDefaults.InputField` passes `TextFieldLineLimits.SingleLine` to
the inner `BasicTextField`), but the placeholder is laid out by the
decorator as a plain wrapping `Text`, and the `SearchBar` has no fixed
height (only the 56dp `InputFieldHeight` minimum), so the container grows
to fit the wrapped placeholder — ~102dp tall in the screenshot.

Fix: one line — `maxLines = 1, overflow = TextOverflow.Ellipsis` on the
placeholder `Text`. The placeholder then truncates with "…" and the bar
stays 56dp. No width reclaim (shortening "Reset search", moving Reset into
the sort menu) is needed to fix the breakage.

### Issue 2 — card taps are dropped after a sort (referential identity)

`isCurrentDownload` (`DownloadsViewModel.kt:790-793`) guards every card
interaction — `onPlay`/`onOpen`/`onLongPress` (`DownloadsScreen.kt:424-426`)
and the details-sheet callbacks (`DownloadsScreen.kt:486-489, 493`) — with
referential identity:

```kotlin
current.filePicker === download || current.downloads.any { it === download }
```

`DebridDownload` is a `data class`, and the sort path (`setSort`,
`DownloadsViewModel.kt:341-360`) is the only normal path that wholesale
replaces row instances: `launchPage(..., replace = true)` publishes a
brand-new list of freshly deserialized instances. A tap whose lambda still
captures a pre-sort instance fails the guard and is silently dropped, with
zero feedback. The post-sort recomposition is heavy (100 fresh rows +
metadata hydration + poster loads), so on the phone the window is hundreds
of milliseconds — exactly when the user taps Play right after picking a
sort.

Fix: compare structurally, not referentially — `==` on the `DebridDownload`
data class. That single fix covers the card and the details sheet, since
every interaction goes through it. `play()` and `openDownload()` carry no
identity guard of their own (only `resolvingDownload`/`playUrl` guards),
so no change is needed there.

(Correction found by the existing test suite: comparing by `(kind, id)`
alone was tried first and broke `account changes cannot play or delete an
old row in the replacement account` — a same-`(kind, id)` row from a
replacement account must NOT count as current. Structural equality keeps
that guard: sort-republished copies have identical fields, while the
replacement account's row differs. A row whose fields genuinely changed
between fetches — e.g. a downloading torrent's progress ticking — can
still drop one stale tap; the next tap uses the fresh instance.)

Secondary hardening on the same Play path: `playEnabled` is
`state.resolvingDownload == null && state.playUrl == null`
(`DownloadsScreen.kt:423`) feeding `Button(enabled = playEnabled)`
(`DownloadCard.kt:56`). In `loadFiles` and `playFile`, a
`CancellationException` rethrows *before* the clearing update
(`loadFiles`: the `catch (e: CancellationException) { throw e }` branch,
and `ensureActive()` in the generic catch; `playFile`: `ensureActive()`
and the cancellation rethrow after the inner try), so a cancelled lookup
can leave `resolvingDownload` stuck and the Play button disabled with no
way back. Fix: clear `resolvingDownload` (and `resolvingFileId` in
`playFile`) in a `finally`, only when it still matches this call's key —
so a lookup that handed off to playback (auto-play) is not cleared by the
lookup's own `finally`.

## Out of scope (verified, no fix)

- `SelectionState` keys by plain `id` but is referenced nowhere outside its
  own file and its test; batch delete is not wired into the screen, so it
  cannot affect this bug. Do not touch it (the `(kind, id)` keying note
  stands for when batch delete *is* wired).
- The sort menu (`DownloadSortMenu`) has no state gating; no fix needed.
- The search field focus path is untouched by the sort flow; no fix needed.
- The Play button looked enabled (bright mint, not faded) in the user's
  screenshot, so the stuck-`resolvingDownload` path is hardening only —
  the identity guard is the confirmed mechanism for the reported symptom.

## Needs the user's phone (cannot be verified from this VM)

- The search bar stays 56dp with Reset visible, and the placeholder shows
  on one line with an ellipsis.
- Tapping Play immediately after changing the sort starts playback.
- The Play button never sticks disabled after a cancelled file lookup.
