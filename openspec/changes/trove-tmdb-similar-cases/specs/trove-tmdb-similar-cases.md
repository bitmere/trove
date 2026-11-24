# trove-tmdb-similar-cases

## F1 — Untyped numeric manual input

The manual parser SHALL classify an all-digit string within the existing input
limit as ByTitle when fallbackType is neither movie nor series, before trying
to convert it to an Int. Valid typed bare IDs and explicit TMDB item links
SHALL retain their current meaning and positive-Int validation.

Scenarios:
- Given unknown type, `418`, `1917`, `300`, `2012`, `42`, `0`, and `2147483648`
  SHALL become title searches with the original trimmed digits; invalid fallback
  type SHALL behave like unknown type.
- Given type movie, `418` SHALL resolve ID 418; zero/overflow SHALL remain Invalid.
- A valid movie/tv link SHALL override fallback type; zero/overflow link IDs
  SHALL be Invalid. Missing API key SHALL produce no TMDB requests.

## F2 — Two-character manual prefixes

Manual result filtering SHALL accept normalized title prefixes of length two
or more in localized or original titles. Exact one-character titles SHALL
remain eligible. Automatic candidate searches and enrichment SHALL remain exact.

Scenarios:
- `pu` SHALL retain returned `Pushpa`; `O.M` SHALL retain `O.M Operation Mole`.
- `X` SHALL retain exact `X` but not `X Files`; `It` and `Up` SHALL remain exact
  matches in manual and automatic lanes.
- An automatic query `pu` SHALL not accept `Pushpa`.

## F3 — Manual substring matching

Manual result filtering SHALL also accept contains matches of at least two
normalized characters in either title field. It SHALL preserve the outgoing
trimmed query, TMDB result order (movie then series when type is unknown),
deduplication, and cap of five. It SHALL NOT alter ranking or auto matching.

Scenarios:
- `Operation Mole` SHALL retain returned `O.M Operation Mole`; an original-title
  interior match SHALL also qualify.
- One-character and empty normalized keys SHALL not enable contains matching.
- `4:18`, `418.`, `1917.`, `42!`, and `1.618` SHALL take ByTitle with punctuation
  intact in the request. Comparison normalization SHALL retain their digits.

## F4 — Leading banners

Release parsing SHALL remove a structurally delimited leading domain banner
before punctuation normalization, with optional wrappers/www and a TLD of two
or more letters. A closing wrapper or at least two space/hyphen separator
characters SHALL be required. The narrow already-normalized form
`www <label> <TLD> - ` SHALL also be recognized. Nonbanner title text SHALL survive.

Scenarios:
- `www.1TamilMV.ceo - Film`, `[example.technology] Film`, and
  `example.org - Film` SHALL yield title Film.
- `www 1TamilMV ceo - 418 TRUE` SHALL yield `418 TRUE`, without inventing a year
  or deleting TRUE.
- `O.M Operation Mole`, `S.H.I.E.L.D.`, hyphenated titles, domain-shaped text
  without the required separator, and the protected Naruto-Kun collision
  SHALL not be consumed as banners.
- URL/magnet guards SHALL run before banner stripping and continue to block
  third-party automatic search requests.

## F5 — Release year retention and preference

The parser SHALL retain standalone grouped year candidates before deleting
parenthetical qualifiers, prefer the first eligible standalone grouped release
year over bare candidates, and otherwise retain the last eligible bare release
year rule. Grouped means parentheses, square brackets or braces containing only
a year and whitespace. Eligibility SHALL use title-position evidence (F7) and
the existing 1900..current-year+1 policy within supported 19xx/20xx years.
Clean and raw variants SHALL agree on structural release-year selection.

Scenarios:
- `Film (2026) Telugu HQ HDTS` SHALL yield Film, 2026; square/braced forms SHALL
  retain the same year.
- `Film (2020) 2021 1080p` SHALL select 2020; `Film 2020 2021 1080p` SHALL select
  2021. A grouped year without preceding title content is not eligible.
- `Blade.Runner.2049.(Hindi.Dubbed.2024).2017.1080p.mkv` SHALL retain title
  Blade Runner 2049, year 2017; the year inside a dub annotation is not standalone.
- Future-year acceptance/rejection SHALL be tested relative to the current
  year, not a frozen research cutoff.

## F6 — Technical boundaries without title-word deletion

