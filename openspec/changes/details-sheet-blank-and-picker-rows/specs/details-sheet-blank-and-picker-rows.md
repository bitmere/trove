# Details-sheet blank and picker rows

## Keyboard insets

The list respects ancestor consumption of horizontal and bottom safeDrawing
insets so the keyboard is not subtracted twice. The content inset override
and drag handle remain unchanged. Title and manual input remain reachable
while typing; on-device keyboard verification is required after JVM checks.

## Candidate information

MediaCandidate has nullable posterUrl and overview with null defaults.
Cinemeta uses publicPosterUrl for catalog poster and description for overview.
TMDB fetches cached image configuration once per search, maps poster_path
through posterUrl, and retains overview. Config failure keeps titles with null
posters. Manual ID lookup carries artwork and overview from resolved metadata.
Selection continues to resolve by ID; preview information does not change picks.

Each full-width button shows a 56dp-wide poster or placeholder, one-line
ellipsised title, year/type/source facts, and an optional two-line ellipsised
synopsis in onSurfaceVariant. Missing synopsis takes no extra line. Buttons
retain disabled state and at least a 48dp touch target. Existing loader and
design tokens are reused without dependencies.

## Exclusions

Bug 6 (unkeyed lazy items), auto-scroll to FOUND, partial-title hint and
diagnostic build are not implemented.
