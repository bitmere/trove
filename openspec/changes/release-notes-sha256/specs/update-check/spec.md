## ADDED Requirements

### Requirement: Release notes carry the APK SHA-256

Release notes SHALL carry the APK SHA-256 as one line, `SHA-256: <64
lowercase hex>`, immediately before the terminal
`Android-Version-Code:` trailer. The parser SHALL accept at most one
line starting with `SHA-256:` (after leading whitespace); when present
it SHALL be the penultimate line and SHALL match
`SHA-256: [0-9a-f]{64}` exactly, with no leading or trailing
whitespace. Any other `SHA-256:`-prefixed line — wrong length, uppercase
hex, extra spaces, missing value, duplicate lines, or a hash line
anywhere except immediately before the trailer — SHALL fail release
parsing, so the check surfaces a manifest error and offers no update.
The hash line SHALL be stripped from the displayed notes, like the
version-code trailer.

#### Scenario: Notes with a valid hash and no sidecar

- **Given** a release whose notes end with `SHA-256: <64 hex>` followed
  by the version-code trailer, and whose assets contain only the APK
- **When** the manifest is parsed
- **Then** `LatestRelease.sha256` is the hash, `checksumUrl` is empty,
  and the displayed notes contain neither the hash line nor the trailer.

#### Scenario: Notes with a malformed hash

- **Given** a release whose notes contain a `SHA-256:` line with 63
  hex characters, uppercase hex, trailing spaces, or placed anywhere
  except immediately before the trailer
- **When** the manifest is parsed
- **Then** parsing fails and the update check reports a manifest error
  with no update offered and no install attempted.

### Requirement: Sidecar fallback for older releases

When the notes carry no hash line, the updater SHALL fall back to the
`.sha256` sidecar asset when one is present, validated exactly as
before (exact name, `uploaded` state, exact size `67 + APK name
length`, allowlisted download URL, body `<64 hex>  <APK name>\n`).
The sidecar asset SHALL be optional in the manifest: a release with
neither a notes hash nor a sidecar asset SHALL parse, with an empty
expected hash, and the checksum step SHALL then fail with ASSETS so no
unverifiable update is offered.

#### Scenario: Update to an already-published release

- **Given** a release whose notes have no hash line but which publishes
  a `.sha256` sidecar (for example 0.1.23)
- **When** the updater checks for an update
- **Then** it downloads the sidecar, uses its hash, and offers the
  update exactly as before.

#### Scenario: No hash source at all

- **Given** a release whose notes have no hash line and which publishes
  no sidecar asset
- **When** the updater checks for an update
- **Then** the check fails with ASSETS and offers no update.

### Requirement: Notes hash is used without a network fetch

When `LatestRelease.sha256` is present, `ApkDownload.checksum` SHALL
return it with no network request, after rejecting a non-empty value
that is not a valid 64-lowercase-hex hash. The download path SHALL keep
comparing the computed digest of the downloaded APK against that hash,
and the install guard SHALL keep verifying ownership, then size, then
SHA-256, in that order, with the same issues and diagnostics as before.

#### Scenario: Notes hash offered without a checksum fetch

- **Given** a release whose notes carry a valid hash
- **When** the update check runs
- **Then** no request is made to a checksum URL and the offered release
  carries the notes hash.
