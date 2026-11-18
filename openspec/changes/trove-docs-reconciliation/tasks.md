# Tasks — trove-docs-reconciliation

> Historical change. [bitmere-releases](../bitmere-releases/proposal.md) defines the current GitHub Releases distribution, update channels, and signing contract.

Status: 2026-11-16. Documentation only; leave an uncommitted diff.

## Sixteen staging files

- [x] AGENTS.md — fix requestdl privacy, record discrepancy, Actions-only
  signing, planned updater and current design.
- [x] README.md — merge main content; repair signing/design/relative paths;
  add index, house-rules and testing links; preserve Modules/Security.
- [x] CHANGELOG.md — retain staged 0.1.0/0.1.1; add evidence-backed
  0.1.2–0.1.14 and Unreleased T1; no separate 0.1.7 release.
- [x] TESTING.md — read android-dev-flow; wrapper/toolchain checks,
  baseline 107 tests, lint 0 errors; no local release signing.
- [x] docs/ARCHITECTURE.md — check module paths/stack, requestdl flow,
  current design and historical mockup.
- [x] docs/DISTRIBUTION.md — Actions-only signing, clean install transition,
  parked Play sharing and explicitly unbuilt updater/mirror/token.
- [x] docs/MULTI-PROVIDER-RESEARCH.md — preserve research body verbatim,
  prepend dated playback correction and planned/shipped status.
- [x] docs/PROVIDERS.md — fix TorBox playback; preserve dated provider/API
  research facts, distinguish unshipped adapters.
- [x] docs/README.md — repair index, historical mockup and history link.
- [x] docs/SKILLS.md — verify custom skill names; scope Codex workspace-write
  notes to T2 and override generic signing guidance.
- [x] docs/llms-full.txt — baseline identity/history, requestdl, modern
  design, historical debug build, current signing and updater status.
- [x] docs/mockup-v6.html — retain body, add historical-only HTML comment.
- [x] llms.txt — baseline, provider playback/privacy, release signing,
  modern design and planned updater.
- [x] openspec/changes/trove-v1/proposal.md — preserve original intent with
  dated shipped-status and current-policy note.
- [x] openspec/changes/trove-v1/specs/trove.md — preserve historical scope,
  add playback/signing/design deviations.
- [x] openspec/changes/trove-v1/tasks.md — replace obsolete action list
  with delivered/pending status and dated distribution history.

## Disposition of all 27 local-only files

Sources remain untouched under /home/hatch/workspace/trove-app.
All twenty root briefs are excluded: working instructions with stale
implementation directions, not durable project documentation:

- Excluded: `blank-screen-brief.md` — working brief; stays local.
- Excluded: `blank-screen-brief-2.md` — working brief; stays local.
- Excluded: `blank-screen-brief-3.md` — working brief; stays local.
- Excluded: `compare-016-brief.md` — working brief; stays local.
- Excluded: `deepdive-brief.md` — working brief; stays local.
- Excluded: `dialog-brief.md` — working brief; stays local.
- Excluded: `draw-never-brief.md` — working brief; stays local.
- Excluded: `fix-plan.md` — working brief; stays local.
- Excluded: `fixes-017-brief.md` — working brief; stays local.
- Excluded: `layout-loop-brief.md` — working brief; stays local.
- Excluded: `layout-loop-brief-2.md` — working brief; stays local.
- Excluded: `play-modern-brief.md` — working brief; stays local.
- Excluded: `prefetch-brief.md` — working brief; stays local.
- Excluded: `rewrite-brief.md` — working brief; stays local.
- Excluded: `rewrite-review.md` — working brief; stays local.
- Excluded: `senior-rewrite-brief.md` — working brief; stays local.
- Excluded: `tabs-brief.md` — working brief; stays local.
- Excluded: `trove-v2-codex-brief.md` — working brief; stays local.
- Excluded: `ui-fixes-brief.md` — working brief; stays local.
- Excluded: `ui-fixes-2-brief.md` — working brief; stays local.

Read all seven diagnostic records; decisions below refer to source docs/:

| Source filename | Disposition and reason |
| --- | --- |
| 0.1.6-design-review.md | Excluded; design/playback review with pending device checks, not definitive blank-screen evidence. |
| blank-screen-round4-comparison.md | Excluded; candidate comparison explicitly says root cause not proven. |
| blank-screen-verification.md | Excluded; failed 0.1.9/0.1.10-era hypotheses and proposed experiments, no successful 0.1.13 verification. |
| scrollable-tab-row-diagnosis.md | Included verbatim at docs/history/blank-screen/scrollable-tab-row-diagnosis.md; concrete Material 3 intrinsic-query ordering defect and CountPill mitigation, with explicit source-only caveats. |
| senior-rewrite-plan.md | Excluded; implementation plan, not definitive diagnosis or device verification. |
| senior-rewrite-verification.md | Excluded; static rewrite checks, not successful first-frame/device verification. |
| verification-017.md | Excluded; earlier API/input/static checks, not blank-screen resolution evidence; 0.1.7 was not a separate release. |

- [x] Add docs/history/README.md with date provenance, diagnosis limits
  and the absence of a local successful 0.1.13 device-verification record.
  The supplied release evidence provides that user confirmation.

## T2 OpenSpec and verification

- [x] Add proposal.md, this tasks.md and specs/project-baseline/spec.md
  with ADDED/MODIFIED requirements, stable IDs and Given/When/Then scenarios.
- [x] Check CLI availability: no openspec executable installed. Strict CLI
  validation was not run; no package installation attempted.
- [x] Manual structure: proposal/tasks/spec present; ADDED/MODIFIED sections,
  four unique requirement IDs with SHALL statements, five scenarios each
  containing Given/When/Then. All local Markdown links resolve.
  Initial/final SHA-256 inventory: 20 added docs, only README.md modified,
  no deletions; all pre-existing code/config/version files unchanged.
  Research and mockup bodies retained after added top notes; diagnosis
  copied byte-for-byte. Git checks unavailable: mounted .git is empty,
  so no local commit history or git diff can be inspected. Release subjects,
  test/lint baselines and 0.1.13 device confirmation use supplied evidence.
- [ ] TARS: run build/tests/lint and any needed device checks. The 107-test,
  lint-zero baseline comes from supplied 0.1.14 evidence, not T2 execution.

Verification limits: .git is empty in this sandbox, so commit subjects and
PR history cannot be independently queried; changelog evidence is supplied
by the brief. Remote account/mirror/token state is also supplied, not queried.
No Gradle or signing executed. No code/config/workflow/version changed.
