# Change: trove-docs-reconciliation

> Historical change. [bitmere-releases](../bitmere-releases/proposal.md) defines the current GitHub Releases distribution, update channels, and signing contract.

## Why

Staged documentation describes 0.1.1, obsolete createstream-only playback,
v6 design and debug signing. The checkout is 0.1.14 / code 14 with T1
Actions-only signing. Reconcile documentation with shipped behavior.

## Scope

Reconcile the sixteen staging files: merge README.md with main, add the
other fifteen, preserve dated proposal/research history, record verified
release evidence and index selected diagnostic history. Of the 27 local-only
files, retain only the tab-row diagnosis; record every disposition in tasks.md.

Correct the staged createstream/requestdl contradiction: Play uses a
per-file requestdl direct link for the selected kind and real file ID,
hands it only to the chosen ACTION_VIEW player with a filename-derived
concrete video MIME, and never logs, shares or exposes it in errors.
Streaming is plan-restricted on Essential and unused. Record the discrepancy
in AGENTS.md and the historical v1 spec's deviation note.

Document the 2026 modern Material 3 UI, v6 as historical only, secrets-only
Actions signing and the planned, unbuilt updater/mirror. The first signed
release after debug-signed 0.1.14 needs a clean install over 0.1.14.

## Non-goals

No runtime behavior, Kotlin, XML, Gradle, manifest, workflow, version,
branch, commit, tag, push, release or publication changes. Build and device
verification belong to TARS; this change leaves documentation on disk.

## Verification

Check documentation paths, stale claims and the docs-only file inventory.
Use `openspec validate trove-docs-reconciliation --strict` if installed;
otherwise manually check proposal/tasks/spec, requirement IDs and
Given/When/Then scenarios. Baseline test/lint results are supplied release
evidence, not new T2 execution results.
