# trove-sync-freshness

## Pagination

Accepted rows may be fewer than the raw response count. Offset advancement and
end detection use raw count, including duplicates and unconvertible elements.
A full page never establishes completeness. Page size remains 100.

## Requests and feedback

Only the current generation's owning per-tab job publishes results or clears
loading. Cancellation stops silently. Errors retain loaded rows, expose a
snackbar and a retry state even when search has no matches. Server detail is
used when present in the typed error. Failed head retries replace and bypass;
continuation retries retain offset and bypass mode. No automatic error retries.

## Freshness

Fresh refresh traverses only the visible tab to end/error, sequentially, with
300 ms between pages and progress throughout. Search and continuations inherit
generation freshness. New refresh/sort/provider changes invalidate old work.
Foreground and tab activation revalidate a stale head with replacement and
bypass, bounded to one attempt per tab per 600 s, with no concurrent refresh,
page load or search sync. Signed-out/provider-less state cannot revalidate.

## Tests

Cover 2,400+ rows, late-page newest items, raw versus accepted counts, stale
responses, cancellation cleanup, fresh/non-fresh continuations, failed-head
replacement retries, visible errors with zero matches and revalidation bounds.
