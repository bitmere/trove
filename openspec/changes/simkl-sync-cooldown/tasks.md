# Tasks

- [ ] Engine cooldown (S, low): in `SimklSyncEngine`, add
  `SYNC_COOLDOWN_MS = 30 * 60 * 1000L` and
  `sync(force: Boolean = false)`. When a snapshot exists and
  `now() - lastCheckedMs < SYNC_COOLDOWN_MS` and `!force`, return
  `SimklSyncResult.CooledDown` with zero network calls and no
  snapshot write. First sync (no snapshot) always runs. The quota
  block check stays first. Add `CooledDown` to `SimklSyncResult`.
  Update the class KDoc.
- [ ] Repository passthrough (XS, low): `TroveRepository.syncSimkl`
  and `DebridRepository.syncSimkl` gain `force: Boolean = false`;
  `CooledDown` bumps no snapshot version (like
  `NotSignedIn`/`NotConfigured`). `FakeRepository.syncSimkl`
  records the passed `force` for tests.
- [ ] Settings bypass (XS, low): `SettingsViewModel.syncSimklNow`
  calls `repository.syncSimkl(force = true)`. Automatic callers
  (`DownloadsViewModel.requestSimklSync`) keep the default.
- [ ] Tests (S, low): in `SimklSyncEngineTest` — cooldown skips
  the network when the last check is recent; allows at exactly
  30 minutes; `force = true` bypasses; first sync runs with no
  snapshot; badges are still served from the cached snapshot while
  cooled down. Existing sync tests must keep passing (their clock
  is past the cooldown).
- [ ] Gates (XS, low): full test suite 0 failures, lint 0 errors,
  detector 0 findings, `git diff --check` clean.
