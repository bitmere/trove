# Tasks

- [ ] Slugged TMDB links (S, low): in `TmdbResolver`, accept `-`
  (slug) after the numeric id in `tmdbLinkPattern`; keep
  positive-id and type validation. Extend
  `TmdbResolverTest.parseManualLink` with slugged movie and tv
  links, and confirm a non-TMDB slugged path still parses as a
  title.
- [ ] Delete exits the picker cleanly (S, low): in
  `DownloadsViewModel.requestDelete`, cancel the in-flight manual
  search job and remove that download's `manualTmdbSearch` entry,
  the same cleanup `dismissFilePicker` performs. Add a test:
  search in flight, delete, cancel, reopen before completion —
  the field state is clean and no stale result publishes.
- [ ] Resolving selection disables row buttons (S, medium): expose
  the in-flight override resolution per download in UI state
  (set when `overrideEdits` gains the ref, cleared in `finally`);
  pass it into `DownloadDetailsState` and disable the candidate
  pick buttons and the Clear override button while it is set.
  Add a test: a second pick/clear issued during a slow resolution
  is not silently dropped — it either waits or is refused visibly
  via the disabled state (assert the state flag is set during the
  resolution and cleared after).
- [ ] Edited input clears feedback (S, low): add a view-model
  `clearManualTmdbSearch` that removes the download's manual
  status entry (no-op when absent); call it from the sheet's
  `onValueChange` for the manual field. Add a test: `FOUND` set,
  clear called, status is null and candidates are untouched.
- [ ] Draft survives recreation (XS, low): change the manual input
  state in `DownloadDetailsSheet` from `remember` to
  `rememberSaveable`, keyed by download identity. No test hook
  exists for Compose saved state in this repo; verify by
  inspection and keep the key identical to the current `remember`
  keys.
- [ ] Gates: full JVM test suite green, lint 0 errors, detector 0
  findings on the changed UI files, `git diff --check` clean.
  Revert-check each fix once if cheap (temporarily remove the
  fix, exactly one test should fail); leave no revert-check
  markers.
