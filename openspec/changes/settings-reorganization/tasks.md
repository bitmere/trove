# Tasks

- [x] Inventory the sections in `SettingsScreen.kt` and related section files and map each to a group: Providers, Library, Playback, Appearance, Updates, Backup. No separate About section exists (version is shown inside App updates), so no About group is added.
- [x] Add the six group titles and one-line summaries to `strings.xml`, reusing existing title strings where they fit.
- [x] Restructure `SettingsScreen` into a root group list plus one sub-screen per group, with internal destination state, a back arrow per sub-screen and a system-back handler that returns to the root list before leaving Settings. Keep the existing NavHost destination and `onBack` contract unchanged.
- [x] Move each section's composables verbatim into its group: Providers gets the provider list, the TorBox key block and the five provider sign-in sections plus the coming-soon list; Library gets clutter files, clipboard offers and release preferences; Playback gets the default player row and dialog; Appearance gets ThemeSection; Updates gets UpdateSection; Backup gets BackupSection.
- [x] Add `SettingsDestinationTest` coverage: the six groups exist in order with distinct titles, so a dropped group fails the suite.
- [x] Review source diff and XML/resource references without compiling.
- [x] Coordinator: ran focused settings tests (71 tests incl. SettingsDestinationTest 1, SettingsViewModelTest 25), full app/core JVM suites (404 tests, 0 failures), lint once (BUILD SUCCESSFUL, 0 errors, 29 warnings, none new).
- [ ] Phone: verify every group opens, every setting is present and works as before, system back returns to the group list, and leaving Settings still returns to Downloads.
