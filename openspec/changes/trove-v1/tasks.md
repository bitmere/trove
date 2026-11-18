# Tasks — trove-v1 (historical reconciliation)

> Historical change. [bitmere-releases](../bitmere-releases/proposal.md) defines the current GitHub Releases distribution, update channels, and signing contract.

Status: 2026-11-16, current baseline 0.1.14 / code 14. Original v1
shipped as 0.1.0 on 2026-11-15. This records delivered scope rather
than giving instructions to rebuild an obsolete scaffold. See
[spec deviations](specs/trove.md#deviations) and [changelog](../../../CHANGELOG.md).

- [x] Proposal/spec and app/core scaffold, version catalog, bundled fonts,
  version.properties: present in the checkout.
- [x] Android-free provider interface, models, transport, rate limiter,
  TorBox adapter, share classifier and credential-store abstraction.
- [x] TorBox mylist, cache checks, hosters, account validation and deletion.
  Current torrent creates are synchronous; web/Usenet creates are async.
- [x] Downloads, Add/share target and encrypted Settings delivered in v1.
  Share handling lives in MainActivity, not a separate ShareActivity.
- [x] Play delivered; fixes in 0.1.2/0.1.3, requestdl path in 0.1.5,
  loading/sheet dismissal and concrete filename-derived MIME in 0.1.6.
  Per-file bearer links go only to the selected ACTION_VIEW player,
  never logs, share targets or error messages. Streaming is unused
  because it is plan-restricted on Essential.
- [x] Original v6 intent superseded: 0.1.4 introduced a Material 3 rewrite;
  0.1.5 shipped the 2026 modern Material 3 UI. Downloads is the primary
  destination, no bottom nav, floating liquid-glass action cluster,
  Settings behind the avatar. mockup-v6.html is historical only.
- [x] Senior rewrite shipped in 0.1.8 (0.1.7 work folded into it).
- [x] Blank-screen diagnosis across 0.1.9–0.1.12, CountPill mitigation
  in 0.1.13; user confirmed working on 2026-11-16. Diagnostics removed
  in 0.1.14. See diagnostic history for the source-review limits.
- [x] Recorded baselines: 0.1.0 — 37 JVM tests; 0.1.1 — 50;
  0.1.14 — 107; lint 0 errors for those baselines. Not rerun by T2.
- [x] Historical distribution intent: v1 used debug signing; 0.1.14
  was also debug-signed.
  T1 supersedes that policy: secrets-only Actions signing, no local
  release signing and no version change. Obsolete local release-signing
  steps are withdrawn.
- [ ] Play internal sharing remains parked (no Play Console account).
- [ ] Other provider adapters remain planned. See bitmere-releases for the shipped manual updater.

Build/device verification for this docs reconciliation belongs to TARS;
no release, publication or version change is part of this task list.
