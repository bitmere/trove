# Changelog

## 0.1.42 — 2026-11-23

- Download progress (PR 78): downloading rows now show a live progress bar, percentage, speed and ETA that update about once a minute via a 60 s head poll (page 0 only, stops on 429). The status mapping is fixed so an actively downloading row is never hidden behind a stale READY flag. Pull-to-refresh now fetches only page 0 instead of traversing the whole tab, scrolling paginates by offset, and search matches the loaded snapshot instantly while the rest syncs in the background. Adding a download inserts its row at the top immediately; if the server still cannot confirm it after a second check, the row is dropped with an explanation. Optional per-download progress notifications (off by default) use the notification permission and a foreground service to show active download progress; the notifications render locally from already-fetched list data and no data leaves the device for them. With notifications on, polling continues in the background so they stay live; with them off, polling stops when the app backgrounds.
- Baseline: 805 JVM tests, lint 0 errors.

## 0.1.41 — 2026-11-23

- Ordering fix (PR 76): Newest/Oldest are now truly date-first — preferred release words only break ties, so an old x265/REMUX download no longer outranks the newest plain download. Refreshes and sort changes no longer shrink a fully loaded library back to 100 rows, and the saved snapshot heals itself instead of needing preferences deleted. Also fixed metadata enrichment being attempted twice for the same download on a fresh refresh.
- Baseline: 765 JVM tests, lint 0 errors.

## 0.1.40 — 2026-11-22

- Sync freshness (PR 74): the library no longer stops fetching early — pagination is counted from the raw TorBox page, so a page that loses rows to dedup cannot cut off the rest of the library. A fresh refresh now walks the visible tab to the end instead of re-checking only page 0, and a stale visible head revalidates on foreground return (at most once per tab per 10 minutes). Page failures now show an error with retry instead of hiding at the bottom of the list, and a cancelled search can no longer strand a tab in "loading".
- Baseline: 756 JVM tests, lint 0 errors.

## 0.1.39 — 2026-11-22

- Partial titles in manual TMDB search (PR 71): typing a partial title (e.g. "pushpa") now also matches titles starting with it (minimum 3 letters), so "Pushpa: The Rise" and "Pushpa 2: The Rule" appear instead of "No TMDB match". The automatic picker gate stays exact.
- Baseline: 750 JVM tests, lint 0 errors.

## 0.1.38 — 2026-11-22

- Blank details sheet with keyboard open (PR 68): the details sheet's list now uses inset-aware padding, so opening the keyboard no longer collapses the sheet to blank — the title, manual field, Search button and results stay visible while typing. Picker rows now show a poster and a two-line synopsis, so same-name matches (e.g. five "The Odyssey" results) can be told apart. Cleanup: the old inline row renderer was removed, superseded by the new row; no other dead code was found in the touched files.
- Baseline: 745 JVM tests, lint 0 errors.

## 0.1.37 — 2026-11-21

- Five code-proven picker bugs (PR 65): TMDB links with title slugs now parse — `…/movie/550-fight-club` resolves to the movie instead of falling through to a title search and returning no match. Deleting a download now cancels a running manual search and clears its status, mirroring dismiss, so the field and Search cannot stay disabled on reopen. Pick and Clear buttons disable while a resolve is in flight, so a tap during a slow resolve can no longer be silently dropped. Editing the manual field clears the stale manual status, so a FOUND line for an old query is no longer shown beside new input. The manual draft survives activity recreation.
- Baseline: 742 JVM tests, lint 0 errors.

## 0.1.36 — 2026-11-21

- Manual TMDB search races (PR 62): submitting a manual TMDB search in the details-sheet match picker now owns the picker — it cancels the in-flight automatic candidate load, so a late automatic response can no longer overwrite manual results or clear manual feedback. Dismissing the sheet mid-search cancels the pending manual search and clears its status, so the field and Search are never left disabled on reopen. A manual search that finds a match now shows a "Found — pick a match from the list above" line under Search, and the no-match message suggests the full title or a themoviedb.org link.
- Baseline: 738 JVM tests, lint 0 errors.

## 0.1.35 — 2026-11-21

- SIMKL client ID field (PR 59): the SIMKL group in Settings now has a "Public Client ID" field — register your own SIMKL app (AUTH V2, "TV, devices & command line") at simkl.com/settings/developer and paste its public client ID to enable sign-in, with no baked-in ID needed. The ID is stored encrypted, like the TorBox key; changing it wipes SIMKL tokens and the cached library, and signing out keeps it. With an ID saved, SIMKL sign-in is active.
- Baseline: 735 JVM tests, lint 0 errors.

