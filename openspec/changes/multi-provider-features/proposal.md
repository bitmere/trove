# Multi-provider and library features — analysis and plan

## Why

Trove's roadmap already plans six debrid providers (TorBox →
Real-Debrid → AllDebrid → Premiumize → Offcloud → Debrid-Link), and
the library around them keeps growing. This change analyses a
candidate feature set — providers, library, playback, a
download-to-device engine, and convenience — against Trove 0.1.18 and
proposes, item by item, what to adopt, what to defer, and what to
skip.

## Sources

- Trove 0.1.18 audit of the local checkout (`README.md`,
  `CHANGELOG.md`, `docs/PROVIDERS.md`, `app/src`, `core/src`,
  `AndroidManifest.xml`, `strings.xml`, `gradle/libs.versions.toml`).
- Provider research in `docs/PROVIDERS.md` and
  `docs/MULTI-PROVIDER-RESEARCH.md`.

## Ground rules

- **Fresh implementation:** every adopted feature is implemented
  fresh in Trove. No code is copied from any other app, the same way
  Nuvio code was handled.
- **Trove's identity:** Trove is a cloud-library front end — it lists
  TorBox items and plays them through an external player. It is NOT a
  device downloader and has no in-app player. That shapes the
  recommendations: library and convenience features fit; the
  download-to-device engine and the in-app player are product-direction
  decisions, not small adds.
- **House rules:** modular, optimized code, small footprint on
  low-memory devices, no lost functionality (`AGENTS.md`). No new
  dependency without a reason.
- Sizes are rough: S = a day or less of focused work; M = a few days;
  L = a week or more / a new subsystem; XL = multi-week / new
  architecture.

## Provider architecture

- **Design.** Trove uses one provider abstraction with a
  per-provider adapter; per-provider capabilities (`supportsUrl` /
  `checkCached` / `control`); item ids namespaced per provider/kind;
  links resolved lazily (`getStreamUrl` → `requestdl`).
- **Wiring.** Trove wires a single repository manually in
  `AppContainer` (no DI framework, house rule), uses OkHttp, has no
  database, and its interface serves the library UI plus playback.
  Keep Trove's wiring. Do not adopt Hilt, Room, or Retrofit for this.
- **Validation.** The planned provider set and build order (TorBox →
  Real-Debrid → AllDebrid → Premiumize → Offcloud → Debrid-Link)
  stand. No change to the order. Provider caveats from `docs/PROVIDERS.md`
  still apply: Real-Debrid and AllDebrid have no read-only cache check;
  Offcloud needs live verification at build time; Debrid-Link's torrent
  service was reported unavailable in Nov 2026 — verify before
  building it.

## Comparison

### A. Multi-provider — already planned

| # | Feature | Trove 0.1.18 | Recommendation | Size | Notes |
| --- | --- | --- | --- | --- | --- |
| A1 | Real-Debrid adapter | Missing | Already planned (step 2) | L | Device-code or token auth; no read-only cache check — probe is add-then-delete; `selectFiles` mandatory; links IP-locked. |
| A2 | AllDebrid adapter | Missing | Already planned (step 3) | L | PIN or API key; the upload ready flag is the signal; a miss must be deleted. |
| A3 | Premiumize adapter | Missing | Already planned (step 4) | L | `transfer`/`directdl` gives links in one call; real cache/check; points system. |
| A4 | Offcloud adapter | Missing | Already planned (step 5) | L | Thinnest docs — live verification at build time. `.torrent` upload unsupported; torrents go as magnet links. |
| A5 | Debrid-Link adapter | Missing | Already planned (step 6) | L | Verify the service is actually available before building it. |
| A6 | Multi-service Add: checks every service that can say if a torrent is cached, picks one that has it, lets the user choose | Missing | Adopt with A1+ | M | Depends on 2+ providers shipped. Needs an Add-sheet provider picker. Per-provider capability declarations are the model (fresh implementation). |
| A7 | Per-provider sign-in and instant switching; tokens stored per provider | Missing | Adopt with A1+ | M | Tokens per provider in the Keystore; the "Coming soon" labels become real entries. |

### B. Library