The release parser SHALL recognize bounded source, resolution, codec, audio and
size families listed in the proposal, including compound and decimal forms
before punctuation normalization. It SHALL preserve existing supported tokens.
HQ SHALL only extend an adjacent recognized source boundary. Weak short tokens
and standalone channel numbers SHALL require a release year or adjacent strong
technical evidence plus a preceding title. A token at title start SHALL not
create an empty title. Language words SHALL NOT be unconditional boundaries;
only text after a proven year/technical delimiter is a metadata suffix.
`parseReleaseDetails` SHALL retain existing resolution/episode display behavior.

Scenarios:
- `Film HQ HDTS 720p`, `Film DVDScr`, `Film HDRip`, `Film Xvid`, `Film AV1`,
  `Film TrueHD`, `Film 576p`, and `Film 1.7GB` SHALL yield Film with unknown year.
- `Film HQ CAM 720p` SHALL yield Film; `Cam`, `The Web`, `HQ Clean`, and `Film HQ`
  SHALL retain their title words. Embedded token-like letter sequences SHALL
  not cut titles. `Film 2026 TS` SHALL recognize a metadata suffix.
- `The Telugu Film` SHALL remain intact; `Film (2026) Telugu` SHALL yield Film,
  2026. `Film Telugu HDTS` SHALL retain Telugu before the technical boundary.
- Both `O M Operation Mole (2026) 1080p Telugu DVDScr - x264 - AAC - 7.5GB`
  and `www.1TamilMV.ceo - O.M Operation Mole (2026) Telugu HQ HDTS - 720p - x264 - HQ Clean - AAC - 1.7GB.mkv`
  SHALL produce normalized title O M Operation Mole, year 2026.
- Existing 4K→2160p, 1080p/1080i/720p/480p labels and SxxExx extraction SHALL
  remain unchanged; source-only releases SHALL not gain a resolution label.

## F7 — Numeric titles and bounded years

A release year SHALL have separators on both sides and meaningful preceding
title content, excluding wrappers. A sole numeric title, even wrapped, SHALL
remain a title with unknown year; leading numeric titles SHALL survive a later
release year or explicit episode marker.

Scenarios:
- `Film A2012B` SHALL retain the entire title with unknown year.
- `[1917]`, `(1917)`, `1917`, `2012`, and `1917.` SHALL retain their numeric title
  without a release year. `(1917) (2019) 1080p` SHALL yield title 1917, year 2019.
- `1917.2019.1080p` SHALL yield 1917, 2019; `1923 S01E01` SHALL yield 1923,
  unknown year, series. `300` and `42` SHALL remain titles.

## F8 — Recovery from blank cleanup

When clean parsing erases all title content but existing raw parsing retains a
usable title, `parseMediaName` and the first query in `mediaQueryVariants` SHALL
use that raw result. Recovery SHALL not bypass input guards or promote known
metadata-only text. Existing nonblank variant order and deduplication SHALL be
preserved; no new variant family or request expansion SHALL be introduced.

Scenarios:
- `(Alien)` and `(Alien) 1080p` SHALL yield Alien and permit a fake-transport
  catalog lookup instead of returning a blank first query.
- Empty input, URL/magnet input, `(Hindi Dubbed)` alone, and technical-only text
  SHALL not be promoted as recovered titles.
- Existing nonblank edition/article variants SHALL keep their order.
- `[REC]`/arbitrary bracket-group disambiguation is explicitly outside this
  recovery contract because current raw parsing also removes that span.

## F9 — Numeric punctuation versus absolute episodes

Weak terminal-number inference SHALL require alphabetic content in the proposed
series title. Numeric/punctuation-only prefixes SHALL not be sufficient.
Explicit season/episode recognition SHALL retain precedence.

Scenarios:
- `1.618` and `1-618` SHALL retain all title digits after ordinary release-name
  punctuation normalization, with unknown year/type; neither SHALL become
  series title 1. `4:18` and `418.` SHALL remain numeric titles.
- `Show 1071` and `One Piece - 1071` SHALL retain absolute-episode behavior;
  `The 100` SHALL remain a title; `1917 S01E01` SHALL remain a series query.

## Operational and scope invariants

The implementation SHALL add no dependencies or Android coupling to core and
SHALL retain the existing manual 160-character limit, automatic exact-match
policy, ID support, candidate cap, credential handling and bounded variants.
No normalization-based manual query cleaning, arbitrary TRUE/Clean/group-word
removal or ranking changes SHALL be introduced.

Enrichment SHALL continue using download.name (DownloadsViewModel.kt:951).
The no-year name `www 1TamilMV ceo - 418 TRUE` still leaves `418 TRUE` after
banner removal; successful auto-enrichment SHALL NOT be asserted. Shipping
requires a new hydration attempt for that row; existing pull-to-refresh miss
bypass supplies the retry without changing cache policy.
