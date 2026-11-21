# Tasks

- [ ] Manual submit owns the picker (S, low): in
  `DownloadsViewModel.searchManualTmdbLink`, cancel
  `metadataCandidateJob` after the open-picker guard passes, so a
  late automatic load cannot overwrite manual results or clear
  manual feedback mid-search.
- [ ] Dismiss clears a pending manual search (S, low): in
  `dismissFilePicker`, cancel `manualTmdbSearchJob` and remove the
  dismissed download's `manualTmdbSearch` entry; in the manual
  job's completion guard, also clear the entry when the picker is
  gone, so no stale `SEARCHING` survives a close.
- [ ] Found feedback (S, low): add `ManualTmdbSearchStatus.FOUND`;
  a manual `Found` sets it instead of removing the entry; the
  sheet renders the `match_picker_manual_found` line under the
  Search button. Update the two existing tests that assert the
  status is null after a `Found`.
- [ ] No-match guidance (XS, low): reword
  `match_picker_manual_no_match` to say to try the full title or
  paste a themoviedb.org link.
- [ ] Tests (S, low): in `DownloadsViewModelManualTmdbTest`,
  cover the late automatic load (manual `Found` survives when the
  automatic job was still in flight at submit time), the
  dismiss-mid-search case (stale `SEARCHING` cleared, a new search
  works on reopen), and the `FOUND` status after a manual `Found`.
- [ ] Gates: full JVM test suite green, lint 0 errors, detector 0
  findings on the changed UI files, `git diff --check` clean.
