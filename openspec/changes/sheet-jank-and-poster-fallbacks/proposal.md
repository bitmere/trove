# Sheet jank + poster fallbacks (11 items, 0.1.28 tree)

## What the user asked for

On 2026-11-20 ~12:28 IST the user reported, with a phone video, that
the download details sheet janks up and down continuously and that
posters show for only a few items, asking whether the metadata for a
file is not enough or how TMDB search can be improved with fallbacks.
On 2026-11-20 ~12:46 IST the user approved "Fix all of them" for the
11-item fix list from the investigation.

## Verified current state (0.1.28, main @ acfe67dc)

### Issue 1 — details sheet jank

- Frame analysis of the video (47 frames @ 10 fps): the sheet
  translates as a rigid whole between two positions ~30–35 dp apart,
  alternating at ~5 Hz, continuously for the whole 4.65 s clip — it
  never settles. No touch input visible. Sheet content is static.
- The sheet is `ModalBottomSheet` with
  `rememberModalBottomSheetState(skipPartiallyExpanded = true)`
  (`DownloadsScreen.kt:223`), shown when `state.filePicker != null`
  (`DownloadsScreen.kt:474`). Content is a `LazyColumn`
  (`DownloadDetailsSheet.kt:69-73`).
- Most likely cause: the sheet follows a moving `Expanded` anchor
  (M3 1.4.0 `ModalBottomSheet` + offset-dependent inset feedback on a
  nearly-full-height sheet), plausibly driven by `hydrateRows`
  emissions (~5 Hz, every 10 successes / 200 ms). **Unproven** until
  an on-device trace correlates flush timestamps with reversals.
- Ruled out: poster retry loop (the sheet has no poster at all —
  `DownloadDetailsSheet.kt` has no `MetadataPoster`/`Image`/
  `posterUrl`); conditional content toggling in the sheet (metadata
  present, files present, status READY, no error row); `show()`
  re-running on every emission (effect keyed to the remembered
  `SheetState`); predictive back / IME; `animateContentSize`
  (imported in `DownloadsScreen.kt:6` but never used — dead import).
- The sheet scaffolding is not new: `skipPartiallyExpanded` dates to
  0.1.18. PR #36 restyled the sheet but did not change the
  ModalBottomSheet setup, so the jank mechanism likely also exists on
  0.1.27 (video recorded 12:28 IST, 0.1.28 went live ~12:24 IST, so
  the phone was very likely still on 0.1.27).

### Issue 2 — posters only for a few items

- The video's title, "The Amazing Spider-Man" (2012), proves the
  lookup works: the sheet shows full Cinemeta data (synopsis,
  2012 · Action, Sci-Fi · 136 min · IMDb 7.0, Marc Webb, cast). The
  poster is a separate path: it comes only from Cinemeta's `poster`
  field via `publicPosterUrl` (`MediaMetadata.kt:270`), and the row
  poster is a separate load (`MetadataPoster.kt:133-142`, fixed
  48x72 dp, neutral placeholder when no bitmap).
- `publicPosterUrl` (`MediaMetadata.kt:108-113`) rejects anything
  that is not plain `https` with a host — no query string, no
  fragment, no userInfo, anything containing `requestdl`. A Cinemeta
  `poster` value that is `http:`, relative, or signed/query-string is
  silently dropped while all the text metadata still displays.
- **TMDB is never consulted for a poster-only gap.**
  `lookupAndStore` (`MediaMetadata.kt:180-193`): `tmdbResult` is
  computed only `if (result == null)` — Cinemeta hit ⇒ TMDB skipped,
  even with a valid key. Confirmed by test `cinemeta hit never calls
  TMDB` (`MediaMetadataTest.kt:480+`).
- **A posterless success is frozen for 180 days.** Disk cache
  (`MetadataDiskCache.kt:14,18-19,25`): 512 entries / 2 MB / 180-day
  TTL / 128 KB per entry, successes only. A fresh disk entry
  short-circuits at `MediaMetadata.kt:147-149` with no revalidation
  and no TMDB attempt.
- Misses/failures: in-memory miss TTL 1 h, failure TTL 60 s
  (`MediaMetadata.kt:128-130,198-201`); misses never written to disk;
  nothing schedules a retry. In-memory cache is process-scoped, so a
  restart re-resolves misses — but a miss cached before the TMDB key
  was added stays a miss for up to 1 h in-process.
- Poster fetch on a slow link: `PosterLoader`
  (`MetadataPoster.kt:44-127`) uses default OkHttp timeouts; failed
  URLs miss-cached 60 s; `loadPosterWithRetry` (`PosterRetry.kt:5-19`)
  does up to 3 attempts with a 60 s pause — a poster can take ~2 min
  of continuous row visibility to appear, and scrolling away / opening
  the sheet / a refresh restarts the budget. While
  `state.refreshing` is true, poster loading is suppressed entirely
  (`MetadataPoster.kt:133-142`). `MetadataPoster`'s
  `LaunchedEffect(url, metadata, refreshing)` restarts when the
  metadata instance changes.
- Hydration throughput: `fetchPage` publishes 100-row pages then
  `hydrateRows` resolves sequentially, one row at a time, flushing
  every 10/200 ms (`DownloadsViewModel.kt:545,624-665`); any
  refresh/sort/tab-switch bumps `refreshVersion` and abandons the
  pass. Rows below the fold may never be reached in a session.
  Resolver `Mutex` serialises all metadata traffic including network
  I/O (`MediaMetadata.kt:140-162`).
