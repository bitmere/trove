# Source review before compilation

## Visible change

Before: header `MKV` (labelLarge), kind and status on the right.
After: 64 x 96 dp poster or placeholder, titleMedium title (two lines, ellipsis),
optional year/type, kind and status. No format text in the header.
Original filename still appears below, titleMedium, two lines with ellipsis.

Example: `Baththa.2026.1080p.WEB-Rip.HEVC-GROUP.mkv` immediately displays
`Baththa` / `2026` / `Web · Ready`. A confident Cinemeta match adds its poster,
canonical name and `Movie` type. A miss keeps the parsed identity.

## Changed source and tests

- core/.../MediaMetadata.kt (new): local release parser; public URL validation;
  Cinemeta catalog/detail client, exact title/year matching, ambiguity fallback;
  256-entry LRU of results/misses, one-minute failure retry, cancellation.
- data/TroveRepository.kt: default nullable metadata seam for existing fakes.
- data/DebridRepository.kt: owns resolver and dispatches enrichment onto IO.
- ui/downloads/DownloadsViewModel.kt: publishes rows first, then hydrates metadata;
  cancels stale refresh, preserves existing identities for unchanged rows;
  metadata failures never become list errors. Sign-out clears visible metadata.
- ui/downloads/DownloadsScreen.kt: passes row metadata to the card.
- ui/downloads/DownloadPresentation.kt: parsed fallback identity.
- ui/downloads/DownloadCard.kt: poster/title/year/type/kind/status template.
- ui/downloads/MetadataPoster.kt (new): off-Main HTTP/decode, serialized loads,
  4 MiB bitmap cache, sampled 384px maximum dimension, bounded failure cache.
- res/values/strings.xml: Movie and Series resources.
- core tests/MediaMetadataTest.kt (new): parser examples, series and numeric titles,
  lookup/detail, encoding, no auth headers, concurrent cache reuse, misses,
  ambiguity, year/title mismatch, malformed replies, retries, eviction,
  cancellation, unsafe artwork URLs.
- app tests/DownloadPresentationTest.kt: fallback and resolved identity.
- app tests/DownloadsMetadataTest.kt (new): nonblocking hydration, stale refresh,
  nonfatal metadata failure.
- androidTest/DownloadCoverTest.kt (new): parsed/resolved identity, kind/status,
  format absent, two-line ellipsis title contract.
- OpenSpec proposal/tasks/spec/validation/review documents.

No dependency, permission, version, signing, workflow, provider playback or tab
measurement changes. Core stays Android-free. Synthetic test data only.

## Limits and verification plan

This workspace contains an empty .git directory. Base dbe9a786 and a Git diff
cannot be independently verified here; review the listed sources directly.
No compile/test/lint/device run yet: AGENTS.md requires pre-compile review.
After review: check JDK/SDK/wrapper versions; focused MediaMetadataTest,
DownloadPresentationTest and DownloadsMetadataTest; then :core:test and
:app:testDebugUnitTest. Run :app:lintDebug once before any approved PR.
Use --profile on verification builds. No local release signing or installation.

Phone checks still required: cold list load, poster success/failure/offline,
long names and large font scale, refresh and scroll cache reuse, all three kinds,
Play/file picker/share/encrypted storage unchanged. Compose tests need a device.
Live search/meta endpoint testing was blocked in this environment; manifest and
protocol/source checks succeeded. Conservative matching can miss aliases or
transliterated titles; it deliberately retains parsed identity for these cases.
Caches are session-only and bounded, so eviction/process restart can re-fetch.
The bitmap cache/decode are bounded; the existing binary HTTP transport buffers
an encoded image before the loader's 4 MiB acceptance check.