| # | Feature | Trove 0.1.18 | Recommendation | Size | Notes |
| --- | --- | --- | --- | --- | --- |
| B1 | Clean titles with episode and quality, e.g. "The Bear · S02E05 · 1080p" | Partial — title/year/type only; episode token and quality tags are stripped by the parser | Adopt | S | The parser already extracts the episode token and quality tags (`MediaMetadata.kt`); show them on the card instead of discarding them. |
| B2 | Series grouped by season | Missing | Adopt later | M | Grouping in the list. Sequence AFTER the pending `search-top-reset` change — it shares the Downloads list rows and ViewModel, whose files are reserved. |
| B3 | Hide clutter files (`.nfo`, samples, covers, "Downloaded from" txt) in the details sheet, with a Settings toggle | Missing — details sheet lists every file unfiltered | Adopt | S | Filter the file list; default hidden; toggle restores. No new permission. |
| B4 | Multi-select batch actions (long-press to select; batch download / remove / mark watched) | Missing — long-press is single-item delete only | Adopt | S/M | Selection mode plus batch delete. Batch mark-watched excluded: Trove has no watched-state model. |
| B5 | Release preferences: exclude words (CAM, TS by default); prefer words (x265, REMUX) rank a release higher | Missing — the parser knows the tags but nothing filters or ranks on them | Adopt | S/M | Settings lists plus filter/rank applied in the library. |
| B6 | Coming-up / next-episode air dates in the library | Missing — Cinemeta resolver returns title/year/type/poster only | Later | M | Needs episode/air-date data; the Cinemeta meta `videos` array is the candidate source (verify). A "Coming up" row only when posters are on. |
| B7 | Follow a show: new episodes reaching the account download by themselves, checked on library load and every couple of hours in the background | Missing — refresh is manual only; no background work at all | Skip (reframe) | L | Trove has no download engine and no background sync by design, so auto-download does not apply to a streaming library. A followed-shows watchlist/notice is a different, smaller feature and is not proposed here. |
| B8 | Plex/Jellyfin folder layout (`Shows/Name/Season 01`, `Movies/Name (Year)`) | Missing | Skip | — | Needs the download engine: Trove writes no files to device storage. |

### C. Playback — in-app player and everything hanging off it

| # | Feature | Trove 0.1.18 | Recommendation | Size | Notes |
| --- | --- | --- | --- | --- | --- |
| C1 | Built-in player (Media3: seek, speed, audio/subtitle tracks, FFmpeg audio decoders, PiP, gestures, sleep timer, A-B loop, screenshots) | Missing — playback is the external player chooser only | Skip / keep deferred | L/XL | Directly conflicts with the small-footprint rule (Media3 plus FFmpeg binaries). Trove's settled direction is the external player chooser, decided 2026-11-15; an in-app player only if browsing-while-playing becomes a must-have. Revisit only on a user decision. |
| C2 | Continue watching / resume points per file (stable across changing debrid links) | Missing — no watched-state model | Skip with C1 | M | Position cannot be tracked reliably in an external player. Needs C1. |
| C3 | Subtitles from the torrent (`.srt`/`.ass`/`.vtt`, labelled by language) and OpenSubtitles search, hash-matched | Missing | Skip with C1 | M | External players handle their own subtitles. Passing subtitle files to VLC/MX via intent extras is fiddly and unproven. |
| C4 | Next-episode autoplay, skip intro/recap/credits (chapters or remembered skip points), no-dead-stream link refresh mid-watch | Missing | Skip with C1 | M | All need the in-app player. |
| C5 | Play on TV: DLNA cast to a TV on the same Wi-Fi, resume position sync | Missing | Skip | M | Matrix is the TV product; Trove is a phone app. DLNA adds discovery, multicast permission, and footprint. |
| C6 | Offline playback of downloaded files in the same player | Missing | Skip | — | Needs the download engine. |

### D. Download-to-device engine — product-direction decision

| # | Feature | Trove 0.1.18 | Recommendation | Size | Notes |
| --- | --- | --- | --- | --- | --- |
| D1 | Download manager: up to 16 connections per file, pause/resume, automatic expired-link refresh, rules (Wi-Fi only, charging only, nightly window, speed limit), save to any folder incl. SD/USB, free-space check, auto-delete of old downloads | Missing — no `DownloadManager`, `WorkManager`, or download engine of any kind; "downloads" are TorBox server-side items | Decision needed; default Skip | XL | The single biggest scope change: foreground service/job, chunked engine, storage, new permissions, battery and backup behaviour. Turns Trove from a lightweight library-plus-streamer into a downloader. If the user wants it, it is its own OpenSpec change, phased, after multi-provider. |
| D2 | Direct-link downloads without a debrid service (Pixeldrain, Google Drive, direct file links) | Missing — every add goes through TorBox | Skip with D1 | M | Needs the engine. |
| D3 | Whole TorBox torrent as one zip | Missing — resolution is per-file only | Skip with D1 | S/M | Needs a download path to be useful (a zip link cannot be played); verify the TorBox zip endpoint first. |

### E. Convenience, system, and settings

