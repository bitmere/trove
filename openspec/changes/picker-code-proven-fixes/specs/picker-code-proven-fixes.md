# Picker code-proven fixes

## Slugged TMDB links

- A themoviedb.org link `/movie/<id>-<slug>` or `/tv/<id>-<slug>`
  parses as `ManualTmdbLink.ById(id, type)`, exactly like the
  unslugged link: the numeric id must be positive and the
  `movie`/`tv` segment still decides the type.
- A link with other characters directly after the id (no
  separator) still does NOT parse as an id; it falls through to
  title handling as before.
- Bare numeric ids, titles, blank/over-long input, and non-TMDB
  links behave exactly as before.

## Picker-exit cleanup

- Every path that leaves the picker cancels any in-flight manual
  TMDB search job for that download and removes that download's
  `manualTmdbSearch` entry: ordinary dismiss (`dismissFilePicker`,
  existing) and delete (`requestDelete`, new).
- Reopening the same download after a delete-cancel, before the
  cancelled search could have completed, starts with no manual
  status: the field and Search button are enabled, and no stale
  result publishes into the reopened sheet.

## Resolving selection

- While an override resolution is in flight for a download, the
  picker's candidate pick buttons and the Clear override button
  for that download are disabled, so a tap during resolution is
  never silently dropped.
- The disabled state is driven by view-model state that is set
  when the resolution starts and cleared when it finishes
  (including cancellation and failure), not by the sheet's local
  state.
- The stored pick itself is unchanged: one resolution per download
  at a time, resolved through the existing `overrideEdits` guard.

## Edited input clears feedback

- Changing the manual field's text removes that download's manual
  search status (`FOUND`, `NO_MATCH`, or `INVALID`), so feedback
  never describes a query the field no longer holds.
- Clearing the status does not touch the shown candidates.
- Submitting a new search sets `SEARCHING` again as before.

## Draft survives recreation

- The manual field's draft is held in `rememberSaveable`, keyed by
  the download's kind and id (the same keys as the current
  `remember`), so an unsubmitted draft survives activity
  recreation (e.g. rotation).
- Ordinary dismissal still discards the draft: reopening a
  download starts with an empty field.
