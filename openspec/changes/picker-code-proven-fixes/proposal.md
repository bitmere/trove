# Picker code-proven fixes

## What was found

Astra's bug hunt on the blank details sheet
(`~/workspace/trove-blank-sheet-model/report.md`, §2; Codex session
`01a1fe75`) proved five further picker bugs in code, needing no
device confirmation. All line numbers are main @ `9861d658`
(Trove 0.1.36; the app code equals `5e38f4d8`, the base of this
change).

1. **Slugged TMDB links fall through to title search (P1).**
   `TmdbResolver.kt:150-152`: the link regex
   `^(?:https?://)?(?:www\.)?themoviedb\.org/(movie|tv)/(\d+)(?:[/?#].*)?$`
   permits only `/`, `?`, `#` after the id — not `-` — so
   `https://www.themoviedb.org/movie/550-fight-club` becomes a
   title query and typically yields `NO_MATCH`, contradicting the
   comment's promise of tolerance for "anything after the id".
2. **Delete bypasses manual-search cleanup (P1).**
   `DownloadsViewModel.requestDelete` (`~1330-1332`) only sets
   `deleteConfirm` and clears `filePicker` — no job cancel, no
   `SEARCHING` status clear — unlike `dismissFilePicker` (`~1313`)
   which does both. Deleting, cancelling the deletion, and
   reopening before the search completes leaves the field disabled
   and can publish the stale result into the new sheet (the
   completion guard checks only `(kind, id)`).
3. **Candidate changes are silently dropped during resolution (P1).**
   `changeMetadataOverride` returns silently while
   `ref in overrideEdits` (`~1138`), but the candidate row buttons
   stay enabled (`DownloadDetailsSheet.kt:225`); tapping another
   candidate or Clear during a slow resolve is ignored with no
   visible effect.
4. **Search feedback stays attached to edited input (P2).**
   Field edits change only local text
   (`DownloadDetailsSheet.kt:244`); `FOUND`/`NO_MATCH`/`INVALID`
   keeps rendering from view-model status (lines 258-270), so
   feedback for query A still shows beside edited input B.
5. **Manual draft is lost on activity recreation (P2).**
   The input uses `remember`, not saved state
   (`DownloadDetailsSheet.kt:89`); rotating with an unsubmitted
   draft resets it even though the view model still holds the open
   download.

## Fix (this change)

- Accept a `-` slug (and any `-`-led suffix) after the numeric id
  in a themoviedb.org link; keep the positive-id and type
  validation, and keep bare ids and titles unchanged.
- Share the manual-search cancellation/status cleanup across
  every picker-exit path: `requestDelete` cancels the in-flight
  manual search and clears that download's status, exactly as
  `dismissFilePicker` does.
- Expose a per-download resolving-selection state from the view
  model and disable the candidate row buttons (pick + Clear) while
  a resolve is in flight, so a tap during resolution cannot be
  silently dropped.
- Clear the manual search status when the input text changes, so
  feedback never describes a query the field no longer holds.
- Hold the manual draft in `rememberSaveable`, keyed by download
  identity, so rotation keeps an unsubmitted draft.

Out of scope: Bug 1 (blank sheet — the diagnostic build gates any
inset change; `DownloadDetailsSheet.kt:95-110` stays exactly
as-is) and Bug 6 (unkeyed lazy items — focus loss needs on-device
confirmation; follow-up). No change to the exact-title gate, the
picker's pick path, or the auto/manual race behaviour from PR #62.
