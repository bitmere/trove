# Test organization: per-feature layout, not fewer tests

## What the user asked for

Why are there ~600+ tests instead of tests organized per feature? Can
they be combined per feature and optimized?

## Verified current state (0.1.23, from a counted census)

**There are not 600+ tests — there are 411.** Counted, not estimated:
238 in `:app` + 173 in `:core` = 411 unique `@Test` methods across 53
files / 55 classes / 7,910 lines. The 649 comes from the test-result
XML already in the checkout: `:app:testDebugUnitTest` ran 238,
`:app:testReleaseUnitTest` ran the *identical* 34 classes again
(verified by diff) for another 238, plus `:core:test` 173 — 238×2+173
= 649. **CI never sees 649**: `ci.yml:33` and `release.yml:89` run only
`testDebugUnitTest` + `:core:test` (411 results, ~26 s of test time).
The 649 appears only from a local `./gradlew test` / Android Studio
"run all tests", which executes both build variants. The double run buys
almost nothing — unit tests run on the host JVM; only 1–2 R8-sensitive
tests in `StartupRegressionTest` justify a release run at all.

**The tests are already organized per feature** — one class per provider,
per ViewModel, per updater component. 0 disabled, 0 for removed
behavior, 0 hitting real network, 0 `Thread.sleep` in test bodies.
The slowest suite is `SettingsBackupTest` (4.4 s) because PBKDF2 runs
210,000 iterations per encrypt/decrypt — a deliberate security
parameter, not waste. The real coverage gap is Compose UI: only 10
device-only `androidTest` tests; the main screens have no UI tests.

The impression of disorganization comes from a handful of exceptions,
not the overall shape:
- `FakeTransport` copied verbatim into 6 core provider test files + a
  7th variant in `MediaMetadataTest` (~70 lines; `:app` already solved
  this with shared `Fakes.kt`, `:core` has no equivalent).
- `AddSettingsViewModelTest.kt` is 835 lines holding two classes (18 +
  25 tests).
- `SeniorRewriteTest` (9 tests) is a cross-cutting bundle with an opaque
  name — add, auth, details, sign-out, delete, cover, diagnostics,
  budgets in one class; a failure there names no feature.
- `CredentialStoreTest` misfiled inside `RateLimiterTest.kt`.
- Five near-identical per-service "add rejected asks for a new key"
  tests — parameterize, don't delete (0 parameterized tests exist today).
- Eight single-`@Test` files, but three are deliberate shipped-bug
  guardrails (`CountPillGuardrailTest`, `PlaybackPrivacyTest`,
  `ShareRoutingTest`) that must stay standalone.

## Decisions for review

1. **No coverage is deleted.** Every proposed move names what still
   covers the behavior. Anyone promising "600+ → 200 tests" is proposing
   deleting coverage; this audit finds no coverage safe to delete.
2. **Merges are by feature, never across features.** A merged
   "UpdaterTest" would hide which component broke. Target state: ~48–50
   files, 411 tests unchanged, ~7,750 LOC, same ~26 s CI runtime.
3. **Keep the three source guardrails standalone.** Their value is
   precisely that they are narrow, named guardrails for shipped bugs;
   folding them hides the regression they pin.
4. **Stop the local 649 by convention, not by code change:** document
   that `./gradlew test` (both variants) is not the CI gate; CI already
   runs debug-only. Keep the release-variant run available for the 1–2
   R8-sensitive tests.
5. **Do not "fix" the slowest suite by lowering PBKDF2 iterations.**
   The 210,000-iteration cost is a security parameter.
6. **Close the real gap separately:** per-feature Compose UI tests for
   the main screens are new work (L, medium risk — device/emulator
   time), not a reorganization. Flagged for a separate decision, not
   part of this change.

## Out of scope

- Deleting any test without a named covering test.
- Converting the 1.54 s AllDebrid cancel test without attributing its
  cost first (one targeted timing run).
- Compose UI test coverage (separate decision, see decision 6).
