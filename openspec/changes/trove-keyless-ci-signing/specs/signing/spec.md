# Signing capability — delta spec

> Historical change. [bitmere-releases](../../../bitmere-releases/proposal.md) defines the current GitHub Releases distribution, update channels, and signing contract.

## ADDED Requirements

### Requirement: Keyless validation
The build SHALL configure and run debug assembly, app/core unit tests and full
app lint without release signing properties, passwords or a keystore.

#### Scenario: CI has no signing secrets
- **GIVEN** no release signing properties or keystore are available
- **WHEN** `:app:testDebugUnitTest :core:test :app:lint :app:assembleDebug` run
- **THEN** release signing configuration SHALL NOT block these tasks, including
  release-variant lint analysis

### Requirement: Read-only complete CI coverage
CI SHALL run on pushes to main and all pull requests, including docs and workflow
changes, with `contents: read`, a 30-minute build timeout and concurrency scoped
to the workflow and PR number or ref, cancelling superseded runs. CI SHALL run
the keyless tasks and existing source guard as required checks and retain only
nonsensitive unit-test and lint reports even after failure.

#### Scenario: A docs pull request supersedes its previous run
- **GIVEN** a pull request has a running CI invocation
- **WHEN** a newer commit changes only documentation or a workflow
- **THEN** CI SHALL still run all required checks and cancel the superseded run

#### Scenario: A required check fails
- **GIVEN** a required build, test, lint or source guard check fails
- **WHEN** the build job finishes
- **THEN** CI SHALL preserve the failure and attempt report upload without signing
  material; no `continue-on-error` SHALL convert it into success

### Requirement: Fail-closed final quality gate
The `quality-gate` job SHALL always run after every required dependency and SHALL
succeed only when each dependency result is exactly `success`.

#### Scenario: Validation is incomplete
- **GIVEN** a required dependency failed, was cancelled, skipped or is missing
- **WHEN** the final quality gate evaluates dependency results
- **THEN** the gate SHALL fail

#### Scenario: All required validation succeeds
- **GIVEN** every required dependency result is exactly `success`
- **WHEN** the final quality gate evaluates those results
- **THEN** the gate SHALL succeed without triggering a merge or release

### Requirement: Generated Kotlin state stays local
The repository SHALL ignore generated `.kotlin/` directories without deleting
existing caches or user-owned artifacts.

#### Scenario: Kotlin generates local state
- **GIVEN** a build creates `.kotlin/` under the checkout
- **WHEN** files are prepared for review
- **THEN** that generated directory SHALL be excluded from the change

## MODIFIED Requirements

### Requirement: Secrets-only release signing
Release APK/AAB packaging SHALL require complete signing properties and an
existing keystore restored only in GitHub Actions. The explicit
`-Ptrove.keystore.properties=<path>` setting SHALL support temporary properties.
Missing or incomplete material SHALL fail clearly before a distributable is
produced, including direct packaging and indirect aggregate task requests.
Release builds SHALL never fall back to debug signing or an unsigned substitute.
R8 and resource shrinking SHALL remain enabled.

#### Scenario: Release packaging has no properties
- **GIVEN** no signing properties are supplied
- **WHEN** a direct or indirect task requests release APK or AAB packaging
- **THEN** the build SHALL fail with an Actions-only signing explanation before
  producing a distributable, including when outputs would otherwise be cached

#### Scenario: Supplied material is incomplete
- **GIVEN** signing properties omit required values or reference a missing key
- **WHEN** release packaging is requested
- **THEN** the build SHALL fail without printing passwords or producing a debug
  or unsigned substitute

#### Scenario: Authorized Actions signing
- **GIVEN** an explicitly authorized Actions run restores the existing secret
  material and supplies its temporary properties path
- **WHEN** optimized release packaging runs
- **THEN** it SHALL use the release signing configuration and preserve shrinking;
  positive signing verification SHALL occur only in Actions

## REMOVED Requirements

### Requirement: Unconditional release-secret availability during configuration
The former requirement to find release signing material while configuring any
task is removed. Secret validation SHALL apply to actual release packaging.

#### Scenario: Configure an unrelated task
- **GIVEN** signing material is absent
- **WHEN** debug, test or lint tasks are configured
- **THEN** the former unconditional missing-key exception SHALL NOT occur

### Requirement: Retrievable private signing-key backup
The former claim that a private `signing-keys` repository provides a retrievable
keystore is removed. Build guidance SHALL state that the rotated key and random
passwords exist only in Trove's GitHub secrets and cannot be recovered locally.

#### Scenario: Review release signing guidance
- **GIVEN** a builder reads the release configuration comments
- **WHEN** release signing is unavailable locally
- **THEN** the guidance SHALL direct signing to Actions and SHALL NOT suggest
  retrieving, recreating or rotating a key, asking for passwords, or debug signing

## Non-goals

- No runtime, provider, `requestdl` playback, share, settings or UI changes.
- No version, tag, release workflow, updater, mirror or dead-code work.
