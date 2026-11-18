## ADDED Requirements

> Historical change. [bitmere-releases](../../../bitmere-releases/proposal.md) defines the current GitHub Releases distribution, update channels, and signing contract.

### Requirement: Streaming bounded download
Trove SHALL stream a single APK at a time into filesDir/updates using an 8 KB buffer and incremental SHA-256, with a 256 MB safety cap. It SHALL close every connection and delete partial, empty, truncated, cancelled and hash-mismatched files. It SHALL preserve a verified file for an installation retry and remove stale owned files before a subsequent download after settling pending sessions.

#### Scenario: Unknown size
- **Given** no Content-Length header
- **When** the user downloads
- **Then** progress shows bytes and an indeterminate indicator, and the hash gates readiness.

#### Scenario: Cancel at the final byte
- **Given** a download has written all bytes but has not returned verification
- **When** cancellation occurs
- **Then** its file is deleted and the state returns to Available.

### Requirement: Installation eligibility
Trove SHALL verify the archive package app.trove.debrid, exact manifest versionCode, higher installed-version comparison and compatible signing identity before offering installation. It SHALL recheck owned path, size, hash, package, version and signer immediately before every install attempt, including retries.

#### Scenario: Wrong APK
- **Given** bytes pass the mirror hash
- **When** their package, version or signer fails validation
- **Then** no install is offered and the file is removed after download failure.

#### Scenario: Signer mismatch
- **Given** incompatible signing identities
- **When** archive validation fails
- **Then** Settings explains that upgrading cannot proceed and offers clean-install instructions that warn local settings and the saved key are removed.
- **And** Trove never uninstalls automatically or claims settings survive a clean install.
