# TMDB similar cases — plan only

Audited baseline `91349f6`, Trove 0.1.41, using the required 2026-11-22 regex
research. Only this summary and the three OpenSpec files are written; no Kotlin,
build, test, lint, version or remote git changes. Evidence paths below are
relative to `core/src/main/java/app/trove/debrid/core/`.

**Fixes planned:**

- F1: `TmdbResolver.kt:119-123` rejects untyped numeric titles before requests.
  Return ByTitle before Int conversion, including zero/overflow when untyped;
  preserve typed IDs and explicit-link validation.
- F2–F3: `TmdbResolver.kt:296-298` filters two-character prefixes and interior
  matches after requests. Enable both only manually, from two normalized
  characters; retain one-character exact matches, order and five-result cap.
- F4–F6: `MediaMetadata.kt:89,158-167` retains domain banners, deletes grouped
  years before extraction and misses technical families. Strip anchored banners
  first, retain standalone grouped years, then scan bounded source/resolution/
  codec/audio/size tokens. HQ is adjacent-source-only; languages are suffix-only.
- F7: `MediaMetadata.kt:84,165-167` accepts embedded years and empties `[1917]`.
  Require separators and real preceding title content; preserve grouped numeric
  titles and share year selection across variants.
- F8: `MediaMetadata.kt:129,148,160,245` blocks a blank primary before an existing
  raw fallback can help `(Alien)`. Recover that usable raw title first, retaining
  URL/magnet and metadata-only guards.
- F9: `MediaMetadata.kt:159,180-186` turns `1.618` into series title `1`.
  Require alphabetic title evidence for weak absolute-episode inference;
  preserve explicit episode and existing anime behavior.

**Deliberately excluded:** manual query cleaning, cap/ranking changes, auto fuzzy
matching, and new IMDb-input support. `4:18`, `418.`, `1917.` already search as
manual titles; bare auto `1917`/`2012` already survive. Blank/overlong input and
invalid explicit IDs retain their guards. No arbitrary deletion of TRUE, Clean,
languages or groups: without a reliable delimiter they may be title words.
`Class of 1984`, `Room 237`, and `[REC]` need separate ambiguity rules to avoid
breaking release-year, anime and group handling. The proposal records evidence
and decisions for each candidate, including empty normalized inputs and links.

**Minimum fix:** small pure helpers and precompiled patterns, no dependencies,
no new query variants, no general span engine. Span annotation remains a separate
optional future proposal for conflicting metadata/title spans; no included fix
requires it, and it cannot identify TRUE as noise without additional evidence.

**418 correction:** enrichment reads `download.name`, not a file name
(`app/src/main/java/app/trove/debrid/ui/downloads/DownloadsViewModel.kt:951`).
`www 1TamilMV ceo - 418 TRUE` has no year and still leaves `418 TRUE` after banner
stripping, so auto-enrichment is not guaranteed. After shipping, pull-to-refresh
must trigger another hydration attempt; existing miss bypass is at VM:263-264.
Later work adds focused regressions in the two existing core test files and
reviews the full diff before compilation, then runs the authorized test/lint gates.
