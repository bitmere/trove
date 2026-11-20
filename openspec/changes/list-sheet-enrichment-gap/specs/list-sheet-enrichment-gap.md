# List-vs-sheet enrichment gap

## Hydration reconciliation

- A reconcile step enqueues every currently loaded row of the active
  tab that holds no poster URL in state — no metadata at all, or
  posterless metadata — through the existing `enqueueHydration`
  guards. Rows that already hold a poster URL are not enqueued.
- The reconcile runs when a snapshot is shown (`restoreLibrary`
  publishes restored rows), when a page load fails (`fetchPage`
  returns `FAILED`, for that tab's loaded rows), and when a tab
  becomes active (`setTab`, for that tab's loaded rows, whether or
  not the tab is in `cachedTabs`).
- The reconcile does not change queue dedup: a row already queued
  with the same download instance is not re-queued, and a row under
  an override edit is skipped. It does not change batching (4),
  viewport priority, or the `current()` account/name/order guards in
  `hydrateRows`.
- Results dropped by cancellation or supersede are recovered by the
  next reconcile, not by flushing inside a cancelled worker: a
  cancelled hydration worker must not publish. The offscreen buffer
  is still flushed at 10 entries / 200 ms and at the end of a
  run that was not superseded.
- A reconcile never clears or replaces metadata, enrichment,
  pending posters, or overrides for any row.

## Retry eligibility

- A loaded row is retry-eligible when it has no metadata or
  posterless metadata, is not under an override edit, and its
  download is still loaded with the same name.
- Every reconcile re-presents retry-eligible rows to the resolver.
  Whether that re-presentation performs network work is decided by
  the resolver's own miss TTL (1 h) and failure TTL (60 s): a
  re-presentation inside the TTL is absorbed by the cache with no
  network call.
- There is no background timer or periodic retry job. Retries ride
  on reconcile triggers (snapshot show, page load/fail, tab
  activation, explicit refresh, sheet open).

## Explicit-refresh bypass scope

- An explicit user refresh (`refreshFromUser`) marks its refresh
  version as bypassing the metadata miss cache. Every page load
  under that version carries the bypass: the page-0 replace, later
  appends of the same tab, and other tabs' first page loads under
  the same version.
- A newer refresh version clears the bypass: a sort change, a new
  refresh, or a provider switch starts a version that does not
  bypass unless the user explicitly refreshes again.
- The poster-bearing skip in `enqueueHydration` is unchanged: a row
  that already holds a poster URL is skipped unless an explicit
  refresh finds its cooldown inside the window.
- The resolver's network semaphore (4) and per-query lane dedup
  still bound the work; the bypass only changes which cached misses
  are re-resolved.

## Mounted row observes a shared image load

- The poster loader publishes a monotonically increasing
  load-generation counter, bumped whenever a poster load completes
  successfully and a bitmap is stored (in memory and/or on disk),
  regardless of which caller started the load (row, sheet, or
  prefetch). Failures do not bump the counter.
- A mounted row's load effect is keyed on its URL, its retry count,
  and the load generation. When the generation changes and the
  row's slot holds no bitmap for its current URL, the row
  re-attempts the load for that URL.
- The re-attempt reads the shared in-memory bitmap cache first. When
  the bitmap is already cached (for example the sheet just decoded
  it), the row takes it with no network call and without disturbing
  prefetch. Only when the bitmap is absent does the re-attempt run
  the normal visible-load path, which honours the image miss TTLs.
- An image failure is reported to the failure callback at most once
  per (URL, retry) failure episode: a generation-triggered re-run
  that fails again while the row is already showing the failed state
  does not report the same failure twice.
- The success, failure, manual-retry, and last-good-poster
  behaviours are unchanged: a new bitmap replaces the slot only
  when it decodes, and a failed load keeps the previously decoded
  bitmap.

## TMDB-primary artwork fallback

- In the resolver's TMDB-primary branch, when the primary result
  has no usable poster and the Cinemeta by-ID backfill supplies no
  poster either — backfill absent (failed, or skipped for a missing
  type) or backfill present but posterless — the resolver falls
  through to the exact-year Cinemeta artwork title search, the same
  strict `lookup(artworkQuery(query, primary), exactYear = true)`
  already used when no primary id exists.
- The title search keeps its strict identity checks: normalized
  title match, exact year, single exact match, and detail id/type
  verification in `lookup()`. A wrong-year or ambiguous title search
  still yields no poster.
- When the artwork title search finds a poster, it is used with
  `TMDB_CINEMETA_POSTER` provenance and the TMDB-resolved identity
  is kept (title, year, IMDb id, overview, episodes from the primary
  / backfill as today).
- When the title search finds no artwork, the result is unchanged
  from today (posterless primary, or posterless primary enriched by
  a posterless backfill).

## Focused regressions

- Snapshot restore + page-0 failure: restored rows are enriched even
  when the revalidating page 0 fails; the metadata resolver is
  called for the restored rows.
- Cancel-before-offscreen-flush: hydration results computed for
  offscreen rows and then cancelled (refresh / sort / tab change)
  are re-enqueued by the next trigger and eventually published,
  not lost.
- Sheet open with absent metadata: opening the sheet for a row with
  no metadata and no enrichment outcome enriches it, and the row
  shows the poster after the sheet closes.
- Sheet open with posterless metadata: the sheet does not enqueue a
  new lookup for the row (the early exit stays), and the row's
  poster state after the sheet closes matches the reconciled state.
- Pull-to-refresh bypass: after an explicit refresh, rows beyond
  page 0 of the active tab and rows in another tab are re-resolved
  with the miss bypass; an ordinary refresh does not bypass.
- Artwork gap, both resolver orders: with a TMDB key configured
  (TMDB-first), the fixture above yields the title-search poster;
  without a key (Cinemeta-first), the TMDB-primary branch is not
  taken and behaviour is unchanged.
- Failure-then-same-URL-success with no remount: a mounted row
  whose image load failed shows the bitmap once the same URL loads
  successfully through the shared loader, without scrolling off/on
  or tapping retry.
