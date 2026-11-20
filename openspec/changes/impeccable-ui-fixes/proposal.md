# Impeccable UI fixes (12 items, 0.1.27 tree)

## What the user asked for

On 2026-11-20 ~10:18 IST the user approved all 12 Impeccable UI fixes
for Trove in one go, without answering the four open sub-questions.
The defaults below apply those answers, and are restated here so they
can be flipped at review:

- Raw release name → details sheet only (never on the row).
- Go-to-top → appears on any scroll down (not only past 10 rows).
- Details-sheet Close button → kept.
- Downloads header subtitle ("Your cloud library") → dropped.

## Verified current state (0.1.27, main @ dc7a54fe)

- The download row is a tonal card (`DownloadCard.kt`): a kind-tinted
  `DownloadCover` banner (poster + title + subtitle + kind · status),
  then a body with the raw release name (2 lines), file count + size,
  added date, transfer progress, and a full-width Play `Button`.
- The go-to-top button appears only at `firstVisibleItemIndex >= 10`
  (`DownloadsScreen.kt`).
- A failed poster load is cached as a miss for 60 s and never retried
  while the row stays composed (`MetadataPoster.kt`); the placeholder
  is a Play glyph on a tonal surface.
- The details sheet renders episodes and files as `Surface` cards
  (`DownloadDetailsSheet.kt`); the matched episode is marked only by
  a different card colour.
- The provider section opens with a tinted `primaryContainer` banner
  (`SettingsScreen.kt: ProviderSection`).
- The empty state is a top-aligned column (`LibraryEmptyState.kt`).
- The Add sheet's Add button always reserves a 20 dp spinner slot,
  so its label sits off-centre (`AddSheet.kt`).
- `Feedback` (`Feedback.kt`) signals error/success by container colour
  alone, with no leading icon.
- The Downloads header is `headlineLarge` + a "Your cloud library"
  subtitle (`DownloadsScreen.kt`).
- The count pill shows the bare number with a separate "+" text next
  to it, and the Reset button reads "Reset search"
  (`DownloadsScreen.kt: Subtabs`, `CountPill.kt`).
- The two full-screen spinners on the Downloads screen carry no
  TalkBack semantics, unlike the details-sheet spinner, which has a
  live region + state description (`DownloadDetailsSheet.kt`).
- Settings Library attribution is text only
  (`SettingsScreen.kt: LibraryContent`); the TMDB terms require the
  logo as well. The official "Primary short (blue)" SVG was fetched
  from TMDB's logos page
  (https://www.themoviedb.org/about/logos-attribution) and converted
  to `ic_tmdb_logo.xml` — same single path and linear gradient
  (#90CEA1 → #3CBEC9 → #00B3E5), no redraw.

## Scope

All 12 items below. Out of scope: BatchDeleteBar / SelectionState
(unwired multi-select, not a design fix), the 128 KB metadata cap,
and enrichment match-gate / parser code. No functionality is lost:
Play, transfer progress, error text, the account chip, the avatar
settings entry, and the details-sheet Close button all stay.

## The 12 items

1. Download-row redesign (Medium): flat Paper surface + hairline
   divider, 48x72 dp poster with 8 dp corners, neutral media
   placeholder (not the Play glyph), ONE title only (clean when
   enriched, parsed otherwise, Space Grotesk, max 2 lines), one Inter
   line (year · type · SxxExx · quality), one compact status + size
   line, kind dropped, raw name / added date / file facts to the
   details sheet, transfer progress + error text stay on the row in
   Jade, Play stays as a compact 48 dp Jade-tinted trailing icon
   button when READY.
2. Go-to-top on any scroll down (Small): threshold
   `firstVisibleItemIndex >= 10` → away-from-top
   (`firstVisibleItemIndex > 0 || firstVisibleItemScrollOffset > 0`),
   gated on a non-empty list.
3. Poster load retry + honest placeholder (Small): retry a failed
   poster when the row re-enters composition / metadata refreshes;
   distinguish a validation-rejected URL (neutral placeholder, no
   retry loop) from a network failure (keep the 60 s miss cache,
   bound retries).
4. Details sheet flat rows (Small–medium): episode/file rows as flat
   rows + hairline dividers (not Surface cards), matched episode
   marked with a Jade indicator/label, file rows keep 48 dp Play +
   overflow menu, cast line maxLines 2. Close button kept.
5. Provider section one heading, no tinted banner (Small): flatten
   ProviderSection to one heading (provider name, headlineSmall) +
   status line.
6. Empty state centered (Small): center LibraryEmptyState content in
   the viewport (still scrolls on short screens).
7. Add sheet: center the Add button label (Small): only render the
   20 dp spinner slot while adding.
8. Feedback leading icon (Small): error-outline / check icon so the
   state reads without colour.
9. Downloads header tighten (Small): titleMedium (or headlineSmall)
   title, DROP the "Your cloud library" subtitle, tab row carries
   screen identity. Account chip + avatar settings entry stay.
10. List-header clarity (Small): "+" inside the count pill ("128+");
    Reset button labelled "Reset" when only the sort changed (or just
    "Reset" always).
11. Loading spinners TalkBack semantics (Small): match the
    details-sheet pattern (liveRegion + stateDescription) on the two
    full-screen spinners.
12. TMDB logo in Settings (Small, terms-driven): official TMDB logo
    asset next to the text attribution in Library settings.
