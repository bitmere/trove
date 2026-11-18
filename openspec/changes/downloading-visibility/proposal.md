# Downloading visibility: fresh list on user refresh, and in-progress states that show progress

## What the user asked for

Two related complaints about the Downloads list:

1. A just-added torrent does not appear even after pull-to-refresh.
2. A torrent that is still downloading shows no progress — it looks
   stuck or missing while it is actually working.

## Verified current state (0.1.20, from code)

- `TorBoxProvider.listDownloads()` (`core/.../TorBoxProvider.kt`) calls
  `mylist` with `limit=1000, files=true` and NO `bypass_cache` — only
  single-item fetches by id (`listKind(..., id = ...)`, used by
  `getDownload`) pass `bypass_cache=true`. TorBox caches the list, so a
  just-added torrent may not appear even on pull-to-refresh: the refresh
  re-reads the same stale cached list.
- `toDownload()` maps the lowercased `download_state` string as:
  `download_finished` → READY; `cached`, `completed`, `seeding`,
  `uploading` → READY; `downloading` → DOWNLOADING; `queued` → QUEUED;
  `paused` → PAUSED; contains `error`/`fail` → ERROR; everything else
  → UNKNOWN. TorBox's official SDK states also include `metaDL`
  (downloading metadata — every magnet starts here), `stalled (no
  seeds)`, and `checking_resume_data`, which all land as UNKNOWN.
- Progress renders only for DOWNLOADING (`downloadProgress()` in
  `DownloadPresentation.kt`: returns null for any other status), so a
  `metaDL` / stalled / `checking_resume_data` torrent shows no progress
  bar, speed, ETA, or swarm counts even though TorBox reports them.
- `DebridProvider` is the shared provider interface
  (`core/DebridProvider.kt`); `listDownloads()` is its method,
  implemented by 6 providers plus test fakes.
  `TroveRepository.listDownloads(provider)` (`data/TroveRepository.kt`)
  is the repo-level call, overridden by `DebridRepository`.
- `DownloadsViewModel.refresh()` is the list-load entry point: called
  at init, on pull-to-refresh (as `viewModel::refresh`), after an
  in-app Add (`onAdded` → `refresh(showIndicator = false)`), and after
  a successful delete (`confirmDelete` → `refresh()`). `MainActivity`
  also calls `refresh()` after a share/add intent.
- TorBox rate limit is ~5/sec per IP; one list load is 3 calls
  (torrents + web + usenet).

## Decisions for review

1. **Stale cache: bypass it only on user-initiated refreshes.**
   `bypass_cache=true` is sent on the mylist calls only when the user
   asked for a fresh list — pull-to-refresh and the refresh after an
   in-app Add. Init load, delete-confirm refresh, and the
   MainActivity share refresh stay cached (`fresh=false`): they run
   when the user has not asked for new data, and every bypassed load
   costs 3 uncached calls against the ~5/sec TorBox limit. A bypassed
   load is still one list load (3 calls), so even a rapid
   pull-to-refresh stays well inside the limit; the gate is about not
   spending uncached calls on loads the user did not ask for.
   Call points, exactly:
   - pull-to-refresh: `onRefresh = { viewModel.refresh(fresh = true) }`
     in `DownloadsScreen.kt` (wired by the coordinator — see tasks).
   - after an in-app Add: `onAdded` → `refresh(showIndicator = false,
     fresh = true)`.
   - init, delete-confirm, MainActivity: unchanged, cached.
