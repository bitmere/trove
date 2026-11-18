# Tasks

> Historical change. [bitmere-releases](../bitmere-releases/proposal.md) defines the current GitHub Releases distribution, update channels, and signing contract.

## Failing tests first
- [x] Add parser and policy rejection tests before implementing core rules.
- [x] Add fake HttpTransport checks and fake streaming, session, durable-record, receiver and virtual-time ViewModel tests.
- [x] Add device-only Compose state, notes scrolling, accessibility, back and permission-return tests (UNRUN).
- [ ] Execute failing-test baseline (coordinator; Gradle prohibited in this checkout).

## Core rules
- [x] Add Android-free metadata, strict bounded Gson parsing and integer/URL/hash/eligibility policy.

## Adapters
- [x] Add bounded public HttpTransport implementation, streaming download, incremental hash and archive/signature verification.
- [x] Add testable session protocol, fallback, durable correlation and callback handling.

## Settings wiring
- [x] Hoist a single UpdateViewModel using SimpleFactory; add resource-based Material 3 update section and scrollable notes dialog.
- [x] Preserve existing account UI, navigation, package identity, provider, share and playback behavior.

## Manifest and lifecycle integration
- [x] Add install permission, non-exported receiver and narrow updates FileProvider.
- [x] Re-offer Install after permission return; correlate callbacks and clean up/relaunch independently of UI observers.
- [x] Source-only XML, string-reference, Android-free core and intent-filter inventory checks.
- [ ] Coordinator: focused tests, full JVM suites and lint after reviewing source changes.

## Device verification
- [ ] UNRUN: phone Settings states, accessibility, long notes, back, focus and scroll restoration.
- [ ] UNRUN: unknown-sources denial/grant/return without automatic installation.
- [ ] UNRUN: signed upgrade, cancellation, fallback, signer mismatch, session recovery and process replacement/relaunch.

## Documentation
- [x] Add this proposal and four SHALL/Given/When/Then specifications.
- [x] Add distribution delta spec and update README and distribution docs for manual updates, bootstrap and release prerequisites.

## Release workflow and gate
- [x] Add dispatch-only validate/release jobs with main ancestry checks, keyless validation and Actions-only APK signing.
- [x] Add prepare/recover/verify/publish/mirror/anonymous gate commands with full cert pin and immutable asset checks.
- [x] Add atomic fast-forward mirror publication, explicit absent-manifest bootstrap and anonymous hash verification.
- [x] Add pure offline Python tests for metadata, channel, schema, filenames, pin, bootstrap, rollback and divergence.
- [ ] Coordinator: run keyless Gradle validation after reviewing the complete diff; no Gradle run in this checkout.
- [ ] Release preparation: review/increment version.properties, add reviewed changelog notes and explicitly approve a version tag and dispatch.
