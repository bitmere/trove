# Trove workflow

- This is an independent repository. Do not edit or depend on sibling
  checkouts (matrix-launcher-compose, personal-home-tv) to implement
  features here.
- Trove is a phone app, not a TV launcher. The 2026 modern Material 3 UI
  has one primary destination (Downloads), Torrents/Web/Usenet as top subtabs, search
  field at the top of the list, floating liquid-glass action cluster
  (Add + Search) bottom-right, Settings behind the avatar. No bottom
  navigation — Settings is not a primary destination and Add is an
  action, not navigation.
- core is Android-free and reusable: provider abstraction, HTTP
  transport, rate limiting, share classification, models. app contains
  Android adapters, Compose feature screens, and Keystore credential
  storage. Keep I/O out of composition and off the main thread; no DI
  framework for simple wiring; no Classic Views, RecyclerView, or
  AndroidView wrappers.
- Release signing is secrets-only in GitHub Actions; local release builds
  cannot sign. The Settings updater checks GitHub Releases manually.
- Version comes from version.properties. Increase versionCode for every
  newly installed artifact. The store title is "Trove – Debrid Library",
  package app.trove.debrid — never rename either.
- Collect edits before building. Focused tests first, then the full
  suite; lint once before a PR. Do not edit app/core sources during
  builds. A passing JVM suite is not proof of on-device behaviour
  (player chooser, share target, encrypted storage) — verify on a phone.
- Play resolves a per-file TorBox `requestdl` direct link for the selected
  download kind and real file ID, then hands it to the Android `ACTION_VIEW`
  player chooser with a concrete video MIME derived from the filename.
  This per-file bearer link goes only to the player the user picks, never
  to logs, share targets or error messages. The streaming API
  (`createstream`/`getstreamdata`) is plan-restricted on Essential and is not used.
  Reconciliation (2026-11-16): staged docs said createstream-only;
  shipped code uses requestdl. T2 corrects the docs to match the code.
- The TorBox API key is entered by the user in the app on their phone
  and stored encrypted on-device. It is never in code, logs, backups, or
  this repo. Credentials, SDK paths, signing material, and account dumps
  stay in ignored artifacts.
- Design v6 (`docs/mockup-v6.html`) is historical only, superseded by the
  2026 modern UI shipped in 0.1.5. Design: decide tokens first; refuse
  purple gradients, glassmorphism
  (except the floating action cluster, which the user explicitly asked
  for), bento grids, Inter-only type, and eyebrow labels by default.
  See docs/SKILLS.md for the design skill.
- Keep the footprint small on low-memory phones: bounded snapshot blur
  only (API 31+ and non-low-RAM, scrim fallback), no new blur library,
  no new dependency without a reason. Serve old and new phones.
- Spec-driven development: user-facing changes travel with an OpenSpec
  change under openspec/changes/<change>/ (proposal.md, tasks.md,
  specs/). Tiny test-only cleanups go diff-review only.
- Never merge, release, push, or open a PR without the user's explicit
  approval for that exact action. Show the planned changes/diff for
  review before compiling; fix anything different first, then compile.
