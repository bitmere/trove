# Tasks

DRAFT ONLY — no implementation. The user approves item by item; each
item is a separate decision. Sizes: S = a day or less, M = a few days,
L = a week or more / new subsystem, XL = multi-week / new architecture.

## A. Multi-provider (already planned — reaffirmed, no new decision)

- [x] A1 — Real-Debrid adapter (device-code or token auth; no read-only cache check) — L (done 2026-11-18: token auth via Settings, `RealDebridProvider` in :core; device-code flow DEFERRED as a follow-up; `checkCached` returns null — no mutating probe; `.torrent` upload encoding is the multipart `file` fallback, unverified against the live API; no live Real-Debrid account exists here, so no live API verification and no on-device check yet)
- [x] A2 — AllDebrid adapter (PIN or API key; upload ready flag is the signal) — L (done 2026-11-18: API-key auth via Settings, `AllDebridProvider` in :core; PIN flow DEFERRED as a follow-up; `checkCached` returns null — the docs list no enabled read-only `/magnet/instant` probe, no mutating probe; list files come from `/v4/magnet/files` since v4.1 status omits them for multi-magnet calls; status codes 2/3 map to downloading; a still-downloading upload is returned in progress after the poll window, only error/deleted-upstream uploads are deleted; no live AllDebrid account exists here, so no live API verification and no on-device check yet)
  - [x] Wire API-key sign-in, active provider switching, hoster validation, and Add authentication feedback into the app. Add fake-backed regression coverage.
  - [ ] Verify app integration with a live account on a phone.
- [x] A3 — Premiumize adapter (transfer/directdl in one call; real cache/check) — L (done 2026-11-18: Bearer-header auth via Settings, `PremiumizeProvider` in :core; directdl cached = immediate link, uncached = transfer create + bounded poll (10 polls, 750 ms); real `checkCached` via POST /cache/check (items[], parallel response/filename/filesize arrays); .torrent and .nzb files via /transfer/create multipart `src`, NZB URLs via `src` string; hoster unlock via directdl; points-exhausted mapped to a plain-language Provider error; `supportsUrl` via /services/list directdl + aliases + regexpatterns; control() unsupported; no live Premiumize account exists here, so no live API verification and no on-device check yet; direct-download entries and NZB transfer kind are instance-local — the API exposes no directdl history and no stable transfer source type)
  - [x] Wire API-key sign-in, active provider switching, hoster validation, and Add authentication feedback into the app. Add fake-backed regression coverage.
  - [ ] Verify app integration with a live account on a phone.
