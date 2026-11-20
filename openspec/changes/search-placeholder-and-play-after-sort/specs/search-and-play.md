# Search placeholder and Play-after-sort

## Search placeholder

- The search field's placeholder text SHALL stay on one line whenever the
  search row is visible, including when the Reset button is shown (dirty
  search: non-blank query or a non-default sort).
- When the field is too narrow for the full placeholder, the placeholder
  SHALL truncate with an ellipsis instead of wrapping.
- The search bar SHALL stay at its 56dp height with Reset visible; it
  SHALL NOT grow to fit wrapped placeholder text.

## Play after sort

- Tapping Play on a download card SHALL start playback even when the tap
  was captured before a sort republished the library list — i.e. the
  "current download" check SHALL compare downloads structurally (`==` on
  the `DebridDownload` data class), not by object reference. A row from a
  replacement account SHALL NOT count as current even when its `(kind, id)`
  collides with the old row's, because the records differ.
- The same key-based check SHALL gate the details-sheet Play, Play-with,
  candidate-pick, override-clear and delete callbacks, since all of them
  go through the same guard.
- A cancelled file lookup or cancelled playback SHALL leave
  `resolvingDownload` cleared, so the Play button SHALL NOT stick
  disabled after a cancellation. A lookup that handed off to playback
  (auto-play) SHALL NOT have its resolving state cleared by the lookup's
  own completion.
