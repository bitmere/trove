# Impeccable UI fixes

## Download row

- The download row SHALL be a flat Paper surface with a hairline
  divider, not a tonal card with elevation.
- The row SHALL show a 48x72 dp poster with 8 dp corners.
- The poster placeholder SHALL be a neutral media placeholder, not
  the Play glyph.
- The row SHALL show ONE title only: the clean title when enriched,
  the parsed title otherwise, in Space Grotesk, max 2 lines with an
  ellipsis.
- The row SHALL show one Inter line of `year · type · SxxExx ·
  quality` (only the parts that exist).
- The row SHALL show one compact status + size line.
- The row SHALL NOT show the download kind, the raw release name,
  the added date, or per-file facts; those SHALL live in the details
  sheet.
- Transfer progress and error text SHALL stay on the row, in Jade.
- When the download is READY, Play SHALL stay as a compact 48 dp
  Jade-tinted trailing icon button.
- Tapping Play SHALL still start playback, and the row tap SHALL
  still open the details sheet; the long-press delete SHALL still
  work.

## Go-to-top

- The go-to-top control SHALL appear whenever the visible tab's list
  is scrolled away from the top (`firstVisibleItemIndex > 0 ||
  firstVisibleItemScrollOffset > 0`), not only past 10 rows.
- It SHALL NOT appear for an empty list.
- Tapping it SHALL still scroll the visible tab to the top.

## Poster loading

- A poster whose load failed SHALL be retried when its row re-enters
  composition or its metadata refreshes.
- A URL rejected by validation SHALL show the neutral placeholder
  with no retry loop.
- A network failure SHALL keep the 60 s miss cache and SHALL retry
  only a bounded number of times.
- A successfully loaded poster SHALL still come from the existing
  memory/disk caches; the 4 MB memory cap and 128-entry miss cap
  SHALL NOT grow.

## Details sheet

- Episode and file rows SHALL be flat rows with hairline dividers,
  not Surface cards.
- The matched episode SHALL be marked with a Jade indicator/label,
  readable without colour alone.
- File rows SHALL keep the 48 dp Play button and the overflow menu.
- The cast line SHALL be capped at 2 lines.
- The Close button SHALL stay.
- The raw release name, added date, and file facts SHALL be visible
  in the details sheet.

## Provider section

- The provider section SHALL have one heading — the provider name at
  headlineSmall — plus a status line, with no tinted banner.
- Key entry, Test & Save, sign-out, feedback, and help lines SHALL
  keep working unchanged.

## Empty state

- The empty-state content SHALL be centred in the viewport.
- On a short screen it SHALL still scroll instead of clipping.

## Add sheet

- The Add button's spinner slot SHALL render only while adding, so
  the label is centred when idle and the button keeps its size while
  adding.

## Feedback

- Feedback SHALL carry a leading icon: error-outline for errors, a
  check icon otherwise, so its state reads without colour.

## Downloads header

- The Downloads title SHALL be titleMedium (or headlineSmall) and
  the "Your cloud library" subtitle SHALL NOT render.
- The account chip and the avatar settings entry SHALL stay.

## List header

- The count pill SHALL include the "+" inside the pill ("128+")
  when more pages remain to load; there SHALL be no separate "+"
  text beside the pill.
- The Reset button SHALL read "Reset" when only the sort changed
  (or just "Reset" always).

## Loading spinners

- The two full-screen spinners on the Downloads screen SHALL carry
  the same TalkBack pattern as the details-sheet spinner: a polite
  live region plus a state description naming the loading state.

## TMDB attribution

- Library settings SHALL show the official TMDB logo next to the
  existing text attribution.
- The logo asset SHALL be TMDB's official "Primary short (blue)"
  artwork (same path and gradient as the published SVG), not a
  redraw.
