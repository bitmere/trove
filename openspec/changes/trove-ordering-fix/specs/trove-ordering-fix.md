# trove-ordering-fix

## Date ordering

NEWEST orders non-null creation dates descending; OLDEST orders them ascending.
Dates decide regardless of preferred-release-word score. Null dates sort last
in both directions. Preference score descending breaks equal-date ties,
including two null dates. Equal dates and equal scores preserve input order;
row IDs never substitute for creation time. Other sorts retain preference-first
ordering. Existing saved words apply without migration or reset; empty words,
preference scoring and exclusion filtering retain their behavior.

## Coverage

- C1: Refresh, sort changes and foreground revalidation retain loaded live rows
  unless server evidence establishes deletion. Failed pages never truncate.
- C2: Sort changes traverse the visible tab to the end, restoring at least its
  previous row coverage and completeness, except confirmed deletions. Reuse
  300 ms inter-page pacing and generation/owner guards. Inactive tabs keep their
  rows and reload on visit. Ordinary refreshes replacing loaded live coverage
  also traverse; initial loads and snapshot previews retain lazy paging.
- C3: A successful foreground head request publishes new head rows first in
  server order, then the previous tail, deduplicated by kind and ID. Previous
  head rows (first PAGE_SIZE loaded rows) absent from the new head are removed.
  Keep previous endReached and clamp previous nextOffset to the merged row
  count. Retain rows beyond the head until a full traversal. A failed-head Retry
  uses the same reconciliation mode. An interrupted replacement traversal can
  instead restart reconciliation through a full traversal.
- C4: Cancellation or a newer refresh/sort/provider generation drops stale page
  results. Previously loaded rows survive. During replacement traversal, retain
  old rows alongside newly fetched rows until the end; only then discard absent
  rows. Continuation retries retain accumulated traversal evidence.

Offset pagination against TorBox's unspecified-order list can still skip an
unseen row, and a row displaced out of the head is indistinguishable from a
head deletion. Resolving these provider consistency limits is out of scope.

## Snapshot and retry contract

A snapshot is a cached preview, never authoritative evidence of current server
completeness. Show restored rows immediately, but always revalidate its head on
visit/foreground. A successful snapshot page-0 replacement discards stale
preview rows. This is distinct from preserving live coverage during refresh.
Snapshot tabs not currently visible restore immediately and revalidate on visit.

The persistence boundary caps each tab at 100 rows. If truncation occurs, save
endReached=false and nextOffset=saved row count. Genuinely smaller or equal
live tabs retain their coverage metadata, including a complete 80-row tab.
Legacy snapshots also revalidate before their saved cursor can be used.

Keep the snapshot timestamp at restore. At request time, a preview at least
600 seconds old revalidates with cache bypass; a newer preview may use cache.
Inactive previews age while waiting for their first visit. Record head validation
only after successful publication; a failed head does not consume the 600-second
validation interval, so another foreground/visit can retry immediately. Live
head checks remain bounded by successful validation timestamps.

## Tests

Cover default, custom and empty preferences; both date directions; null dates;
preference ties and stable input ties; non-date preference-first sorts. Exercise
both core sorting and prepared VM ordering. The performance test's equal-date
preference ordering remains valid unchanged.

Load 250 rows over three pages, change sorts, and retain 250 rows/endReached
with the page-three newest item first under NEWEST. Cover ordinary refresh
coverage, failed continuation and a superseding sort, and stale page ownership.
After complete traversal, add one head row and delete one head row: keep every
survivor, new row first, no deleted row, net 250 rows and completeness retained.
Cover head failure without truncation or retry throttling, stale snapshot
replacement, inactive restored tabs and the 600-second bypass boundary.
Persisting a complete 250-row tab must restore 100 rows with endReached=false
and nextOffset=100; a complete 80-row tab retains its metadata.
