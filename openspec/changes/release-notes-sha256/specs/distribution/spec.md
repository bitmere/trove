## ADDED Requirements

### Requirement: APK-only release pages

New releases SHALL publish exactly one asset, `Trove-v<version>.apk`.
The release workflow SHALL NOT upload a `.sha256` asset, and the
release gate SHALL NOT create, require, upload, or read back a sidecar
file. The SHA-256 SHALL instead be part of the published release notes,
inserted by the gate as a `SHA-256: <64 lowercase hex>` line
immediately before the `Android-Version-Code:` trailer, computed from
the verified APK bytes after the build.

#### Scenario: Publishing a new release

- **Given** a verified APK with SHA-256 `<hex>`
- **When** the gate publishes the release
- **Then** the release body ends with `SHA-256: <hex>` followed by the
  version-code trailer, the release has exactly the APK asset, and the
  release page shows only the APK.

### Requirement: Gate verifies the notes hash against the built APK

The gate SHALL require the notes of the release being published or
recovered to contain a `SHA-256:` line whose value equals the digest of
the verified APK bytes, and SHALL reject any notes carrying a
malformed, misplaced, or duplicate hash line. The changelog entry used
as the notes source SHALL NOT contain a line starting with `SHA-256:`;
that marker is reserved for the generated hash line, like the
`Android-Version-Code:` trailer.

#### Scenario: Notes hash does not match the APK

- **Given** an existing draft or published release for the tag whose
  notes hash differs from the approved APK digest
- **When** the gate publishes or recovers
- **Then** it fails closed without uploading, publishing, or reusing the
  mismatched bytes.

#### Scenario: Changelog tries to reserve the hash line

- **Given** a changelog entry containing a line starting with `SHA-256:`
- **When** the gate prepares the release metadata
- **Then** it fails with a reserved-marker error before any build or
  publication step.

### Requirement: Anonymous verification reads the hash from the notes

The anonymous public verification SHALL require the published release
to have exactly the APK asset, SHALL require its notes hash to equal
the approved APK digest, and SHALL download the public APK bytes and
compare size, digest, and signer exactly as before. It SHALL NOT fetch
a sidecar. A published release carrying a sidecar asset SHALL fail
verification as an unexpected asset.

#### Scenario: Public read-back of an APK-only release

- **Given** a published release with only the APK asset and a notes
  hash equal to the approved digest
- **When** the anonymous verification runs
- **Then** it downloads the APK, confirms size, digest, and signer,
  and passes without any sidecar fetch.

#### Scenario: Published release still carries a sidecar

- **Given** a published release whose assets include a `.sha256` file
- **When** the gate validates its assets
- **Then** it fails with an unexpected-asset error and refuses to treat
  the release as verified.