## 0.1.34 — 2026-11-21

- SIMKL sync cooldown (PR 56): automatic SIMKL syncs (opening a download's sheet, pull-to-refresh) now run at most once every 30 minutes — inside the window they make zero network calls and badges keep coming from the saved copy on disk. The first sync still runs, a spent quota still reports properly, and "Sync now" in Settings always goes live.
- Manual TMDB link (PR 56): the details-sheet match picker now has a manual link row — paste a TMDB link (`themoviedb.org/movie/<id>` or `/tv/<id>`), type a bare id, or type a title to search. A manual pick links exactly the same way an automatic TMDB pick does. The row shows whenever a TMDB key is configured, even when Cinemeta already returned candidates.
- Note: SIMKL sign-in remains inactive in this release — the SIMKL app is not yet registered, so sign-in shows a not-registered note and syncs are skipped until the client ID is in place.
- Baseline: 724 JVM tests, lint 0 errors.

## 0.1.33 — 2026-11-21

- TMDB picker fallback (PR 52): when Cinemeta returns no matches, the details-sheet picker now falls back to a TMDB title search — TMDB rows are marked " · TMDB", and a TMDB pick resolves through TMDB detail with a verified IMDb id and Cinemeta backfill. With no TMDB key configured there are no TMDB requests, and the sheet shows a hint pointing at Settings.
- SIMKL read-only status badges (PR 53): sign in with SIMKL via device/PIN (Auth V2, read-only scope) from the new SIMKL group in Settings; tokens are stored encrypted. Library rows show "SIMKL · Watching/Watchlist/Completed" badges linking to simkl.com, and the details sheet shows episode progress for shows. Sync is on-demand only — sheet open, pull-to-refresh, or Settings — never on a timer.
- Note: SIMKL sign-in is inactive in this release — the SIMKL app is not yet registered, so sign-in shows a not-registered note and syncs are skipped until the client ID is in place.
- Baseline: 705 JVM tests, lint 0 errors.

## 0.1.32 — 2026-11-21

- List-vs-sheet enrichment gap (PR 48): rows that were never enriched are now re-enqueued instead of staying blank — snapshot restores, page-0 failures, scrolling, and refresh/sort/tab changes no longer drop them; opening the details sheet is no longer the only recovery. Failed poster loads retry, and TMDB-first titles with an IMDb id but no poster now fall through to the Cinemeta artwork search.
- Web download id fix (PR 49): fixes "TorBox returned no web download id" on web adds — web and Usenet adds now use the sync create endpoints, matching the torrent branches, instead of the async endpoints that return success with no id. When a create still returns no id, the item is recovered from mylist by link hash instead of throwing, and the error now carries TorBox's detail when present.
- Baseline: 650 JVM tests, lint 0 errors.

## 0.1.31 — 2026-11-20

- Poster regression fix (PR 45): updated installs serve surviving legacy posters immediately instead of skipping them when a TMDB key is set, and a posterless result no longer overwrites a good cached poster.
- Artwork fallbacks (PR 45): when TMDB resolves a title but has no usable poster, Cinemeta artwork is tried; a failed TMDB image falls back to the known Cinemeta artwork; an explicit refresh retries posterless rows and rows whose TMDB cooldown expired.
- Last good poster (PR 45): the row and details sheet keep the last successfully decoded poster across revalidation and order changes, replacing it only when the new image decodes.
- TMDB key changes (PR 45): any key change — including swapping one key for another — now invalidates metadata resolved under the old key.
- Baseline: 630 JVM tests, lint 0 errors.

## 0.1.30 — 2026-11-20

- Poster speed (PR 42): TMDB row thumbnails prefer w185 with w342/w500 cache reuse; transient poster failures retry after ~1s then ~2s instead of two 60s waits; poster loads survive a library refresh; visible metadata publishes per-result instead of waiting for a batch; the next 3 rows prefetch once the visible ones are done.
- Enrichment provenance (PR 42): the source is now tracked end-to-end and shown as a line in the details sheet — Cinemeta, TMDB, Cinemeta with a TMDB poster, no match, or unavailable.
- Details sheet (PR 42): the sheet shows the poster in an 80×120 slot next to the title, reusing the row's image.
- Conditional TMDB-first (PR 42): with a TMDB key configured, TMDB is tried first with Cinemeta backfilling the IMDb id, rating, episodes and poster; without a key the order stays Cinemeta-first with zero TMDB requests; a 429 honours Retry-After and falls back to Cinemeta.
- Baseline: 617 JVM tests, lint 0 errors.

## 0.1.29 — 2026-11-20

- Details sheet (PR 39): the sheet no longer bounces up and down while metadata loads — its insets are now stable instead of depending on the sheet's current offset, and its inputs are scoped to the selected download; a dead import was removed.
- Posters (PR 39): a poster-only TMDB fallback fills in posters when Cinemeta returns metadata without one, by IMDb id or title search, keeping the Cinemeta text; posterless entries revalidate instead of freezing for 180 days; the parser strips edition, parenthetical and dub tags and tries ordered query variants; TMDB original_title matching with a popularity tie-break; tap a poster placeholder to retry; an explicit refresh bypasses the 1 h miss; TMDB row posters prefer w342.
- Hydration (PR 39): visible rows hydrate first with 4 concurrent lookups and per-title lookup lanes instead of one Mutex held across network I/O, so the list fills in faster on slow connections.
- Baseline: 601 JVM tests, lint 0 errors.

## 0.1.28 — 2026-11-20

- Download row redesign (PR 36): flat one-title rows with a 48dp poster, a neutral placeholder instead of the Play glyph, and Play as a compact Jade-tinted trailing icon button; the raw release name, added date, and file facts move to the details sheet.
- Go-to-top (PR 36): the button now appears on any scroll down, not only after 10 rows, so you can jump straight to the top from anywhere in the list.
- Poster retry (PR 36): a failed poster is retried when the row re-enters composition or metadata refreshes, instead of showing the Play glyph forever.
- Details sheet (PR 36): episode and file rows are flat rows with hairline dividers instead of cards; the matched episode is marked with a Jade dot and label, and the cast line allows two lines.
- Settings and list polish (PR 36): one heading per provider section with no tinted banner, centered empty state, centered Add button label, leading icons on feedback messages, tighter Downloads header without the subtitle, "128+" inside the count pill, a plain "Reset" button, TalkBack announcements on loading spinners, and the official TMDB logo next to the attribution.
- Baseline: 591 JVM tests, lint 0 errors.

## 0.1.27 — 2026-11-20

- Search placeholder (PR 33): the "Search downloads" placeholder no longer wraps to three lines once Reset search appears after sorting; it is now single-line with an ellipsis, so the search bar stays 56dp.
- Play after sort (PR 33): the Play button no longer goes dead after changing the sort; the current-download check now compares structurally instead of by object identity, and the resolving state is cleared in a finally so Play can never stick disabled.
- Baseline: 586 JVM tests, lint 0 errors.

## 0.1.26 — 2026-11-19

- Impeccable design setup (PR 31): the Impeccable design skill/CLI is vendored into the repo (vendor submodule, Codex hooks, design docs) so UI work is reviewed against the same design checks; no app code changed.
- Baseline: 582 JVM tests, lint 0 errors.

## 0.1.25 — 2026-11-19

- Floating controls (PR 29): the go-to-top button moves into the floating action cluster, sharing its center line with Search and Add (16dp gap above Search), so it no longer overlaps Search; the cluster design is unchanged.
- Library restore on cold start (PR 29): the last-loaded library is saved to disk and restored instantly when the app reopens, then refreshed in the background — no more full-screen spinner after minimize/reopen; offline cold start keeps the cached rows.
- Providers as a visual list (PR 29): one row per provider with an initial-letter tile and signed-in status at a glance; tapping a provider opens its sign-in and details.
- Baseline: 582 JVM tests, lint 0 errors.

## 0.1.24 — 2026-11-19

- Core bundle (PR 27): API-layer and viewport paging (no more 1000-item cap), metadata enrichment (details sheet, durable metadata cache, candidate picker, TMDB opt-in), library performance fixes, provider-account unification, and test reorganization.
- Cleaner release pages (PR 26): the SHA-256 now lives in the release notes instead of a separate .sha256 file; the updater reads it from the notes, with the sidecar as fallback for older releases.
- Baseline: 808 JVM tests, lint 0 errors.

## 0.1.23 — 2026-11-19

- Kotlin 2.2.20 → 2.4.21 (PR 24): the Kotlin compiler and plugins move to 2.4.21, replacing Dependabot PR 18 (closed by Dependabot after the toolchain upgrade removed the `kotlin-android` plugin entry it was bumping).
- Baseline: 649 JVM tests, lint 0 errors.

## 0.1.22 — 2026-11-19

- OkHttp 4.12.0 → 4.12.1 (PR 21): the compromised-CA blocklist for CVE-2026-111943 — certificates from a widely-trusted CA whose private keys leaked are now rejected.

## 0.1.21 — 2026-11-18

- Search reset and go to top (PR 15): a Reset button in the search row clears the query, restores the sort to Newest and scrolls the list to the top; a go-to-top button appears once the list is scrolled down.
- Fresh list on refresh (PR 15): pull-to-refresh and the refresh after adding now ask TorBox for a fresh list, so a just-added download appears instead of a cached one.
- Downloading states (PR 15): torrents in metaDL, stalled and checking-resume-data now show as Downloading with progress instead of "Unknown".
- Library wiring finished (PR 15): the clutter-file toggle now filters the details sheet, and prefer-words ranking (x265/REMUX) now applies to sorting.
- Baseline: 411 JVM tests, lint 0 errors.

## 0.1.20 — 2026-11-18

- Updater install-guard fix (PR 13): the ownership check now compares canonical paths, so in-app updates no longer fail at install; the error screen names the check that failed, with copyable diagnostics.
- Settings reorganized into six groups (PR 13): Providers, Library, Playback, Appearance, Updates and Backup, each opening its own page.
- Web links recognized (PR 13): TorBox hoster-list parsing fixed — Rapidgator, 1Fichier and other hoster links now detect as web downloads instead of "hoster isn't supported".
- Baseline: 405 JVM tests, lint 0 errors.

## 0.1.19 — 2026-11-18

- Multi-provider support (PR 11): Real-Debrid, AllDebrid, Premiumize, Offcloud and Debrid-Link join TorBox behind one library — per-provider sign-in with instant switching, and multi-service Add with a cross-service cache check.
- Library features: clean titles with episode + quality, clutter-file hiding with a toggle, multi-select batch delete, release preferences (exclude CAM/TS, prefer x265/REMUX), clipboard magnet offer on open, launcher shortcut, password-encrypted backup & restore, and themes including pure-black OLED.
- Polish fixes: Stable/Beta channel alignment in Settings, player-picker retry, TalkBack announcements, and small layout fixes.
- README rewrite: install, TorBox setup, library use, playback and updating, all checked against the shipped code.
- Baseline: 396 JVM tests, lint 0 errors.

## 0.1.18 — 2026-11-18

- Trove moved to the bitmere GitHub account. Signed APKs are published through [GitHub Releases](https://github.com/bitmere/trove/releases).
- Settings checks GitHub Releases on demand. Stable is the default, and the optional Beta channel includes prereleases.
- Trove uses a new signing key. A clean install is required when moving from a previous build signed with the old key. Uninstalling clears local settings and the saved API key.

## 0.1.17 — 2026-11-18

- Sort menu on the Downloads screen: Newest, Oldest, Name, Largest, Smallest, Progress — the choice persists across restarts. (PR 15)
- Poster disk cache (32 MB cap, LRU): posters survive cold restarts without a network round-trip, and refresh skips Cinemeta re-resolution for rows whose metadata is unchanged. (PR 15)
- Default player setting in Settings: pick a preferred video player, or "Ask every time"; a per-item "Play with…" escape hatch; a missing or uninstalled player falls back to the chooser without clearing the choice. (PR 14)
- Updater hardening: distinct errors for empty, truncated and digest-mismatch downloads; copyable privacy-safe diagnostics (byte count, expected size, computed digest — no URLs or keys); bounded retry with validated Range resume; no-cache headers so a stale CDN edge cannot serve a wrong file. (PR 16)
- Baseline: 212 JVM tests (141 app + 71 core), lint 0 errors.

## 0.1.16 — 2026-11-17

- Download cards now show a poster, the proper title, the year and kind/status instead of the big format banner — the title is resolved from the filename with the keyless Cinemeta lookup (exact title + year match; if it cannot match, the parsed title is kept). (PR 12)
- Compact download rows: file names are capped at two lines, so long MKV names no longer take over the card. (PR 11)
- Sharing a link or file from another app now opens a small add window instead of the whole app. (PR 11)
- Newest downloads sort first, with a "Download added to <Tab>" confirmation after adding. (PR 11)
- Each row now shows when the download was added. (PR 11)
- Adding is smoother: the work that used to block the screen now runs in the background, with a silent refresh after adding. (PR 11)
- Baseline: 159 JVM tests (103 app + 56 core), lint 0 errors.

## 0.1.15 — 2026-11-17

- T3 — 2026-11-17 (`0358f3b`, PR 4): manual in-app updater in Settings (used the former public mirror, verified SHA-256, requested Android confirmation, and relaunched on success); release workflow + public mirror publishing; `REQUEST_INSTALL_PACKAGES` permission.
- T2 — 2026-11-17 (`ce695401`, PR 3): docs reconciliation (org files, CHANGELOG 0.1.2–0.1.14, requestdl correction, blank-screen diagnosis filed under docs/history).
- T1 — 2026-11-16 (`997a3bb`, PR 2): keyless CI signing and CI hardening. Release signing is secrets-only in GitHub Actions; local release builds cannot sign.
- First signed release: clean install required — will NOT install over the debug-signed 0.1.14; uninstall + reinstall, then updates work normally.
- Baseline: 144 JVM tests, lint 0 errors.

## 0.1.14 — 2026-11-16

- Clean beta: removed startup diagnostics, dead strings and layout-loop tests.
- Baseline: 107 JVM tests, lint 0 errors.
- Evidence: `3dfa067; merge 9b74d86, PR 1`.

## 0.1.13 — 2026-11-16

- Mitigated the tab-row layout loop with CountPill wrapContentWidth; sampler retained. User confirmed working on device on 2026-11-16.
- Evidence: `bd69349`.

## 0.1.12 — 2026-11-16

- Diagnostic sampler captured stacks gated on draw-entered rather than Choreographer, proving a main-thread Compose measure/layout loop.
- Evidence: `638a615`.

## 0.1.11 — 2026-11-16

- Added file-based startup diagnosis for the blank screen.
- Evidence: `d3456b7`.

## 0.1.10 — 2026-11-15

- Blank-screen fix attempt: replaced the custom tab indicator with the stock one and kept startup tracing. Did not fix the blank screen.
- Evidence: `f2da4f2`.

## 0.1.9 — 2026-11-15

- Blank-screen fix attempt: moved Keystore/account reads off the main thread and added stable account JSON with legacy support. Did not fix the blank screen.
- Evidence: `468fd89`.

## 0.1.8 — 2026-11-15

- Senior rewrite: premium design system, rich cards, single-scroll details sheet and safer async work. Shipped with a blank screen on launch (no first frame drawn).
- Evidence: `aae02a3`.

## 0.1.7

No separate release; the 0.1.7 work was folded into 0.1.8.

## 0.1.6 — 2026-11-15

- Added Play loading and clean sheet dismissal, concrete video MIME, full dark mode and redesigned header/search/tabs.
- Evidence: `fffd340`.

## 0.1.5 — 2026-11-15

- Play via requestdl (Nuvio model, works on Essential), 2026 modern UI and details sheet.
- Evidence: `1d87bfc`.

## 0.1.4 — 2026-11-15

- Rewrote the UI with Material 3; Play no longer treats a 403 response as an invalid key.
- Evidence: `a79f90f`.

## 0.1.3 — 2026-11-15

- Fixed Play mylist-object parsing, fresh file lookup and API detail in errors. Card text wraps; tapping a card opens the files dialog.
- Evidence: `4e20c2d`.

## 0.1.2 — 2026-11-15

- Fixed Play by dropping CATEGORY_BROWSABLE and surfacing errors. Aligned download cards; backdrop invalidates on content changes with a scrim fallback.
- Evidence: `d7fb2d1`.

## 0.1.1 — 2026-11-15

- Fixed Test & Save crash: network calls moved off the main thread and
  the validate-and-save path returns a `Result` instead of throwing,
  so a bad key shows an inline error instead of closing the app.
- Fixed Settings back navigation: proper NavHost with downloads and
  settings destinations, predictive back, Downloads state preserved,
  no unconditional refresh when returning.
- True backdrop blur for the floating Search button: bounded snapshot
  blur on API 31+ non-low-RAM devices, plain scrim fallback elsewhere.
- Tests: 50 JVM tests, all passing; lint 0 errors.

## 0.1.0 — 2026-11-15

- First build. TorBox as the reference provider: Downloads (Torrents /
  Web / Usenet), Add sheet with live cache check, share target with
  auto-detect, Play via the player chooser, Settings with a
  Keystore-encrypted TorBox key.
- Tests: 37 JVM tests, all passing; lint 0 errors.
