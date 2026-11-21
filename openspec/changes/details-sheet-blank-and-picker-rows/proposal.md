# Details-sheet blank and picker rows

## What was found

The user confirmed on-device that the details sheet becomes blank only while
the keyboard is open and returns when it closes. Raw safeDrawing padding
reapplies keyboard space already consumed by Material. Candidate rows also
lack artwork and synopses, making same-title results indistinguishable.

## Fix (this change)

Use consumption-aware list inset padding, preserving inset selection, zero
contentWindowInsets and the drag handle. Carry optional poster and overview
from Cinemeta/TMDB search and manual ID lookup into richer, fully tappable rows.
Keep candidate selection and its ID-based resolution unchanged.

Not built: Bug 6 (unkeyed lazy items), auto-scroll to FOUND results, the
partial-title hint line, or a diagnostic build. No release or installed artifact.
