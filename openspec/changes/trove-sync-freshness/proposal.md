# Sync freshness

## Problem

TorBox pages are deduplicated and converted before their length reaches the
repository. A full raw page can therefore falsely end pagination. Refresh
loads only page 0, despite TorBox documenting no list ordering. Page errors
and cancelled search requests can leave feedback missing or loading stranded.

## Change

1. Return accepted rows and raw count from core; paginate by raw count.
2. Surface errors through existing feedback; retry heads with replacement and bypass.
3. Use generation and per-tab job ownership for results and cancellation cleanup.
4. Sequentially reconcile the visible tab on fresh refresh, retaining bypass.
5. Revalidate stale visible heads on activation/foreground, at most every 600 s.
6. Cover counts, large libraries, retries, freshness and cancellation with regressions.

The 600 s bound follows the documented torrent list information update interval
in TorBox Get Torrent List; it is not a response-cache TTL. Automatic traversal
waits 300 ms between pages (at most 200/min, below 300/min per endpoint).

## Scope

Keep page size 100, existing boundaries and dependencies. No queue fetching,
request-update calls, reannounce, ordering parameters, polling, version changes,
or release actions. Other tabs are loaded/revalidated only on visit.
