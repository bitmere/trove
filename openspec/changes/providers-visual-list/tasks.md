# Tasks — providers visual list

Sizes: S ≤ 1 day, M 2–4 days. Implementation and test authoring are
tracked below. Execution and visual verification remain with the
coordinator.

## Phase 1 — visual list + detail sub-screen (S, low risk)

- [x] In `SettingsScreen.kt`, change `ProvidersContent` to render only
      the visual list. Keep the existing `ProviderList` data (service,
      signed-in status, account label, active marker) and restyle each
      row: a 48dp rounded tile with the provider's initial letter
      (SpaceGrotesk, tonal container color; primary container for the
      active provider), name, status line ("Signed in" + label or
      "Not signed in"), trailing "Active" label when active, and a
      chevron. Remove the radio buttons from the list rows.
- [x] Add per-provider detail navigation: tapping a row opens that
      provider's `ProviderSection` as a sub-screen (selected provider
      in `rememberSaveable` state; back arrow and system Back return
      to the list). Reuse the existing `ProviderSection` composable
      unchanged for the sign-in form.
- [x] In the detail sub-screen, add a "Set active" action when the
      provider is signed in and not active (calls the existing
      `SettingsViewModel.setActiveProvider`), and an "Active provider"
      state when it is active. No change to the switching itself.
- [x] Keep the coming-soon block and all existing strings; add only
      the strings the new rows/detail need (tile content descriptions,
      "Set active").
- [x] Add focused tests first: tile shows the right initial per
      service; status text matches signed-in state; tapping a row
      opens the right provider's detail; "Set active" calls
      `setActiveProvider` and the list then marks it active; back
      returns to the list. Update any existing tests that pin the
      old radio-button selection gesture.
- [x] Run focused tests, then full JVM suite, then lint, then
      `assembleDebug`. DONE 2026-11-19: ProvidersVisualListTest 2/2
      green; full gate green (see Gates).

## Gates

- [x] Full `./gradlew test lint assembleDebug` BUILD SUCCESSFUL,
      0 test failures, lint 0 errors. DONE 2026-11-19 (same run as
      floating-controls-and-restore: 582 tests, 0 failures; lint
      0 errors; debug APK built).
- [ ] Visual check of the Providers list at phone density: six
      distinct tiles, status readable at a glance, detail opens per
      provider, back returns to the list. (Semantics assertions in
      ProvidersVisualListTest pass; no PNG — Robolectric capture
      times out in this VM. Needs the user's phone.)
- [x] No functionality lost: sign-in, Test & Save, masking, feedback,
      sign-out, and active switching all behave as before. Full suite
      green; on-device confirmation still pending.
