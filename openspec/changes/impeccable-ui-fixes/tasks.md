# Tasks: Impeccable UI fixes (12 items)

- [x] Verify each item's current code against main @ dc7a54fe
      (files listed in the proposal; `publicPosterUrl` in
      `core/.../MediaMetadata.kt` is the URL validator for item 3)
- [x] Fix 1: download-row redesign — flat Paper surface + hairline
      divider, 48x72 dp poster with 8 dp corners, neutral placeholder,
      one title (Space Grotesk, max 2 lines), one Inter meta line, one
      compact status + size line, kind dropped, raw name / added date /
      file facts moved to the details sheet, transfer progress + error
      stay on the row in Jade, compact 48 dp Jade-tinted trailing Play
      icon button when READY
- [x] Fix 2: go-to-top appears on any scroll down
      (`firstVisibleItemIndex > 0 || firstVisibleItemScrollOffset > 0`),
      gated on a non-empty list
- [x] Fix 3: poster retry on re-composition / metadata refresh;
      validation-rejected URL → neutral placeholder, no retry loop;
      network failure → 60 s miss cache, bounded retries
- [x] Fix 4: details-sheet episode/file rows flat + hairline
      dividers, Jade indicator/label on the matched episode, file rows
      keep 48 dp Play + overflow menu, cast line maxLines 2, Close
      button kept
- [x] Fix 5: ProviderSection flattened to one heading (provider name,
      headlineSmall) + status line, no tinted banner
- [x] Fix 6: LibraryEmptyState content centered in the viewport,
      still scrolls on short screens
- [x] Fix 7: Add button renders the 20 dp spinner slot only while
      adding, so the label is centred
- [x] Fix 8: Feedback gains a leading error-outline / check icon
- [x] Fix 9: Downloads header title titleMedium (or headlineSmall),
      "Your cloud library" subtitle dropped, account chip + avatar
      settings entry kept
- [x] Fix 10: "+" inside the count pill ("128+"); Reset button
      labelled "Reset" when only the sort changed (or just "Reset"
      always)
- [x] Fix 11: the two full-screen spinners on DownloadsScreen match
      the details-sheet TalkBack pattern (liveRegion +
      stateDescription)
- [x] Fix 12: official TMDB logo asset
      (`app/src/main/res/drawable/ic_tmdb_logo.xml`, converted from
      TMDB's official "Primary short (blue)" SVG — same path and
      gradient, no redraw) next to the text attribution in Library
      settings
- [ ] Regression tests: row redesign (one title, no kind, Play icon
      button when READY, transfer/error still shown), go-to-top
      threshold, count pill "128+", Reset label, empty-state centring
      where a Robolectric compose test can assert it, poster retry
      behaviour, Feedback icon presence — each new behaviour test
      mutation-checked (fails on the unfixed code)
  - [x] Add JVM coverage for the go-to-top threshold, count formatting, and bounded poster retries.
  - [x] Extend the existing download-cover instrumentation tests for row identity, Play, transfer progress, and errors.
  - [ ] Reset, empty-state centring, and standalone Feedback UI coverage. No existing Compose JVM tests or dedicated instrumentation suites cover these components.
- [x] Focused tests green, then full `./gradlew test` green
      (baseline 586 tests, 0 failures — final: 591 tests, 0 failures)
      + `./gradlew lint` 0 errors
- [x] Run the Impeccable detector over the changed UI files
      (user-level CLI, Kotlin text rules)
- [ ] Report the diff back for review (no PR, no merge, no release)
