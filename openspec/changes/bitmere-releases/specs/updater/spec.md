## ADDED Requirements

### Requirement: Manual update channel

Settings SHALL default to Stable and persist Beta opt-in in a private update
preference. Stable SHALL use GitHub's latest stable release. Beta SHALL
scan a bounded release list and select the highest unambiguous Android
versionCode across stable releases and prereleases. Checks SHALL be manual
and independent of provider credentials.

#### Scenario: Channel changes during a check

- **WHEN** the user selects another channel while a check is in flight
- **THEN** Trove SHALL clear any offer, discard the stale result and wait for
  a manual check using the newly saved channel.

### Requirement: Verified release bytes

Trove SHALL require the exact versioned APK and SHA-256 asset from the chosen
release, enforce the API byte size including on resume, compare the digest,
and verify package, version and signer before requesting confirmation.

#### Scenario: Resumed request is answered with the full APK

- **WHEN** a ranged request returns HTTP 200
- **THEN** Trove SHALL restart from offset zero and verify the complete APK
  before exposing installation.