2. **Minimal ripple for the fresh path — one method per level, no
   default-delegating pair.** `DebridProvider` gains `suspend fun
   listDownloadsFresh(): Result<List<DebridDownload>> =
   listDownloads()`, overridden ONLY by `TorBoxProvider`, which
   threads `bypassCache = true` through a new `bypassCache: Boolean =
   false` parameter on the private `listKind` (setting
   `query["bypass_cache"] = "true"` when true — including for the
   single-item path, which already passes it). The other 5 providers
   and every test fake inherit the default, so `listDownloadsFresh()`
   is exactly `listDownloads()` for them: no edits in any other
   provider or fake. (This default is safe: it runs on `this`, so
   virtual dispatch reaches a fake's own `listDownloads()` override.)
   At the repo level, by contrast, there is a SINGLE method:
   `TroveRepository.listDownloads(provider, bypassCache: Boolean =
   false)`, overridden by `DebridRepository`, which calls
   `provider.listDownloadsFresh()` when true and
   `provider.listDownloads()` otherwise. A default body delegating to
   a 1-arg overload was tried first and REVERTED during gating: Kotlin
   `by` delegation forwards un-overridden methods to the delegate, so
   the default body ran against the delegate and bypassed a
   decorator's 1-arg override — the full-suite gate caught exactly
   that in `StartupRegressionTest` ("thrown list error stops loading
   and preserves rows on refresh"). One method, no delegation pair.
   The ViewModel gains a `fresh: Boolean` parameter on `refresh()`
   (see decision 4 for the overload shape) and passes it through as
   `bypassCache`.
3. **State mapping: `metaDL`, `checking_resume_data`, and anything
   starting with `stalled` map to DOWNLOADING.** All three are still
   trying to download — `metaDL` is the first phase of every magnet,
   `stalled (no seeds)` is waiting on peers, `checking_resume_data`
   is verifying what it already has — so they show the progress row
   (progress, speed, ETA, seeds/peers) instead of UNKNOWN with
   nothing. `stalled` is prefix-matched because the server appends a
   reason in parentheses (`stalled (no seeds)`). The state string is
   already lowercased before matching, so `metaDL` arrives as
   `metadl`.
4. **No new `DownloadStatus` value.** A new value (e.g. STALLED or
   METADATA) would ripple through the status-label mapping
   (`statusWord` in `DownloadsScreen.kt`), UI strings, and tests, for
   no user-visible gain: the user needs to see that the download is
   in progress, and DOWNLOADING already renders exactly that. The
   minimal mapping is the decision.
5. **`refresh()` overload shape.** The worker overload is
   `refresh(showIndicator: Boolean = true, fresh: Boolean)` with
   `fresh` required, and the existing no-arg entry stays
   `refresh() = refresh(showIndicator = true, fresh = false)`.
   `fresh` deliberately has NO default on the worker: if both
   parameters had defaults, `viewModel::refresh` (used as
   `() -> Unit` by pull-to-refresh and as a retry callback) would
   resolve against two applicable overloads, which risks an
   overload-resolution ambiguity error. With `fresh` required, only
   the no-arg overload adapts to `() -> Unit`. All named-argument
   call sites still work: `refresh(fresh = true)` (pull-to-refresh,
   `showIndicator` defaults to true) and
   `refresh(showIndicator = false, fresh = true)` (`onAdded`).
6. **Official readiness guidance, noted not changed.** TorBox's
   guidance is that readiness is `download_finished` /
   `download_present` / `cached` + `progress`, NOT the `completed`
   state. Trove currently also maps `completed` → READY. This change
   does not alter that mapping — whether TorBox ever returns
   `completed` without `download_finished`, and what that means, is
   unconfirmed from the code and needs live evidence before any
   change. Recorded here so the next person does not "fix" it on
   assumption.

## Known limitation

During `metaDL` TorBox may report the item's name as its hash, so
title search will not match it while it is in that phase. There is no
API fix for this: the name comes from the server. The item still
appears in the unfiltered list once the cache is fresh, and its real
name appears once metadata resolves.

## Non-goals (explicit non-decisions)

- Do NOT change the exclude-words filter. It stays as it is: defaults
  `CAM`/`TS`, whole-token match (`filterExcluded` in
  `ReleasePreferences.kt`), applied by `DebridRepository` on every
  list fetch including fresh ones.
- Do NOT pass the `active` mylist query param. Its exact server
  behaviour is unconfirmed in the docs; Trove lists everything today
  and keeps that.
- No change to `download_finished` / `completed` → READY mapping (see
  decision 6), to the sort/filter/search behaviour, or to any other
  provider's list path.

## Files to change

- `core/src/main/java/app/trove/debrid/core/DebridProvider.kt` —
  `listDownloadsFresh()` with a default implementation delegating to
  `listDownloads()`.
- `core/src/main/java/app/trove/debrid/core/TorBoxProvider.kt` —
  override `listDownloadsFresh()`; `bypassCache` parameter on the
  private `listKind`; `metaDL` / `checking_resume_data` / `stalled*`
  → DOWNLOADING in `toDownload()`.
- `app/src/main/java/app/trove/debrid/data/TroveRepository.kt` —
  single `listDownloads(provider, bypassCache: Boolean = false)`
  method (no 1-arg overload; see decision 2 for why).
- `app/src/main/java/app/trove/debrid/data/DebridRepository.kt` —
  override the 2-arg `listDownloads`, routing to
  `provider.listDownloadsFresh()` when `bypassCache` is true.
- `app/src/main/java/app/trove/debrid/ui/downloads/DownloadsViewModel.kt`
  — `fresh` parameter on `refresh()`; `onAdded` passes `fresh = true`.
  (Pull-to-refresh wiring in `DownloadsScreen.kt` is the
  coordinator's one-line change — see tasks.)
- Tests: extend `TorBoxProviderTest.kt` — state mapping for the three
  new states with progress preserved; fresh load sends
  `bypass_cache=true` on all three mylist calls, normal load does not.
- `openspec/changes/downloading-visibility/` — this proposal, tasks,
  specs.

## Validation

- Focused new provider tests first, then the full JVM suite, then
  lint (house rule: focused tests first, lint once before a PR).
- Phone check (user / on-device): add a magnet, confirm it appears
  in the list right after the Add refresh (previously missing until
  the cache expired); while a magnet is in `metaDL`, confirm the row
  shows Downloading with a progress bar; pull-to-refresh after adding
  a second torrent and confirm it appears; confirm a normal launch
  still loads the list and the delete flow still refreshes. A passing
  JVM suite is not proof of on-device behaviour.
