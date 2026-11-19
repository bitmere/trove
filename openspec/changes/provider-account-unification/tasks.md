# Tasks — provider-account unification

Sizes: S ≤ 1 day, M 2–4 days, L 1–2 weeks. All tasks are unchecked:
nothing here is approved until the user approves item by item.

## 1 — Unify the provider-account flow (M, medium risk) — DONE, gate pending at last edit

- [x] Add one generic `validateAndSave(service, credential, factory,
  label)` in `DebridRepository`, replacing the six `validateAndSave*`
  methods (`DebridRepository.kt:340-511`). Keep stored account IDs and
  the existing `signOut(service, accountId)` precedent.
  (One generic `validateAndSave` + one generic `signOut(service)` now
  replace the six + six; all six account IDs, per-service labels,
  "Key is empty" vs RD "Token is empty", TorBox not writing
  `KEY_ACTIVE_SERVICE`, and TorBox's distinct `storeTorboxAccount`
  helper preserved. Net main-code change ≈ −670 lines.)
- [x] Add one generic test/save + sign-out action in `SettingsViewModel`
  parameterized by a per-service descriptor (fields, strings, feedback
  slot), replacing the six pairs (`SettingsViewModel.kt:564-975` +
  `:998-1091`). Move TorBox sign-out onto the shared dispatched path
  (fixes the synchronous-on-Main call).
  (TorBox sign-out now briefly holds the `saving` flag while pending
  and shows plain error feedback on repository failure — two small
  intentional deltas; no failure path existed before.)
- [x] Add one `ProviderSection` composable in `SettingsScreen` driven by
  the descriptor, replacing the six provider forms
  (`SettingsScreen.kt:161-213, 367-743`). Only strings + state-field
  prefixes + 1–2 help lines differ per service — keep those in the
  descriptor.
- [x] Add focused tests first: each provider's save/test/sign-out still
  works, active-account fallback, backup gating, credential masking,
  per-provider help/note strings, TorBox's distinct storage helper,
  the account-changed signal.
- [x] Run focused tests, then full JVM suite, then lint. Phone-check:
  sign in / test / sign out for each of the six providers, backup
  gating unchanged. (Gate green 2026-11-19: 707 tests, 0 failures;
  lint 0 errors, 24 warnings; gate unit tests 52 OK. Phone-check
  still open.)

## 2 — Kind-aware `DownloadRef` + `hosterDomains` on the interface (M, medium risk) — DONE, gate pending

- [x] Add `DownloadRef(kind, id)` with interface defaults forwarding to
  the string methods; TorBox overrides. Add a default `hosterDomains()`
  (empty-set fallback) to `DebridProvider`.
- [x] Delete the `is TorBoxProvider` branches (`DebridRepository.kt:549,
  559`, `DownloadsViewModel.kt:241`) and the 5-way hoster switch in
  `DebridRepository.classifyShared` (`:615-639`).
  (No `is TorBoxProvider` branch remains in main sources. Offcloud
  classification note: an empty hoster set yields `HosterLink`, not
  `UnsupportedHoster` — that was the old behaviour too, preserved.)
- [x] Add focused tests first: TorBox numeric-id collision semantics
  preserved (same id, different kinds, routes to the right endpoint),
  same-id rows across kinds never misroute deletes/playback.
  (Incl. an interleaved delete+play test for two rows sharing id `11`
  across kinds, exact 4-request sequence, no mylist calls.)
- [x] Run focused tests, then full JVM suite, then lint. (Gate green
  2026-11-19: 707 tests, 0 failures; lint 0 errors, 24 warnings;
  gate unit tests 52 OK.)

## 3 — Shared provider helpers in stages (M, medium–high risk) — DONE Batch 3a, gate green 2026-11-19 (751 tests, lint 0 errors)

