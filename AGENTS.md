# Trove workflow

- Trove 0.1.21 is published in the public bitmere/trove GitHub repository
  with no mirrors. The updater reads GitHub Releases through the GitHub API.
  GitHub Actions signs releases with the bitmere keys held in Actions secrets.
  The in-app updater was verified on a device for 0.1.21 after the install
  guard was fixed.
- Repo rulesets block direct pushes to main. Every change, including version
  bumps, goes through a PR with green CI and a squash merge. Never merge,
  release, push, or open a PR without explicit user approval for that exact
  action. Show the planned diff for review before compiling and address the
  review before starting the build.
- The application ID is `app.trove.debrid` and the store title is
  “Trove – Debrid Library”. Keep both stable. Read the version from
  `version.properties` and increase versionCode for every newly installed
  artifact.
- Trove supports TorBox, Real-Debrid, AllDebrid, Premiumize, Offcloud, and
  Debrid-Link. Settings has six groups: Providers, Library, Playback,
  Appearance, Updates, and Backup.
- Trove is a Material 3 phone app with one primary destination, Downloads.
  Keep the Torrents, Web, and Usenet subtabs, search at the top of the list,
  the floating Add and Search controls at bottom right, and Settings behind
  the avatar. Keep the UI free of bottom navigation.
- TorBox playback resolves a per-file `requestdl` link for the selected
  download kind and actual file ID, then hands it to the Android
  `ACTION_VIEW` player chooser with a video MIME type derived from the
  filename. The bearer link goes only to the selected player. Keep it out
  of logs, share targets, and error messages. The plan-restricted
  `createstream` API is unused.
- Keep `core` Android-free and reusable. Keep Android adapters, Compose
  screens, and Keystore credential storage in `app`. Keep I/O out of
  composition and off the main thread. Use simple wiring and Compose
  throughout, without a DI framework, classic Views, RecyclerView, or
  AndroidView wrappers. Keep the repository independent of sibling checkouts.
- Users enter provider credentials on-device. Keep them encrypted and out of
  code, logs, and backups. Keep SDK paths, signing material, and account dumps
  in ignored artifacts.
- Decide design tokens first. Design tokens are in `docs/SKILLS.md`. Avoid
  purple gradients, glassmorphism outside the floating action cluster, bento
  grids, Inter-only type, and eyebrow labels. Preserve functionality and a
  small footprint on older and low-memory phones. Use bounded snapshot blur
  on API 31+ outside low-RAM devices, with a scrim fallback. Keep dependencies
  small and justify additions.
- Collect edits before building and keep sources stable during builds. Run
  focused tests first, then the full suite, and run lint once before a PR.
  Verify the chooser, share target, and encrypted storage on a phone.
- Use OpenSpec for user-facing changes. Add a proposal, tasks, and specs
  under `openspec/changes/<change>/`. Tiny test-only cleanups need diff review.
  Take design inspiration from GPL-licensed sources, but write all code fresh.
