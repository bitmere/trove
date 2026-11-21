# Manual TMDB search races in the match picker

## What the user reported

After updating to Trove 0.1.35, the manual TMDB link row in the
details-sheet picker (shipped in 0.1.34, PR #56) does not work for
them: "it is opening a page where I cannot see what I was typing, I
am unable to type or see the results from TMDB search, it's not
working." Debugged with Codex (CLI 0.169.0); full Codex output in
`~/workspace/codex-tmdb-debug-answers.log`.

## Verified current state

All line numbers are main @ `5c685973` (Trove 0.1.35).

- The manual row is a standard Material 3 `OutlinedTextField`
  (`DownloadDetailsSheet.kt:242-249`): value in sheet-local
  `manualTmdbInput`, `onValueChange` assigns each edit, Search
  calls `onManualTmdbSearch(manualTmdbInput)`. The field is
  `enabled = !searching` and the button
  `enabled = manualTmdbInput.isNotBlank() && !searching`
  (`DownloadDetailsSheet.kt:239-255`). Theme contrast is correct
  (default `onSurface` on `surfaceContainerLow`); nothing in code
  makes typed text invisible, and focus/keyboard behaviour cannot
  be determined from code alone — that part needs a screen
  recording from the user's phone.
- The exact normalized-title gate is specified behaviour, not a
  bug: a title search only keeps TMDB hits whose normalized title
  (or original title) equals the normalized query
  (`TmdbResolver.kt:276-290`), so a partial title yields
  `NoMatch`, and a bare numeric id is `Invalid` when the parsed
  download type is null (ordinary movie names)
  (`TmdbResolver.kt:104-122`). A themoviedb.org link bypasses the
  gate and resolves its id directly.

## Two confirmed races (found in code, both can produce the report)

1. **Late automatic load overwrites a manual search.**
   `searchManualTmdbLink` (`DownloadsViewModel.kt:1087`) never
   cancels the automatic candidate job (`metadataCandidateJob`,
   started by `loadCandidatesFor`, `DownloadsViewModel.kt:1019`).
   The automatic job publishes unconditionally when it finishes:
   it replaces `candidates[key]` and removes the manual status
   (`DownloadsViewModel.kt:1051-1060`). A slow automatic load can
   therefore land after a manual `Found` and replace the manual
   results, erase a `NoMatch`/`Invalid` message, or clear
   `SEARCHING` while the manual request is still in flight.
2. **Dismissing mid-search leaves the field stuck disabled.**
   `dismissFilePicker` (`DownloadsViewModel.kt:1300`) clears
   `filePicker` but neither cancels `manualTmdbSearchJob` nor
   clears `manualTmdbSearch[key]`. The manual job's completion
   guard then returns early without clearing `SEARCHING`
   (`DownloadsViewModel.kt:1101`). On reopening the same download,
   if the automatic load returns no candidates (the exact case
   where the manual row is needed), nothing clears the stale
   `SEARCHING`: the field and the Search button stay disabled
   indefinitely — the user taps the field and cannot type.

## Fix (this change)

- A manual submit takes ownership of the picker: it cancels the
  in-flight automatic candidate job. A stored-override resolution
  cancelled this way re-runs on the next open.
- Dismissing the picker cancels any in-flight manual search and
  clears its status for that download; the manual job's completion
  guard also clears the status when it finds the picker gone, so
  no stale `SEARCHING` can survive a close (including delete).
- A manual `Found` now reports a `FOUND` status instead of
  silently clearing the feedback, and the sheet shows a "found —
  pick a match from the list above" line under the Search button:
  results render above the manual row, and without this the
  spinner simply disappears and the user sees nothing change.
  `FOUND` clears on the next submit, on dismiss, or when the
  picker reloads.
- The no-match message now says to try the full title or paste a
  themoviedb.org link, so the exact-title gate stops looking like
  a broken search.

Out of scope: auto-scrolling the candidate list into view on a
manual `Found` (needs Compose UI-test coverage; follow-up), and
any change to the exact-title gate (specified behaviour).