- [x] A4 — Offcloud adapter (torrents sent as magnet links) — L (adapter done 2026-11-18. The [published API reference](https://github.com/Offcloud/offcloud-api) documents `?key=` auth on `https://offcloud.com`, `POST /api/cloud` with `url`, `POST /api/cloud/status` with `requestId`, `GET /api/cloud/explore/{requestId}`, `POST /api/remote/accounts` for the authenticated probe, and the read-only `POST /api/cache` with `hashes` for `checkCached`. The [older PHP client](https://packagist.org/packages/patriotblog/php-offcloud) uses `?apikey=`. [JDownloader](https://github.com/mirror/jdownloader/blob/master/src/jd/plugins/hoster/OffCloudCom.java) shows `POST /cloud/history` with `page` and `GET /cloud/remove/{requestId}` with session auth. API-key auth on those two routes is unverified. [Knightloader](https://pkg.go.dev/github.com/junkerderprovinz/knightloader/internal/resolver/debrid) describes `POST /api/sites` for hoster support, and [StremThru](https://pkg.go.dev/github.com/SillyHippy/stremthru/store/offcloud) corroborates status, cache, and file responses. Account identity and plan via API key, raw upload routes, and pause/resume are unverified. Torrent and NZB file uploads are unsupported. NZB URLs use cloud submission based on the [Usenet page](https://offcloud.com/usenet) and [Fetch integration](https://github.com/Offcloud/offcloud-fetch). No live Offcloud account exists here, so live API verification and on-device testing are pending.)
  - [x] Wire API-key sign-in, active provider switching, and Add authentication feedback into the app. Add fake-backed regression coverage.
  - [ ] Verify app integration with a live account on a phone.
- [x] A5 — Debrid-Link adapter — L

  The 2026-11-18 research verified the account, seedbox, and downloader endpoints and confirmed Debrid-Link torrent availability. The outage note refers to Fakir Debrid. The read-only cache endpoint was removed. Authenticated live API and on-device checks remain pending, including the downloader add array, ZIP expansion via IDs, and acceptance of both seedbox add keys. OAuth device flow is deferred.

- [x] A6 — Multi-service Add: check every service that can say if a torrent is cached, pick one that has it, let the user choose — M (needs 2+ providers shipped) (Source review verified provider capability flags, miss handling, Settings order, the no-probe path for Real-Debrid, AllDebrid and Debrid-Link, and IO dispatch for cache checks and selected credentials. Cross-service checks are magnet-only because ShareClassifier.TorrentFile carries bytes and a filename but no info hash. Explicit cache misses from Premiumize and Offcloud no longer trigger the legacy cached label in single-service Add. Fake-provider and fake-transport tests added. Gradle gates, live debrid accounts and on-device behavior were unverified.)
- [x] A7 — Per-provider sign-in and instant switching; tokens stored per provider in the Keystore — M (done 2026-11-18: active provider persisted, per-service cached providers, sign-out falls back to the other signed-in provider; Settings lists TorBox + Real-Debrid with sign-in state and the active marker. Follow-ups: backup/restore is still TorBox-only — Real-Debrid token is not included in backups; `DownloadsViewModel` still shows a TorBox-only auth message — that file is reserved by `search-top-reset`, fix there or after it lands)

## B. Library

- [x] B1 — Show episode token and quality on download cards ("Show · S02E05 · 1080p") — S
- [ ] B2 — Group series by season in the library — M (sequence AFTER the pending `search-top-reset` change)
- [x] B3 — Hide clutter files (.nfo, samples, covers, "Downloaded from" txt) in the details sheet, with a Settings toggle — S (done except caller wiring below)
  - Pending caller wiring: in `app/src/main/java/app/trove/debrid/ui/downloads/DownloadsScreen.kt`,
    add `showClutterFiles = showClutterFiles,` to the `DownloadDetailsSheet(...)` call inside
    `state.filePicker?.let`, once the saved Settings value is hoisted to DownloadsScreen
    as `showClutterFiles`. Until then the sheet uses its default of `false`.
- [x] B4 — Multi-select batch delete via long-press selection mode — S/M (data layer done; UI wiring deferred below)
  - Data layer done: `TroveRepository.deleteMany(provider, downloads)` returns one
    `BatchDeleteItemResult(download, result)` per distinct kind and ID in input order.
    The standalone `SelectionState` and `BatchDeleteBar` are ready for caller wiring.
    JVM and Compose instrumentation tests are added, with execution pending.
  - Pending caller wiring for `search-top-reset`: in
    `app/src/main/java/app/trove/debrid/ui/downloads/DownloadsScreen.kt`, change the
    `DownloadCard(onLongPress = ...)` callback in `DownloadsScreen()` to call a new
    `viewModel.toggleSelection(download)` after the existing `isCurrentDownload(download)`
    guard. While selecting, route card taps to the same function. In
    `app/src/main/java/app/trove/debrid/ui/downloads/DownloadsViewModel.kt`, add
    `DownloadsUiState.selection: SelectionState` and update it with
    `selection.toggle(download.id)`. Scope selection to the active subtab and call
    `clearSelection()` on tab or provider changes.
  - In `DownloadsScreen()`, show `BatchDeleteBar(selectedCount = state.selection.count,
    onDelete = viewModel::requestBatchDelete, onCancel = viewModel::clearSelection)` in
    the `TopAppBar` slot while `state.selection.isSelecting`. Add a batch `AlertDialog`
    alongside the existing single-delete dialog, with confirmation calling
    `viewModel::confirmBatchDelete` and dismissal calling `viewModel::dismissBatchDelete`.
    Have `requestBatchDelete()` capture the selected downloads from the active kind
    in `state.downloads`. In `confirmBatchDelete()`, use
    `repository.deleteMany(requireCurrentProvider(source), selectedDownloads)` in
    `viewModelScope`. If `libraryProvider === source`, call `refresh()` even after
    partial failure, surface failed outcomes, then call `clearSelection()` to apply
    `selection.clear()`. Keep the details sheet on `requestDelete(download)` and the
    existing `confirmDelete()` path.
- [x] B5 — Release preferences: exclude words (CAM, TS by default); prefer words (x265, REMUX) rank higher — S/M (done except deferred prefer-wiring below)
  - Done, except deferred wiring: in `app/src/main/java/app/trove/debrid/ui/downloads/DownloadsViewModel.kt`,
    first load the stored prefer words from `repository.loadPreferWords()` into a new
    `preferWords: List<String>` field on `DownloadsUiState`. In `visibleDownloads()`,
    replace `return sortDownloads(filtered, state.sort)` with
    `return sortDownloads(filtered, state.sort, state.preferWords)`.
- [ ] B6 — Coming-up / next-episode air dates in the library — M (later; verify Cinemeta meta `videos` as the source)

## C. Playback — in-app player (not recommended; kept deferred)

- [ ] C1 — Built-in Media3 player with FFmpeg audio decoders, PiP, gestures, sleep timer — L/XL (needs a user decision to revisit)
- [ ] C2 — Continue watching / resume points — M (needs C1)
- [ ] C3 — Subtitles from the torrent and OpenSubtitles search — M (needs C1)
- [ ] C4 — Next-episode autoplay, skip intro/recap/credits, mid-watch link refresh — M (needs C1)
- [ ] C5 — DLNA cast to TV — M (not recommended; Matrix is the TV product)
- [ ] C6 — Offline playback of downloaded files — needs the download engine

## D. Download-to-device engine (product-direction decision; default skip)

- [ ] D1 — Download manager: multi-connection, pause/resume, auto link refresh, Wi-Fi/charging/nightly/speed-limit rules, save anywhere, free-space check, auto-delete — XL (own OpenSpec change if approved, phased, after multi-provider)
- [ ] D2 — Direct-link downloads without a debrid service (Pixeldrain, Google Drive, direct links) — M (needs D1)
- [ ] D3 — Whole TorBox torrent as one zip — S/M (needs D1; verify the TorBox zip endpoint first)

## E. Convenience, system, and settings

- [x] E1 — Clipboard magnet offer on app open (once per copy, magnets/.torrent links only, setting to turn off) — S
- [x] E2 — Launcher shortcuts (Add) — S (widget and Quick Settings tile deferred — they need the download engine)
- [ ] E3 — Data-usage statistics (Wi-Fi vs mobile, per service, 30-day chart) — M (deferred, low priority)
- [x] E4 — Backup and restore of settings, with sign-ins optionally included and password-encrypted — M
- [x] E5 — Themes: pure-black OLED mode plus a small theme set — S/M
- [ ] E6 — Languages / per-app language setting — L (not recommended)
- [ ] E7 — Android TV / tablet layouts — L (not recommended; out of scope)
- [ ] E8 — Built-in browser with ad blocking — L (not recommended)
- [ ] E9 — Trakt sync (scrobble + watched status) — L (later, as its own change)
- [ ] E10 — Auto-remove torrents after all files downloaded — not recommended (unsafe semantics in a streaming library)

## Per-item validation (runs only after the user approves that item)

- [ ] Focused tests first, then the full JVM suite, then lint once before a PR.
- [ ] Phone verification for UI items: titles, clutter toggle, batch delete, shortcuts, clipboard offer, backup/restore round-trip, themes at normal and large font.
- [ ] Provider adapters re-verified live at build time (especially Offcloud and Debrid-Link).
- [ ] Fresh-implementation check: no code copied from any other app; features implemented fresh.
