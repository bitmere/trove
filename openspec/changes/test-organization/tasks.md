# Tasks — test organization

Sizes: S ≤ 1 day, M 2–4 days. Implemented 2026-11-19 (local only, gates
green: full `./gradlew test lint assembleDebug` 808 tests / 0 failures,
lint 0 errors 24 warnings, release-gate unit tests 52 OK). Suite counts
re-derived during the work — the spec's 411 was the pre-growth audit
count; the suite is 570 unique tests (312 `:app` debug + 258 `:core`),
808 as `./gradlew test` reports them (debug + release variants).

## 1 — Shared core test fixtures (S, low risk) — DONE

- [x] One shared `RecordingTransport(handler)` in `:core` tests,
  replacing the 7 `FakeTransport` / `FakeHttp` copies (6 provider test
  files + `MediaMetadataTest`). The shared `success(...)` / `upload(...)`
  JSON helpers were NOT created: only AllDebrid has `success`/`upload`,
  and each file's `ok` wraps a different provider envelope — one shared
  helper would change the bytes under test. Documented in
  `RecordingTransport.kt`'s KDoc; local helpers kept.
- [x] All 128 provider tests keep their own handler lambdas and
  assertions; only the transport shell is shared. A failure still
  names the provider file/class. (`MediaMetadataTest` keeps a small
  `CinemetaTransport` delegating record-and-answer to
  `RecordingTransport`; its canned catalog/meta routing is per-test
  data, not transport duplication.)
- [x] Full JVM suite + lint green (see header).

## 2 — Split the 998-line settings test file (S, low risk) — DONE

- [x] Split `AddSettingsViewModelTest.kt` (998 lines, 50 tests — spec
  said 835/43, stale) into `AddViewModelTest.kt` (18 tests) +
  `SettingsViewModelTest.kt` (32 tests), each with only its own
  imports. No test changed.
- [x] Full JVM suite + lint green.

## 3 — Dissolve `SeniorRewriteTest` into feature suites (M, low risk) — DONE

- [x] Moved its 8 tests (spec said 9, stale) into the suites they
  cover, assertions verbatim: add/auth ×2 → `AddViewModelTest`;
  details busy-guard, sign-out/late-refresh, delete-kind ×3 →
  `DownloadsViewModelTest` (sign-out/late-refresh builds a
  `DownloadsViewModel` and asserts its `signedOut` state, so it went
  there, not `SettingsViewModelTest`/`StartupRegressionTest`);
  diagnostics → `SettingsViewModelTest`; budgets + URL-download-failure
  ×2 → `DebridRepositoryTest`. File deleted. Net test count unchanged
  (570 unique / 808 reported). `scripts/check-senior-rewrite.py` is
  unaffected (it counts files/tests, does not reference the class).
- [x] Full JVM suite + lint green.

## 4 — Extract the misfiled credential test (S, trivial risk) — DONE

- [x] Moved `CredentialStoreTest` (2 tests) out of `RateLimiterTest.kt`
  into its own `CredentialStoreTest.kt`.
- [x] Full JVM suite + lint green.

## 5 — Parameterize the per-service add-rejection tests (S, low risk) — DONE

- [x] The 5 per-service "add rejected asks for a new key/token" tests
  are now one JUnit4 `@Parameterized` test × 5 services in new
  `AddAuthRejectionTest.kt` (`@Parameters(name = "{0}")`, failure still
  names the service). Real-Debrid's literal-string error branch vs the
  other four's `errorResource` branch both preserved per case. First
  parameterized test in the repo; no new dependency. Kept in its own
  file because a `@Parameterized` runner re-runs every test in its
  class once per parameter.
- [x] Full JVM suite + lint green.

## 6 — Fold single-test files, except the guardrails (S, low risk) — DONE

- [x] Folded: `PlaybackMimeTypeTest` → `PlayerDiscoveryTest` (app
  module — `playbackMimeType` is app-internal, not core
  `PlaybackTargetTest`); `DownloadsSilentRefreshTest` →
  `DownloadsViewModelTest`; `AddIoRegressionTest` → `AddViewModelTest`;
  `BackdropCaptureTest` → `DownloadPresentationTest`;
  `SettingsDestinationTest` → new `SettingsStructureTest.kt` (a second
  class in `SettingsViewModelTest` would recreate the problem item 2
  just fixed).
- [x] **Kept standalone:** `CountPillGuardrailTest`,
  `PlaybackPrivacyTest`, `ShareRoutingTest`.
- [x] Renamed `FakeRepositoryBatchDeleteTest` to
  `FakeRepositoryBatchDeleteContractTest` (file + class).
- [x] Full JVM suite + lint green.

## 7 — Document the debug-only convention (S, trivial risk) — DONE

- [x] Documented in `docs/TESTING.md`: `./gradlew test` reports 808
  results for 570 unique tests (312 `:app` debug + 238 `:app` release
  + 258 `:core`; `:core` has no release-variant run). CI runs
  debug-only (570). Release-variant re-run kept for the 1–2
  R8-sensitive `StartupRegressionTest` account-JSON cases.
- [x] No code change.

## 8 — Investigate the slowest single test (S, low risk) — DONE, no change

- [x] The 1.54 s AllDebrid cancel test does not reproduce: latest run
  0.098 s; whole `AllDebridProviderTest` class fell 2.77 s → 1.58 s.
  Code trace confirms the token bucket cannot be the cost (both
  buckets start full, cancel path makes ~3 immediate acquires,
  `finishUpload` delays only on `attempt > 0`, `pollDelayMs` already 0,
  no deterministic real-clock wait on the cancel path). The 1.54 s was
  one-off JVM/fork warm-up in a single run. Per the item's rule, no
  fake clock injected and the test left alone.

## Deferred / separate decision (do not implement here)

- [ ] Per-feature Compose UI tests for the main screens
  (`DownloadsScreen`, `SettingsScreen`, `AddSheet`) — L, medium risk
  (device/emulator time). New work, not a reorganization.
