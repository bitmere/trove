## MODIFIED Requirements

### Requirement: Install-time ownership check tolerates symlinked ancestors
Trove SHALL accept a downloaded APK whose `filesDir` ancestor is a symlink
(on Android, `/data/user/0` links to `/data/data`) when the file itself is
inside the updates directory. `PendingUpdateSession.isOwnedPath()` SHALL
canonicalize both sides: the candidate's canonical parent MUST equal the
canonical updates directory, the file MUST keep the `.apk` extension, and
`file.canonicalFile` MUST equal `File(canonicalParent, file.name)`. The
single shared predicate SHALL continue to gate install, save/load and
cleanup so they agree.

#### Scenario: Update through the normal Android files directory
- **Given** an APK under a `files/updates` directory reached through a symlinked ancestor, so its absolute path differs from its canonical path
- **When** the install guard, a save/load round trip, or stale-file cleanup checks ownership
- **Then** the file is accepted as owned and the update proceeds past the guard.

#### Scenario: Leaf symlink escaping the updates directory
- **Given** an `.apk` symlink inside the updates directory pointing to a file outside it
- **When** ownership is checked
- **Then** the file is rejected, because its canonical parent is not the updates directory.

## ADDED Requirements

### Requirement: Ordered install re-verification with per-sub-check results
Trove SHALL re-verify at the install boundary in order: ownership, then
byte size against the release, then SHA-256 against the release. A failed
sub-check SHALL stop later file reads: on ownership failure size and hash
are reported as not checked with no observed values, and on size failure
hash is reported as not checked. A genuine size or hash failure SHALL keep
the existing `VERIFICATION` issue and hash/size message; an ownership
failure SHALL use a distinct `OWNERSHIP` issue and message.

#### Scenario: Ownership failure names ownership, not hash/size
- **Given** an APK that fails the ownership check
- **When** install runs
- **Then** Settings shows the ownership message with size and hash reported as not checked, and no install retry is offered.

#### Scenario: Genuine size or hash failure keeps the existing error
- **Given** an owned APK with the wrong byte size, or the right size with the wrong SHA-256
- **When** install runs
- **Then** Settings keeps the existing hash/size message, the diagnostic names the failing sub-check with expected vs observed values, and no install retry is offered.

### Requirement: Typed, copyable install diagnostics
Install failures from the guard SHALL carry one typed `InstallDiagnostics`
record: per-sub-check pass/fail/not-checked, expected vs observed bytes and
SHA-256, and whether deletion of the file succeeded (not attempted when the
file was not owned). The record SHALL be collected before cleanup, rendered
through the existing `Error.detail` path in Settings as selectable text, and
kept in memory only, with no saved-state, backup or log copy. It SHALL
retain no URL, path, API key, response body, raw header or exception
message. Owned files SHALL still be deleted on install failure; unowned
files SHALL be left in place.

#### Scenario: Failed install shows what actually failed
- **Given** an install that fails the guard
- **When** Settings shows the error
- **Then** the detail names the failing sub-check with expected vs observed measurements and whether the file was deleted, and the text is selectable for copying.

### Requirement: No behavior change outside the install guard
Download verification, archive/package/signer verification, the installer
session flow, pending-session cleanup of stale verified files and the
permission flow SHALL be unchanged. No dependency, version, signing,
workflow, manifest URL or release-process changes.
