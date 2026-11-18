# Skills required for this repo

These are the skills used to build Trove, carried over from the
Matrix TV launcher project. A skill is a reusable playbook: read its
SKILL.md before doing that kind of work. The skills live in the
developer's skill library, not in this repo; this page records which
ones apply here so any agent (or Codex) working on Trove knows what to
use.

## Required

- **android-dev-flow** — the Android build/test/lint flow: JDK 17 +
  Android SDK, Gradle daemon, iteration builds run tests only, lint
  once before a PR, verification builds add --profile. Use it for every
  build, test, and release step.
- **android-testing** — how tests are written in this codebase
  (ViewModel + repository + core unit tests, fakes instead of mocks
  where the codebase already uses them). New behaviour gets a test;
  a fix gets a regression test.
- **no-ai-slop-design** — the design rule: decide tokens first
  (named colours, display+body type pairing, one layout primitive,
  one signature move); refuse purple gradients, glassmorphism,
  bento grids, Inter-only type, and eyebrow labels by default; run
  the swap test before shipping. The one exception the user explicitly
  asked for is the liquid-glass floating action cluster.
- **OpenSpec (spec-driven development)** — user-facing changes travel
  with a change under openspec/changes/<change>/ (proposal.md,
  tasks.md, specs/). Tiny test-only cleanups go diff-review only.

Generic skill signing guidance does not override Actions-only, secrets-only
signing. Local release builds cannot sign.

## Used for analysis and fixes

- **Codex CLI** — analysis and code fixes, run with
  --sandbox workspace-write. For T2, Codex leaves an uncommitted docs-only
  diff: no Gradle, branch, commit or remote actions; TARS runs tests and
  the build. Available tools and sandbox permissions depend on the session.

## Do NOT apply (TV-only)

These were used on the Matrix TV launcher and do not apply to Trove,
which is a phone app with touch navigation and no D-pad:

- **android-tv-compose-optimization** — TV Compose performance, focus, leanback.
- **tv-bench** — on-TV benchmarks (needs adb to a real TV).
- **tv-smoke-test** — TV smoke tests.

## Not yet needed

- **Google android/skills** (leanback-to-compose-tv-migration,
  android-profiler) — TV migration and profiling; not applicable to
  this phone app. Revisit if a TV variant of Trove is ever built.