- [x] Stage 1: one shared `mapError` (decide TorBox's non-`Exception`
  rethrow explicitly, do not silently unify it) and the `opt*` JSON
  accessors as the canonical set (Real-Debrid and `CinemetaResolver`
  already use them).
  (New `ProviderHelpers.kt`: single `mapError` + canonical `opt*` set
  moved verbatim from TorBox + `optNonBlankString`. TorBox
  non-`Exception` decision: ADOPTED the rethrow — the other five
  already rethrew; TorBox used to wrap JVM Errors in `Network`, hiding
  a fatal condition behind a retryable-looking error. One deliberate
  TorBox behaviour change. Coordinator fix after the worker: the
  worker's two new tests asserted null for Gson behaviours that return
  values — single-element array `[1]` unwraps to `"1"`, fractional
  `1.5` truncates to `1L` under `asLong` (proven against Gson 2.15.0);
  the tests now pin the verbatim behaviour instead.)
- [x] Stage 2: migrate the other four providers to the `opt*` set one
  provider at a time, each with its own tests. Never big-bang.
  (AllDebrid, Premiumize, Offcloud, DebridLink migrated one at a time
  with string-vs-number parity tests. Per-provider element-level
  `text()` helpers deliberately left local — possible follow-up.)
- [x] Do NOT merge the six `call()` bodies into one base class.
- [x] Run focused tests, then full JVM suite, then lint after each stage.

## 4 — Split `SettingsUiState` by concern (M, medium risk) — DONE Batch 3a

- [x] Group provider-account state into one record per service; keep
  backup eligibility + account-changed atomic.
  (New `ProviderAccountState` record; six per-service clusters are now
  `torbox` / `realDebrid` / `allDebrid` / `premiumize` / `offcloud` /
  `debridLink` records + derived `anyProviderSaving`,
  `providerAccount(service)`, `clearFeedback()`. `canStartBackup`
  reads `anyProviderSaving`; `refresh()` rewrites all six records in
  one snapshot.)
- [x] Rework the 835-line settings test file in lockstep.
  (898 → 998 lines; all field references renamed. Coordinator fix
  after the worker: `StartupRegressionTest` had two missed
  `state.hasKey` references → `state.torbox.hasKey`.)
- [x] Add focused tests first; run focused tests, then full JVM suite,
  then lint. Split the ViewModel itself only if still needed after
  grouping the state. (ViewModel NOT split — grouping removed the
  need; the per-service surface is six record copies and the flow is
  already generic via the descriptor.)

## 5 — Split `DebridRepository` (M, medium risk; only after items 1 and 4) — DONE Batch 3c, gate green 2026-11-19 (808 tests, lint 0 errors)

- [x] Split account/provider registry vs. preferences store. Provider
  caching (rate budgets + hoster cache) must survive the split;
  `loadConnection` atomicity must survive it.
  (New `ProviderAccountRegistry.kt` — accounts, active-service
  pointer, CredentialStore, provider factories + cache,
  `validateAndSave`/`signOut`/`loadConnection`/`provider()`, backup
  restore, TMDB key; new `PreferencesStore.kt` — theme + theme
  StateFlow, sort, release words, clutter, clipboard, player,
  metadata overrides. `DebridRepository` (620 → 391 lines) is now
  the facade delegating to the two parts; metadata resolver wiring
  stays at the composition point; download ops compose both parts.
  Storage unchanged on purpose — one `"trove_accounts"` prefs file,
  disjoint key sets, zero migration risk. Construction unchanged —
  the context ctor is byte-for-byte the same, no call-site change.
  All account/provider ops `@Synchronized` on the registry (one
  monitor, no second lock in the facade). Coordinator fix: the
  worker's new test used `ThemeAccent.AMBER`, which does not exist
  — the accent is `EMBER`.)
- [x] Add focused tests first; run focused tests, then full JVM suite,
  then lint. (New `DebridRepositorySplitTest` — 13 tests: budget +
  hoster-cache retention across switches and preference writes,
  a real-thread `loadConnection` vs sign-out race with 0 torn
  snapshots allowed, registry/prefs independence both directions,
  parts in isolation, facade/parts shared-state composition.)
