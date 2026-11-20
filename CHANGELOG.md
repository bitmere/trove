# Changelog

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
