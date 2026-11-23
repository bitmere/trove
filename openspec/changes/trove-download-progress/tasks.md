# Tasks — trove-download-progress

- [x] Phase 0 — Record the open questions as unverified (no live TorBox key or
  phone from this VM): which READY arm fires on real torrent rows; relay
  "Request Update" endpoint path/limits; `bypass_cache` semantics; missing-web
  cause; `/queued/getqueued` live shape. Proceed on code + docs evidence only.
  Static verification done against code + the OpenAPI spec; the relay endpoint
  and `/queued/getqueued` shape stay unverified.
- [x] Phase 1 — Status-mapping fix in `TorBoxProvider.toDownload()` + `rawState`
  field on `DebridDownload`; rewrite the bug-encoding test
  (`TorBoxProviderTest.kt:890-910`) deliberately; add the new mapping tests
  (§3.5 of the plan); verify the working 77% web row still maps DOWNLOADING.
  Committed `1d68cbc`.
- [x] Phase 2 — Foreground-only head polling in `DownloadsViewModel` (60 s ticks,
  visible tab page 0 only, head-only merge; start/stop rules per plan §4.2;
  stop on 429; suppressed while refresh/full sync runs). Add the
  `DownloadsViewModel` poll tests (fake clock / `TestScope`).
  Committed `3adc3d4`. Polling tests cancel the ViewModel scope in-body:
  the poll loop never idles, so runTest spins forever otherwise.
- [x] Phase 3 — Head-only `refreshFromUser()`; remove the implicit auto-traverse
  clause in `launchPage()` (audit every caller first); sort change keeps full
  traversal; search matches instantly against the loaded snapshot while
  `ensureFullSync()` runs in the background with a "Searching all downloads…"
  indicator. Rewrite the 0.1.40 refresh-traversal tests to the new contract.
  Committed `7af0966`. The head-reconcile merge applies only when rows below
  the head exist to retain, else a first load froze the paging cursor at 0.
  The "Searching all downloads…" indicator's Compose test needs a device to run.
- [x] Phase 4 — Optimistic insert: `onAdded(download)` inserts the returned
  row at the top of its kind tab; fresh server fields replace it by kind/id;
  drop after a second non-poll head fetch without it. Pending adds clear on
  provider changes. Add sheet and share flow carry the complete download.
  - Deliberate deviation from plan §6: only non-poll head fetches count misses
    (user refresh, foreground revalidation, and sort-change heads). Poll ticks
    still confirm server rows but never count misses: TorBox refreshes torrent
    list data every 600 s, so 60 s ticks would exhaust both lives too early.
  - `/queued/getqueued` read deferred: live response shape remains unverified;
    no queued endpoint implementation added.
  - Focused tests cover immediate insertion, server confirmation, first/second
    misses, >100-row head reconciliation, and poll miss exemption.
  Committed `b3369b9`.
- [x] Phase 5 — Notification: new package `ui/downloads/notifications/`
  (helper + foreground service), manifest permissions + service entry, Settings
  toggle default OFF following the `PreferencesStore` toggle pattern,
  per-download grouped notifications, updates on poll ticks with ≥1–2% change
  threshold, stop/cancel rules per plan §7.2. Unit tests for the helper.
  - Built as: `DownloadNotificationController` (interface + `ActiveDownload`
    primitives + NoOp default) + `DownloadNotificationHelper` (channel, pure
    content mapping, repost decider, build/cancel) +
    `DownloadProgressService` (START_NOT_STICKY foreground service, group +
    summary, silent cancel on completion) +
    `ServiceDownloadNotificationController` (Context-backed; permission check;
    startForegroundService only when not already running, plain startService
    for later SYNCs; ForegroundServiceStartNotAllowedException swallowed so
    a refused background start retries on the next foreground poll).
  - The ViewModel pushes the active set from EVERY loaded tab (not just the
    visible one); only DOWNLOADING rows and QUEUED just-added optimistic rows
    get notifications. Identical sets are not re-sent (VM-side dedup); the
    service re-posts only on >= 2% progress moves or speed/ETA text changes.
  - The notification toggle is NOT included in Settings backup (device-specific,
    permission-gated). The Bundle encode/decode for service intents has no unit
    test (no Robolectric tests exist in this repo); it is covered by the manual
    phone checks below.
  - Manual phone checks (cannot run from this VM): toggle on -> permission
    prompt appears once; toggle off -> service stops and notifications clear;
    3 simultaneous downloads -> 3 grouped notifications; tap a notification
    -> opens Downloads; toggle on with permission denied -> stays off with
    explanation; toggle on with a download running -> notification appears
    with the right title/text/progress and moves about once a minute.
  - Release-notes sentence for the new permissions: "Download progress
    notifications (optional, off by default) use the notification permission
    and a foreground service to show active download progress; the
    notifications render locally from already-fetched list data and no data
    leaves the device for them."
  - NOTE: Codex (Astra) hit the ChatGPT account usage limit during this phase
    (~16:45 IST, "try again at 2:48 PM"), so Phase 5 was implemented directly
    by the coordinator from the plan + verified code reads, with the same
    review + gates. Recommend a Codex review of the final diff once the
    limit lifts, before the PR.
- [x] Phase 6 — Nuvio ideas as mechanisms (no separate build; verification
  only): local-snapshot search landed in Phase 3; raw status + numeric
  progress separate landed in Phase 1 (`rawState`); the Phase 2 poll is
  per-tab by construction (other tabs' snapshots are never invalidated) and
  only re-fetches mylist — it never triggers a `requestdl`. Trove keeps its
  own offset paging, per-kind failure isolation, and TokenBucket + stop-on-429
  rate limiting. No Nuvio code copied (GPL-3.0 boundary).
- [x] Review fixes (2026-11-23, user decision "keep polling when notification
  is on") — Fix 1: `DownloadsViewModel.onBackground()` stops the 60 s poll
  loop only when the notification toggle is off (never stops the
  notification service, so last posted values freeze); `onDownloadsVisible()`
  restarts it; `MainActivity.onPause()` calls `onBackground()` and a
  `DisposableEffect` in `DownloadsScreen` covers leaving for Settings; the
  poll loop and `maybeStartPolling()` both re-check the toggle gate, so
  backgrounded + toggle off issues no further page calls while backgrounded
  + toggle on keeps polling. Spec + proposal updated: foreground-only when
  notification off, background polling intended when notification on.
  Fix 2: the second-miss drop branch now sets `error` to "Couldn't confirm
  the added download. Pull to refresh and try again." (existing snackbar
  path; no load/page error). Nit: stale `refresh()` KDoc corrected to
  head-only. Tests: three new poll-gate tests + one drop-feedback test.
- [x] Gates — full test suite 0 failures (801 tests: baseline 765 + 36 new),
  lint 0 errors (29 warnings + 1 hint, all pre-existing), Impeccable 0
  findings on changed UI files. No push, no PR.
  - Codex review of the final diff: NOT DONE — Codex (Astra) hit the ChatGPT
    account usage limit during Phase 5 and Phase 5 was implemented directly
    by the coordinator. Recommend a Codex review of the final diff once the
    limit lifts, before the PR is opened.
