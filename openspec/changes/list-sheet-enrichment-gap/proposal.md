# List rows skip the enrichment fallback the details sheet runs

## What the user asked for

On 0.1.31 the details sheet shows a poster and enrichment for a
download, but the list row for the same download stays blank — only
previously-matching titles show posters in the list. The user asked
to fix all five items of the plan: reconcile hydration against
loaded rows, define retry eligibility and carry the explicit-refresh
bypass further, make a mounted row observe a shared image load, close
the TMDB-primary artwork gap, and add focused regressions.

## Verified current state

- Row and sheet read the same shared state through the same
  selector (`DownloadsUiState.posterUrl`, `PosterPrefetch.kt:8-9`),
  so there is no sheet-specific fallback to copy to the list. The
  split comes from what *triggers* a lookup and what publishes its
  result.
- `restoreLibrary` (`DownloadsViewModel.kt:312-327`) publishes
  restored rows and calls only `schedulePreparation` — no
  `enqueueHydration`. If the revalidating page 0 then fails,
  `fetchPage` returns `FAILED` with no enqueue, and the restored
  rows stay on screen with enrichment never scheduled.
- Appends enqueue only newly seen IDs (`fetchPage`, `:601`); scrolling
  never enqueues (`updateVisibleRows` only stores `visibleRowKeys`,
  `:376-377`).
- `hydrateRows` (`:712-810`) drops results on refresh/sort/tab
  cancel: `cancelHydration` clears the queue (`:680-684`); `publish
  = current()` drops superseded results; the offscreen `pending`
  buffer is flushed only when it reaches 10 entries or 200 ms elapse,
  and the final flush runs only `if (version == refreshVersion)`
  (`:812`) — on cancellation the whole buffer is lost. Nothing
  re-enqueues those rows afterwards except a fresh page/tab/refresh
  trigger.
- The sheet recovers such a row because `loadCandidatesFor` (`:936`)
  enqueues a row with neither metadata nor enrichment outcome, and
  the `filePicker` priority promotion (`:758`) publishes its result
  immediately instead of buffering it offscreen.
- The 1 h miss cache (`MediaMetadata.kt:209,250-252,411-412`)
  absorbs normal refreshes; only pull-to-refresh bypasses it, and
  only for page 0 of the active tab (`DownloadsScreen.kt:421` →
  `refreshFromUser()`, `DownloadsViewModel.kt:215`, `:601`).
- A mounted row owns its own `rememberPoster` slot; its load effect
  is `LaunchedEffect(validUrl, retry)` (`MetadataPoster.kt:229-248`)
  and re-runs only on URL change, manual retry tap, or remount. A
  failed row load records a miss (60 s non-transient, 1 s transient,
  `PosterRetry.kt:6-7,21-24`). The sheet mounts a fresh effect that
  succeeds once the miss expires, while the mounted row stays blank.
- When TMDB primary returns an IMDb id but no poster and the Cinemeta
  by-ID backfill is also posterless, the exact-year Cinemeta artwork
  title search is gated on `primaryId == null`
  (`MediaMetadata.kt:332`) and never runs.
- The sheet's candidate search (`candidates()`,
  `MediaMetadata.kt:470-479`) bypasses all gates (no cache, no miss
  gate, year ignored); the list only ever runs `resolveOutcome`
  (strict title+year gate, 1 h miss cache).

## Proposed change

- Reconcile hydration against the rows actually loaded whenever a
  snapshot is shown, a page loads or fails, or a tab becomes active:
  enqueue loaded rows that hold no poster, using the existing
  enqueue guards (override edits, account/name checks, dedup,
  viewport priority, batches of 4).
- Define retry eligibility for posterless / no-match rows: such a row
  is re-presented to the resolver on every reconcile, and whether
  that re-presentation performs network work is decided by the
  resolver's miss/failure TTLs. No background timer — retries ride
  on reconcile triggers, which keeps the footprint small and avoids
  hourly background network work while the app is idle.
- Carry the explicit-refresh bypass to every page load under that
  refresh's version (page-0 replace, later appends, other tabs'
  first page loads), not just page 0 of the active tab.
- Make a mounted row observe a successful shared image load for its
  current URL (including one the sheet started): the poster loader
  publishes a load-generation counter bumped on every successful
  bitmap store, and the row's load effect re-attempts when the
  generation changes and its slot holds no bitmap for the URL. The
  re-attempt reads the shared in-memory bitmap cache first, so no
  network runs when the sheet already decoded the image.
- Close the TMDB-primary artwork gap: when TMDB supplies an id but
  no poster and the by-ID backfill supplies no poster either, fall
  through to the exact-year Cinemeta artwork title search, keeping
  the strict identity checks in `lookup()`.
- Add focused regressions for snapshot restore + page-0 failure,
  cancel-before-offscreen-flush, sheet open with absent vs posterless
  metadata, pull-to-refresh bypass beyond page 0 / other tabs, both
  resolver orders, and failure-then-same-URL-success with no remount.
  Assert what the *row* shows after the sheet closes.

## Non-goals

- No change to the strict automatic title+year match gate, the
  1 h miss TTL, or the candidate picker's bypass behaviour.
- No background timer or periodic retry job.
- No change to the sheet's candidate picker or override flows.
- No version bump, changelog entry, PR, merge, tag, or release.