| # | Feature | Trove 0.1.18 | Recommendation | Size | Notes |
| --- | --- | --- | --- | --- | --- |
| E1 | Clipboard magnet offer: open the app with a magnet copied and it offers to add it (once per copy, can be turned off) | Missing — no clipboard reads anywhere | Adopt | S | Offer only for magnets / `.torrent` links, once per copy, with a setting to turn it off. Note: Android 12+ shows a toast when an app reads the clipboard. |
| E2 | Home-screen widget, Quick Settings tile, launcher shortcuts (Add, Resume all, Pause all) | Missing — manifest declares only MainActivity and ShareActivity | Adopt shortcuts only | S | Static launcher shortcuts (Add). Widget and tile deferred: their Pause/Resume actions need the download engine. |
| E3 | Data-usage statistics (this month split Wi-Fi/mobile, 30-day chart, totals per service) | Missing | Defer | M | Needs byte accounting. Nice-to-have, low priority. |
| E4 | Backup and restore of settings, with sign-ins optionally included, password-encrypted (PBKDF2 + AES-256-GCM) | Partial — Android auto-backup only; the key is deliberately excluded, so a clean install wipes settings and key | Adopt | M | In-app export/import. Sign-ins included only if the user opts in, encrypted with a password they choose. Real pain point: the 0.1.18 clean install wiped settings and the API key. |
| E5 | Themes: seven colour themes in light/dark, pure-black OLED mode, wallpaper colours on Android 12+ | Partial — one fixed light scheme and one fixed dark scheme, system-selected | Adopt | S/M | Pure-black OLED plus a small theme set behind the existing token system. No new dependency. |
| E6 | 14 languages, per-app language setting on Android 13+ | Missing — one `strings.xml`, no locale directories | Skip | L | Translation plus ongoing maintenance burden for a single-locale user base. |
| E7 | Android TV and tablet layouts (Leanback, side nav rail on wide screens) | Missing | Skip | L | Out of scope. Trove is a phone app by decision; Matrix is the TV product. |
| E8 | Built-in browser with uBlock Origin ad blocking, "find videos on page" download listing | Missing — no WebView of any kind | Skip | L | WebView plus filter-list updating is a separate app concern. Trove's Add already covers share/paste; the "find videos" path is a downloader acquisition flow. |
| E9 | Trakt sync: scrobble what you watch, watched status from anywhere shows in the library | Missing — no watched-state model at all | Later, separate change | L | Needs a watched-state model and OAuth first. Candidate for its own change, not this batch. |
| E10 | Auto-remove torrents from the service once every file in one has downloaded | Missing — manual delete exists (long-press, details sheet) | Skip | S but unsafe | In a streaming library "downloaded" has no clean meaning, and auto-deleting cloud items risks data loss. Manual delete stays. |

## Recommended adopt set (user picks item by item)

1. A1–A5 — multi-provider adapters (already planned; this change
   reaffirms the order, no new decision) — L each.
2. A6 + A7 — multi-service Add and per-provider switching, as providers
   land — M each.
3. B1 — clean episode/quality titles — S.
4. B3 — hide clutter files + toggle — S.
5. B4 — multi-select batch delete — S/M.
6. B5 — release preferences — S/M.
7. E1 — clipboard magnet offer — S.
8. E2 — launcher shortcuts (Add) — S.
9. E4 — backup and restore with optional encrypted sign-ins — M.
10. E5 — themes: pure-black OLED + small set — S/M.

Later / optional: B2 season grouping (after `search-top-reset`), B6
coming-up air dates, E3 data-usage statistics, E9 Trakt (own change).

Not recommended: C1–C6 in-app player and dependents, D1–D3 download
engine and dependents, B7 follow-a-show, B8 Plex/Jellyfin layout, E6
languages, E7 TV/tablet, E8 built-in browser, E10 auto-remove.

## Sequencing and conflicts

- B2 shares the Downloads list rows and ViewModel with the pending
  `search-top-reset` change, whose files are reserved — sequence B2
  after it lands.
- The A-series is the existing multi-provider roadmap; nothing here
  changes its order or its DMM-style design.
- Nothing in this plan touches `README.md`, the search files
  (`DownloadsScreen.kt`, `DownloadsViewModel.kt`,
  `LibraryEmptyState.kt`), or the pending `polish-minute-details`
  change.
- No new dependencies are proposed for the adopt set. C1, D1, and E8
  would each add heavy dependencies — part of why they are skipped.

## Validation

- Per item, when approved: focused tests first, then the full JVM
  suite, then lint once before a PR; phone verification for UI items
  (titles, clutter toggle, batch delete, shortcuts, clipboard offer,
  backup/restore round-trip, themes at normal and large font).
- Provider adapters: re-verify each provider's API live at build time
  (especially Offcloud and Debrid-Link); cache-check behaviour per
  provider follows `docs/PROVIDERS.md`.
- Fresh-implementation check: no code copied from any other app; every
  adopted feature is implemented fresh.

## What was not verified

- The compared feature set was drawn from public multi-provider app
  documentation — not by installing or running any reference app.
  On-device behaviour of the compared features is unverified.
- Trove statuses rest on a read-only audit of the 0.1.18 checkout
  (file evidence cited per feature); no build or test run was done for
  this analysis.
