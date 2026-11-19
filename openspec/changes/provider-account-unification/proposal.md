# Provider-account unification: factor the ×6 duplication

## What the user asked for

The code has grown large instead of optimized, modular, architect-level
design. The biggest concentration of repetition is the provider-account
surface, copied six times.

## Verified current state (0.1.23, from code)

Total factorable duplication is only ~700–900 lines (6–8% of main code).
The codebase is not large — 11,346 lines of main Kotlin, 2.9 MB APK,
13 dependencies, no DI framework — it is *repeated six times in two
places*:

1. **Provider forms in `SettingsScreen`** (~430 lines, ~280–340
   removable): TorBox 54 lines, the other five 74–77 lines each; same
   structure (header, connection text, masked-key field + show/hide,
   Test & Save + spinner, feedback, help text, Sign out). Only strings,
   state-field prefixes, and 1–2 help lines differ.
2. **Test/save + sign-out in `SettingsViewModel`** (~330 lines +
   ~100 lines of `showXxxFailure`/`withXxxFeedback` helpers): the five
   non-TorBox pairs already use the `withXxxFeedback` helpers; the
   TorBox pair is the odd one out — it manually resets all five feedback
   flags in three places and calls the repository **synchronously on
   Main** (`SettingsViewModel.kt:620-630`, Keystore delete +
   `SharedPreferences.edit` on Main), while the other five dispatch via
   `accountDispatcher` (IO). Sign-out ×6 was already factored into one
   private `signOut(service, accountId)` — the precedent for this fix.
3. **`validateAndSave*` ×6 in `DebridRepository`** (~172 lines,
   `DebridRepository.kt:340-511`, ~100–125 removable): trim → validate
   → build account → persist → map failure, repeated 6×; only the
   provider constructor, account id, and label differ.
4. **Kind leak through the interface.** `DebridProvider` takes a bare
   `String id`, but TorBox ids are numeric **per kind** and collide
   across namespaces. TorBox exposes public overloads the interface
   doesn't have, and callers compensate with `is TorBoxProvider` branches
   (`DebridRepository.kt:549,559`, `DownloadsViewModel.kt:241`). The
   interface `getDownload(id)` re-fetches up to 3 full 1,000-item lists
   just to resolve a kind before deleting one torrent. `hosterDomains()`
   is a second leak: public on 5 providers, not on the interface, so
   `DebridRepository.classifyShared` does a 5-way `when (provider) is …`
   switch.
5. **Shared provider helpers, partially adopted.** TorBox's `internal
   optString/optLong/optInt/optDouble/optBoolean`
   (`TorBoxProvider.kt:531-552`) are already used by Real-Debrid and by
   `CinemetaResolver` — they are the de-facto shared set; the other four
   providers just never adopted them. `Result.mapError()` ×6 (31 lines)
   is 5× byte-identical; TorBox's lacks the non-`Exception` rethrow.
   The six `call()` bodies (~204 lines) share a shape but auth
   placement, envelopes, and error classification genuinely differ.

**Genuinely provider-specific — do not deduplicate:** RD `selectFiles` +
add-then-delete cache probe; AD opaque-UUID web ids + magnet/upload
split; Premiumize transfer-vs-item model; Offcloud `cloudFiles` +
history walk; Debrid-Link seedbox/downloader split + page loop; TorBox
per-kind numeric-id namespaces + `bypass_cache`; per-provider rate
budgets; per-provider error envelopes and credential-redaction rules.

## Decisions for review

1. **One generic `validateAndSave(service, credential, factory,
   label)` in the repository**; one generic test/save + sign-out action
   in the ViewModel parameterized by a per-service descriptor (fields,
   strings, feedback slot); one `ProviderSection` composable driven by
   the descriptor. Moves TorBox sign-out onto the shared dispatched
   path, fixing the on-Main bug for free. Removes ~500–600 lines
   across 3 files. Keep stored account IDs and the working `signOut`
   precedent.
2. **Kind-aware `DownloadRef(kind, id)` on the interface** (defaults
   forward to the string methods; TorBox overrides) plus a default
   `hosterDomains()` (empty-set fallback). Deletes the 4
   `is TorBoxProvider` branches, the 5-way hoster switch, and the
   3×`mylist` delete trap. Needs tests preserving TorBox's numeric-id
   collision semantics.
3. **Shared provider helpers in stages, never big-bang:** first the
   zero-risk pieces — one shared `mapError` (decide TorBox's
   non-`Exception` rethrow explicitly) and the `opt*` JSON accessors as
   the canonical set, migrating the other four providers one at a time,
   each with its own tests. **Do not merge the six `call()` bodies into
   one base class** — envelopes/auth/redaction genuinely differ; a base
   class would trade 6 honest adapters for 1 adapter + 6 override mazes.
4. **Split `SettingsUiState` by concern first, then the VM if still
   needed.** Group provider-account state into one record per service;
   keep backup eligibility + account-changed atomic. The 835-line
   settings test file moves in lockstep.
5. **Split `DebridRepository` (account/provider registry vs.
   preferences store) only after items 1 and 4, or when a new feature
   forces it.** The repo is coherent today; a premature split just
   moves the wide interface.
6. **Do not refactor:** the six `call()`/`apiError` bodies into one
   base class; the `is TorBoxProvider` branches on their own (they are
   load-bearing until item 2 lands); Gson → kotlinx.serialization;
   manual DI → Hilt; the single-`StateFlow`-per-screen pattern (field
   count is the problem, not the mechanism); provider quirks listed
   above; the updater stack.

## Out of scope

- Any provider behaviour change (rate budgets, envelopes, cache probes).
- The `DebridRepository` split as a standalone item (item 5 covers it
  as a follow-up).
