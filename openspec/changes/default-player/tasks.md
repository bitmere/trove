# Tasks

- [x] Read repository rules and required Android/design skills; choose existing tokens.
- [x] Add Android-free component/selection policy and JVM tests.
- [x] Persist package/activity only in trove_accounts via DebridRepository; support clearing.
- [x] Wire synthetic player discovery through AppContainer with API 30+ manifest visibility.
- [x] Add settings row, bounded selection dialog, stale-choice presentation and error feedback.
- [x] Add one-play Play with… actions in details and per-file overflow; retain normal Play.
- [x] Recheck availability by concrete MIME off Main, target saved component, and fall back on ActivityNotFoundException/SecurityException without clearing.
- [x] Add repository, ViewModel, discovery/privacy adapter guards and core policy tests.
- [x] Review collected source diff and parse changed XML; leave existing MIME/privacy tests untouched.
- [ ] Coordinator: review diff before compiling, focused tests, full JVM suite, then lint once.
- [ ] Phone: selection/restart/clear, one-play chooser/cancel, stale/blocked app, multi-file real IDs, API 30+ visibility, large fonts and low-memory devices.

See validation.md for commands and limitations. No Gradle execution in the editing sandbox.
