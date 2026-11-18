# Tasks — trove-keyless-ci-signing

Each task names its files and verification. Gradle is prohibited in this
sandbox; unchecked build tasks belong to the overseer. No remote actions,
release, version bump or signing material recovery are authorized.

1. [x] Review the approved T1 scope, required skills and Matrix OpenSpec format.
   Files: `proposal.md`, `tasks.md`, `specs/signing/spec.md`.
   Verify: preserve the proposal/task/delta layout and explicit scenarios.

2. [x] Complete and source-review keyless configuration and fail-closed release wiring.
   File: `app/build.gradle.kts`.
   Verify: no eager missing-secret failure for debug/test/lint; no debug fallback;
   direct and indirect APK/AAB packaging depend on the signing guard; no false
   recovery claim; no package/SDK/dependency/shrinking changes.

3. [x] Complete and source-review keyless CI and generated-file exclusion.
   Files: `.github/workflows/ci.yml`, `.gitignore`.
   Verify: all PRs/main pushes, contents read, build timeout 30 minutes, PR/ref
   concurrency cancellation, required checks without `continue-on-error`, reports
   on failure, and `quality-gate` accepting only exact dependency success.

4. [x] Attempt supported OpenSpec validation and validate structurally.
   Files: this change's three Markdown files.
   Result: no installed `openspec` command. Attempting clean npm acquisition
   failed with network `EPERM`; no CLI package was installed. Structural checks
   against Matrix's format verify proposal/task headings, all three delta sections,
   requirement headings, SHALL statements and Given/When/Then scenarios.
   Strict CLI validation remains unrun: `openspec validate trove-keyless-ci-signing --strict`.

5. [ ] Run the overseer's no-secret build gate and retain actual reports.
   Command: `./gradlew --no-daemon :app:testDebugUnitTest :core:test :app:lint :app:assembleDebug`.
   Verify: configuration and all tasks succeed with no signing properties or key;
   lint has zero errors including release analysis; report actual test counts.
   Recheck keyless execution with configuration cache if enabled.

6. [ ] Run the negative release-signing checks in the overseer's environment.
   Verify: `:app:assembleRelease`, `:app:bundleRelease`, direct release packaging
   and aggregate/indirect release requests fail clearly without signing material
   before any distributable is produced. Repeat with missing/incomplete properties
   and missing keystore; include cached/up-to-date paths.
   Do not retrieve secrets, generate a key or run a positive local signed build.

7. [x] Include and pass the existing source guard as a T1 gate.
   Command: `python3 scripts/check-senior-rewrite.py`.
   Include `scripts/check-senior-rewrite.py` as a tracked T1 addition, correcting
   only the stale CountPill assertion to require `wrapContentWidth(unbounded = true)`,
   forbid `requiredWidth(IntrinsicSize.Min)`, and retain the soft-wrap and max-lines checks.
   Local result: exit 0, `Static guards passed`; CI is expected to run this gate green.
   Hosted CI results remain unverified locally; no runtime or test changes were made.
   Verify `quality-gate` fails for failed/cancelled/skipped/missing dependencies
   and succeeds only when every required result is exactly `success`.

8. [ ] Verify positive signing later in separately authorized Actions work.
   Verify: secret-restored temporary material signs the optimized release with
   the approved full certificate; no secret enters logs or report artifacts.
   This task authorizes no release workflow, dispatch or publication in T1.
