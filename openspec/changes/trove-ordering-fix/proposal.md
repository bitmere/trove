# Ordering and library coverage

## Problem

Diagnosis is complete against main `ee007031accbda2e9c4c107bc37965e71b615bfc`
(Trove 0.1.40). Verified current state:

- `app/src/main/java/app/trove/debrid/ui/downloads/DownloadsViewModel.kt:1498`
  ranks preferred words before every chosen sort; core duplicates that rule in
  `core/src/main/java/app/trove/debrid/core/DownloadSort.kt:34`.
  `core/src/test/java/app/trove/debrid/core/ReleasePreferencesTest.kt:40`
  explicitly pins preference-first Newest.
- `app/src/main/java/app/trove/debrid/data/TroveRepository.kt:35` defaults to
  exclusions CAM/TS and preferences x265/REMUX. Saved preferences must remain
  intact. Newest is the default sort. Creation dates come from `created_at`
  (`core/src/main/java/app/trove/debrid/core/TorBoxProvider.kt:689`), not an
  update timestamp or row ID.
- `app/src/main/java/app/trove/debrid/ui/downloads/DownloadsViewModel.kt:447`
  resets pagination on sort and reloads only page 0. Its head revalidation at
  `:508` also replaces loaded coverage with page 0 (`:657`), and `:621`
  records validation before a request succeeds. Startup uses cache mode (`:239`).
- `core/src/main/java/app/trove/debrid/core/LibraryDiskCache.kt:65` encodes at
  most 100 rows while retaining live completeness and cursor metadata.
  The VM also truncates before saving (`DownloadsViewModel.kt:390`) and restores
  coverage verbatim (`:360`). Saved snapshot time is not checked for age.

## Change

1. Make dates primary for Newest/Oldest; preferences break date ties only.
2. Traverse sort changes and replacements of live coverage, retaining old rows
   until a completed traversal establishes deletions. Reconcile foreground heads
   without discarding the loaded tail; retain generation and owner guards.
3. Normalize truncated snapshot coverage at persistence, validate heads only
   after successful publication, and bypass cache for stale restored previews.
4. Add focused regressions using existing fake repositories and providers.

The reference date ordering is creation-date descending without release-word
priority. A provider's Default ordering is not assumed to mean Newest.

## Scope

No new dependencies, preference migration, settings reset, timestamp-parser,
metadata, enrichment, application identity or version changes. Keep core
Android-free, page size 100 and existing 300 ms traversal pacing. No build,
test, lint, remote git or PR actions in this implementation session; coordinator
reviews the diff and runs gates. Offset pagination against an unspecified-order
provider remains an acknowledged limitation.
