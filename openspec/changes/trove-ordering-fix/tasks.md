# Tasks

- [ ] Small / low risk: make core and prepared-list Newest/Oldest date-primary, preserving stable ties and all other sort behavior.
- [ ] Medium / medium risk: retain live coverage through refresh/sort traversal, errors and cancellation; reconcile foreground heads and preserve/clamp cursors.
- [ ] Small / low risk: normalize truncated disk snapshots and pass full coverage to the persistence boundary.
- [ ] Small / low risk: record successful head validation only; apply snapshot-age bypass on startup and inactive-tab visits.
- [ ] Medium / low risk: add date, preference, coverage, retry, cancellation, restored-tab and persistence regression tests; retain existing stale-preview replacement assertions.
- [ ] Coordinator: review the diff, run focused tests, full suite and lint; verify device behavior before any separately approved PR or release.
