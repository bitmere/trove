# Tasks: search placeholder wrap + Play dead after sort

- [x] Verify both root causes against a fresh sync of main @ 23987cae
      (placeholder: `DownloadsScreen.kt:316`; identity guard:
      `DownloadsViewModel.kt:790-793`; `loadFiles`/`playFile` cancellation
      paths)
- [x] Fix 1: placeholder single-line with ellipsis (`DownloadsScreen.kt:316`)
- [x] Fix 2: `isCurrentDownload` compares structurally (`==`), not `===`
      (`DownloadsViewModel.kt:790-793`) — key-only `(kind, id)` was tried
      first and broke the account-switch guardrail test, so the fix is
      structural equality, which keeps both behaviours
- [x] Fix 2b: clear `resolvingDownload` (and `resolvingFileId` in
      `playFile`) in a `finally` in `loadFiles`/`playFile`, only when it
      still matches the call's key; `loadFiles` must not clear a lookup
      that handed off to playback
- [x] Regression test (ViewModel): Play works on a pre-sort instance after
      the sort republishes fresh instances with the same `(kind, id)`
- [x] Regression test (ViewModel): a cancelled file lookup / cancelled
      playback leaves `resolvingDownload` cleared
- [x] Regression test (Robolectric compose): the search placeholder stays
      one line tall with Reset visible at 360dp width (the user's phone
      is ~354dp wide)
- [x] Focused tests green (33 tests, 0 failures), including the
      pre-existing account-switch guardrail; both new behaviour tests
      mutation-checked (fail on the unfixed code: placeholder 72.0dp
      tall pre-fix; sort test fails under `===`)
- [x] Full `./gradlew test lint assembleDebug` green: **586 tests, 0
      failures, 0 errors** (baseline 582 + 4 new); **lint 0 errors**,
      25 warnings (baseline 24 + 1 pre-existing Robolectric notice)
- [x] Ran the Impeccable detector over the changed UI files: 0 findings
      (regex-only on `.kt`, known undercount — no new UI surfaces, tokens
      or layout primitives were introduced, so there is nothing for the
      design passes to flag)
- [ ] Report the diff back for review (no PR, no merge, no release)
