# Tasks: Sheet jank + poster fallbacks (11 items)

- [x] Verify each item's current code against main @ acfe67dc (files
      listed in the proposal)
- [x] Fix 1a: stable inset handling for the details sheet, not
      dependent on its current offset (e.g. override
      `contentWindowInsets` on the `ModalBottomSheet`); preserve
      safe-drawing padding, keep the list scrollable. If a diagnostic
      build is the only way to test the mechanism, report that instead
      of shipping a guessed padding
- [x] Fix 1b: scope the sheet's inputs to the selected download only
      (only if it makes sense alongside 1a; if 1a alone is the right
      fix, leave 1b out and flag it)
- [x] Fix 1c: remove the dead `animateContentSize` import
      (`DownloadsScreen.kt:6`)
- [x] Fix 2a: poster-only TMDB fallback (by imdbId via `/3/find`,
      else title search; fill only the poster, keep Cinemeta text)
- [x] Fix 2b: posterless disk entries re-run the poster-only lookup
      instead of being returned frozen for 180 days; must not turn
      every cached row into a network call
- [x] Fix 2c: strip edition/parenthetical/dub tags in `parseMediaName`
      + query variants (a)–(e) in order, strict gate kept per variant;
      verify against `Blade Runner 2049` / `1917`
- [x] Fix 2d: TMDB `original_title` matching + popularity tie-break;
      never auto-pick on popularity alone; keep `vote_average` NOT
      mapped to `imdbRating`
- [x] Fix 2e: per-row poster retry on tap of the placeholder
- [x] Fix 2f: user-triggered refresh bypasses the 1 h in-memory miss
      (explicit refreshes only)
- [x] Fix 2g: TMDB `w342` as the row poster size
- [x] Fix 2h: hydration visible-rows-first / bounded parallelism
      and/or don't hold the resolver Mutex across network I/O; keep
      `refreshVersion` abandonment; do AFTER 2a–2c
- [x] Tests: parser variants, TMDB `original_title`/popularity
      matching, poster-only fallback, posterless-cache revalidation
- [ ] Gates: full unit tests green, lint 0 errors, Impeccable
      detector 0 findings on changed UI files; re-run gates fresh
      (`--rerun-tasks --no-build-cache`) before reporting
  Gradle gates are pending coordinator review. The Kotlin detector
  ran with zero findings. Device verification is pending.
- [x] Report: branch + head commit, per-item done/skipped with reason,
      test/lint/detector results, diff summary, what still needs the
      user's phone to verify, and anything the open user questions
      would change. No PR, merge, tag, or release.