- Worked example — the Spider-Man name parses correctly:
  `parseMediaName` (`MediaMetadata.kt:80-101`) yields
  `MediaQuery("The Amazing Spider-Man", 2012, null)`. The missing row
  poster is either (a) the Cinemeta `poster` field was empty/rejected
  at resolve time and then frozen 180 days on disk, or (b) the poster
  fetch failed on the slow link and the retry budget had not
  completed during the video. The TMDB key is irrelevant here because
  Cinemeta matched.
- Other hard names: `Harry.Potter.and.the.Sorcerers.Stone.2001…`
  (Cinemeta's canonical name is "…Philosopher's Stone", TMDB checks
  `title` only, never `original_title`); `Avatar.The.Way.of.Water.
  Extended.2022…` (`Extended` is not a `releaseTag` and sits before
  the year, so the title becomes "Avatar The Way of Water Extended");
  same class for `Unrated`, `Directors Cut`, `Remastered`, `IMAX`,
  `3D`, parenthetical `(Hindi Dubbed)` (parens blanked but contents
  stay in the title); `King.Kong.1080p.BluRay` (no year → every
  `King Kong` match is exact → `exact.size > 1 → null`).

## The 11 fixes (all approved)

### Issue 1 — jank

- **1a** (Small, low–medium): stable inset handling for the details
  sheet that does not depend on its current offset — e.g. override
  `contentWindowInsets` on the `ModalBottomSheet`. Must preserve
  safe-drawing padding and keep the list scrollable; wrong insets
  could clip under the status/nav bar. If a diagnostic build is the
  only way to test the mechanism, do NOT ship a guessed padding —
  report that instead.
- **1b** (Small–Medium, low–medium): scope the sheet's inputs — pass
  the selected download a state that changes only when its own inputs
  change, instead of the whole `DownloadsUiState`
  (`DownloadDetailsSheet.kt:32-33`). Keep files/metadata/candidates/
  error live for the open download. Do this only if it makes sense
  alongside 1a; if 1a alone is the right fix and 1b adds risk
  without evidence, leave 1b out, clearly flagged.
- **1c** (Trivial): remove the dead `animateContentSize` import
  (`DownloadsScreen.kt:6`).
- Do NOT just tweak spring parameters or swap `LazyColumn` for
  `Column` — either leaves the measured-anchor dependency in place.

### Issue 2 — posters

- **2a** (Medium, medium): poster-only TMDB fallback — when Cinemeta
  returns metadata with `posterUrl == null`, call TMDB by imdbId via
  `/3/find` (`TmdbResolver.resolve(query, imdbId)` lines 115-127
  exists but no caller ever passes an imdbId) else title search, and
  fill ONLY the poster, keeping Cinemeta's text fields. Extra TMDB
  calls per posterless title; disk-cache semantics for posterless
  entries must change with it (see 2b).
- **2b** (Small, low–medium): don't freeze posterless successes —
  if `posterUrl == null`, treat the disk entry as poster-incomplete
  and re-run the poster-only TMDB lookup (or Cinemeta re-fetch)
  instead of returning it for 180 days. Must NOT turn every cached
  row into a network call.
- **2c** (Small, low): strip edition/parenthetical/dub tags in
  `parseMediaName` + query variants — add `extended/unrated/
  directors cut/remastered/imax/3d` to the tag set; drop `(…)` contents
  before the year cut. Verify against year-in-title cases like
  `Blade Runner 2049` / `1917`. Query variants in order, each a fresh
  strict query, stop at first hit: (a) cleaned title + year (today);
  (b) cleaned title without year (accept only a single candidate);
  (c) edition-stripped title + year; (d) leading `The` dropped /
  `The X` ↔ `X, The`; (e) raw release-name title as last resort.
  Do NOT loosen the gate per variant — >1 exact-year candidate must
  still be null.
- **2d** (Small, low–medium): TMDB `original_title` matching +
  popularity tie-break. Today `searchHit` (`TmdbResolver.kt:128-155`)
  checks only `title`/`name` + year, never `original_title` or
  `popularity`. Prefer exact normalised `title` == query, then
  `original_title` == query, then exact year, then highest
  `popularity`; never auto-pick on popularity alone when titles
  differ; tie-break must never pick a wrong-language duplicate.
  TMDB `vote_average` is deliberately not mapped to `imdbRating`
  (`TmdbResolver.kt:30-40`) — keep that.
- **2e** (Small, low): per-row poster retry (tap placeholder) that
  clears that URL's 60 s miss entry and re-runs `loadPosterWithRetry`.
- **2f** (Small, low): user-triggered refresh bypasses the 1 h
  in-memory miss — cap to explicit refreshes so background page loads
  don't re-hammer.
- **2g** (Small, negligible): TMDB `w342` as the row poster size
  (`TmdbResolver.kt:225-229`, currently prefers w500 → w342).
  One-line bandwidth win at 48x72 dp on slow links.
- **2h** (Medium, medium): hydration visible-rows-first / bounded
  parallelism and/or don't hold the resolver Mutex across network I/O.
  Keep the `refreshVersion` abandonment. Do AFTER 2a–2c
  (correctness first, latency second).

## Open questions (fixes proceed without them)

- Version on the phone in the video (0.1.27 vs 0.1.28)?
- 2–3 more failing release names, copy-pasted?
- Does a missing poster ever appear if the row stays on screen for
  ~2 minutes, or on Wi-Fi?
- Was the TMDB key validated successfully in Settings?
- Anything that would change with those answers must be flagged in
  the implementation report.

## Constraints

- Optimised, modular code, small footprint on low-memory devices;
  lose no functionality.
- Implement + gate only — no PR, merge, tag, or release in this change.
