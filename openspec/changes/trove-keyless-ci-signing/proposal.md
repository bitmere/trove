# Change: trove-keyless-ci-signing

> Historical change. [bitmere-releases](../bitmere-releases/proposal.md) defines the current GitHub Releases distribution, update channels, and signing contract.

One change for T1, intended branch `fix/trove-keyless-ci-signing`. No release
or version bump accompanies this change.

## Why

- Release signing currently throws during configuration when properties are
  absent, blocking keyless debug builds, tests and lint.
- A release comment incorrectly promises a private `signing-keys` copy.
  The rotated key exists only in GitHub secrets; nobody knows its passwords
  and there is no retrievable keystore.
- CI needs explicit read-only permissions, bounded execution, superseded-run
  cancellation, reports and a final gate that rejects incomplete validation.
- Generated `.kotlin/` directories lack an explicit ignore rule.

## Scope

1. `app/build.gradle.kts`: allow keyless configuration and validation while
   guarding direct and indirect release packaging. Retain release shrinking,
   secrets-only signing and the explicit `-Ptrove.keystore.properties=<path>`
   Actions handoff. Never substitute a debug key or unsigned release.
2. `.github/workflows/ci.yml`: preserve main pushes and all pull requests;
   run keyless app/core tests, full app lint, debug assembly and the existing
   source guard. Add reports, read-only contents permission, a 30-minute build
   timeout, PR/ref concurrency cancellation and an always-run `quality-gate`.
3. `.gitignore`: ignore generated `.kotlin/` directories without deleting them.
4. This proposal, `tasks.md` and `specs/signing/spec.md`: record the contract
   and the checks that still need the overseer's build environment.

## Non-goals

- T2 documentation reconciliation, dead-code decisions or staging changes.
- T3 updater, release workflow, public mirror, tags, publication or versioning.
- Key retrieval, recreation, rotation or password recovery.
- Package, SDK, dependency, runtime, UI, playback, provider, share or settings
  changes. Current `requestdl` playback and all functionality remain intact.
- Deleting caches, generated outputs, user artifacts or historical briefs.

## Constitution

The approved cleanup plan's T1 scope and the current execution brief govern
this change. Keep code modular, optimized and small for low-memory phones.
Use the android-dev-flow, android-testing and no-ai-slop-design skills within
that scope; their generic local-signing guidance does not override secrets-only
signing. The Trove certificate was rotated on 2026-11-16. Retired
`17d010bb` never shipped. These prefixes are context, not full certificate pins.
Signing happens only in explicitly approved GitHub Actions release work using
`TROVE_KEYSTORE_BASE64` and `TROVE_KEYSTORE_PROPERTIES`.

## Open questions

- The unchanged source guard currently fails at its `CountPill.requiredWidth`
  assertion. T1 must keep that failure visible; resolving the existing runtime
  or guard mismatch requires separately scoped work.
- Gradle, lint, unit suites, device checks and positive signing are not run in
  this sandbox. The overseer must verify the pending tasks before claiming CI
  or signing success.
