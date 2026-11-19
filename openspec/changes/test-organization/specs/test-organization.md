# test-organization

## Requirements

### Requirement: `:core` tests share one fake transport
The seven copies of the fake HTTP transport in `:core` tests SHALL be
replaced by one shared `RecordingTransport`, with each provider test
keeping its own handler lambdas and assertions.

#### Scenario: A provider test failure still names the provider
- WHEN a TorBox provider test fails
- THEN the failure names the TorBox test class, not a shared helper.

### Requirement: Each ViewModel has its own test file
`AddViewModelTest` and `SettingsViewModelTest` SHALL live in separate
files, and `CredentialStoreTest` SHALL live in its own file.

#### Scenario: A settings failure points at the settings file
- WHEN a settings ViewModel test fails
- THEN the file named in the failure is the settings test file.

### Requirement: Cross-cutting regression tests live in feature suites
The nine tests in `SeniorRewriteTest` SHALL move into the feature
suites they cover, with assertions unchanged and total test count
unchanged at 411.

#### Scenario: Total count is unchanged
- WHEN the move is complete
- THEN the suite still has 411 unique unit tests.

### Requirement: Per-service add-rejection tests are parameterized, not deleted
The five per-service "add rejected asks for a new key/token" tests
SHALL become one parameterized test × 5 services, keeping all five
failure signals (parameter name = service).

#### Scenario: One service's failure is still identifiable
- WHEN the Real-Debrid case fails
- THEN the failure names Real-Debrid.

### Requirement: Shipped-bug guardrails stay standalone
`CountPillGuardrailTest`, `PlaybackPrivacyTest`, and `ShareRoutingTest`
SHALL remain standalone single-test files.

#### Scenario: A guardrail failure names the shipped bug
- WHEN the tab-row layout guardrail fails
- THEN the failure names the guardrail, not a merged suite.

### Requirement: The local double run is documented, not mistaken for 649 tests
The repo SHALL document that `./gradlew test` runs both build variants
(649 results) while CI runs debug-only (411 results), and that the
release-variant run exists only for the 1–2 R8-sensitive tests.
