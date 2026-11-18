## ADDED Requirements

### Requirement: Signed GitHub Release publication

GitHub Releases for bitmere/trove SHALL be the sole public source of signed
Trove APKs. The workflow SHALL build from the approved tag and verify the
exact package, version, approved bitmere certificate and optimized bytes.
Release notes SHALL end with a single Android-Version-Code trailer. Every
release SHALL publish the versioned APK and its exact SHA-256 sidecar.

#### Scenario: First stable publication

- **WHEN** an approved release is dispatched with prerelease set to false
- **THEN** the workflow SHALL promote the verified draft as latest and verify
  the public metadata, download bytes and certificate anonymously.

#### Scenario: Prior signing identity

- **WHEN** the installed APK uses an older signing identity
- **THEN** release notes and Settings SHALL explain the clean install and the
  loss of local settings and the saved API key.
