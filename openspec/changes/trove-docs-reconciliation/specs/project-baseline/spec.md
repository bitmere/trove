# Project baseline — delta spec

> Historical change. [bitmere-releases](../../../bitmere-releases/proposal.md) defines the current GitHub Releases distribution, update channels, and signing contract.

## ADDED Requirements

### Requirement: PB-001 Traceable documentation baseline
Project documentation SHALL identify the current baseline as 0.1.14 / code
14 with 107 JVM tests and lint 0 errors, attributed to the release baseline.
The changelog SHALL use only supplied commit evidence and SHALL distinguish
T1 as Unreleased with no version change. It SHALL assign no separate release
to 0.1.7 and no unsupported test counts to intervening versions.

#### Scenario: A reader checks the release history
- **GIVEN** the 0.1.14 baseline and T1 commit evidence
- **WHEN** the reader opens the changelog or current AI reference
- **THEN** the documented version SHALL remain 0.1.14 / code 14 and T1
  SHALL NOT be represented as a new release

### Requirement: PB-002 Bounded historical evidence
Historical proposals and research SHALL remain explicitly dated history.
Only definitive diagnostic evidence SHALL be included, with original
filenames under docs/history/blank-screen and a dated index that states
what each record proves and what it does not. All 27 local-only file
choices SHALL be recorded; working briefs SHALL stay local.

#### Scenario: No successful device verification record exists
- **GIVEN** the tab-row diagnosis is source review and the local records
  do not document successful 0.1.13 device verification
- **WHEN** diagnostic history is selected
- **THEN** the diagnosis SHALL retain its caveats and the index SHALL
  attribute device confirmation to supplied release evidence without
  inventing a verification record

## MODIFIED Requirements

### Requirement: PB-003 Shipped playback contract
Current playback documentation SHALL describe a per-file TorBox requestdl
direct link for the selected download kind and real file ID, delivered only
to the user's chosen Android ACTION_VIEW player with concrete video MIME
derived from the filename. The bearer link SHALL never enter logs, share
targets or error messages. Streaming APIs SHALL be described as unused and
plan-restricted on Essential. AGENTS.md and the v1 spec deviation note SHALL
record the staged createstream-only discrepancy without changing code.

#### Scenario: A maintainer reads the playback guidance
- **GIVEN** staged docs claim createstream-only playback
- **WHEN** the maintainer reads reconciled current guidance
- **THEN** requestdl SHALL be the documented shipped path with chooser-only
  bearer-link handling and no endpoint or runtime changes

### Requirement: PB-004 Current design and distribution status
Documentation SHALL describe the 2026 modern Material 3 UI: Downloads as
one primary destination, no bottom nav, floating liquid-glass action cluster
and Settings behind the avatar. mockup-v6.html SHALL be historical only.
Signing SHALL be secrets-only through GitHub Actions; local release builds
SHALL NOT be described as able to sign. The first signed release after
debug-signed 0.1.14 SHALL require a clean install over 0.1.14. Current documentation SHALL describe
manual GitHub Releases checks, the Stable and Beta channels, and the bitmere
signing-key migration. Play internal sharing remains parked.

#### Scenario: A reader prepares a local build
- **GIVEN** release signing is available only through Actions secrets
- **WHEN** the reader follows TESTING.md
- **THEN** wrapper-based test/lint/debug commands SHALL be available and
  local release signing SHALL NOT be promised

#### Scenario: A reader checks design and updater status
- **GIVEN** a historical v6 mockup and the current GitHub Releases updater
- **WHEN** the reader follows the documentation index
- **THEN** v6 SHALL be marked historical and updater behavior SHALL be
  presented as a shipped manual feature
