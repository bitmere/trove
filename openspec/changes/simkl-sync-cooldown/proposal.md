# SIMKL sync cooldown (30 minutes)

## What the user asked for

SIMKL needs to be cached so Trove does not consume free API quota.
The free SIMKL tier allows 500 calls per user per day, and every sheet
open and pull-to-refresh currently spends at least one
`GET /sync/activities` call to check whether anything changed.

## Verified current state

All line numbers are main @ `10ae971d` (Trove 0.1.33).

- The full SIMKL library is already persisted to disk
  (`core/.../SimklDiskCache.kt`, `simkl.json`, snapshot with entries +
  activities watermark + `lastCheckedMs`) and badges are recomputed
  from the in-memory index (`DownloadsViewModel.refreshSimklStatuses`,
  O(rows), zero API calls).
- `SimklSyncEngine.sync()` (`core/.../SimklSyncEngine.kt`) is
  activities-gated: one `GET /sync/activities`; if the `all`
  watermark is unchanged it stops there (1 call, no all-items
  fetches); changed buckets only, with `date_from` delta. No timer,
  no background polling, no sync on app start.
- Sync triggers: sheet open (`DownloadsViewModel.openDownload` ->
  `requestSimklSync`, `:996`) and pull-to-refresh
  (`refreshFromUser` -> `requestSimklSync`, `:242`), plus Settings
  "Sync now" (`SettingsViewModel.syncSimklNow` ->
  `repository.syncSimkl()`). All three go through
  `DebridRepository.syncSimkl()` -> `simklEngine.sync()` with no
  distinction between automatic and explicit calls.
- `SimklSnapshot.lastCheckedMs` is persisted on every sync outcome
  (unchanged, changed, failed, quota-blocked), so it survives app
  restarts and is a reliable "last checked" clock.

## The gap

Automatic triggers have no memory: opening ten sheets in a row
spends ten activities calls even when the last check was seconds
ago and nothing could have changed. The snapshot already holds
everything the badges need between checks.

## Change

- Automatic syncs (sheet open, pull-to-refresh) hit the network
  only when the last check was more than 30 minutes ago.
  Between checks, badges keep coming from the disk snapshot.
- Manual "Sync now" in Settings always bypasses the cooldown and
  goes live.
- First-ever sync (no snapshot) always runs.
- No timer, no background polling: the cooldown is a gate on the
  existing on-demand triggers, not a schedule.

## Seam

The engine owns the rule: `SimklSyncEngine.sync(force: Boolean =
false)` returns a new `SimklSyncResult.CooledDown` when a snapshot
exists and `now() - lastCheckedMs < SYNC_COOLDOWN_MS` (30 min),
with zero network calls and no snapshot write. The quota block is
still checked first, so a spent quota keeps its existing behaviour.
The repository passes `force` through (`syncSimkl(force: Boolean =
false)`); `CooledDown` bumps no snapshot version. Automatic
callers keep the default; Settings "Sync now" passes `force =
true`.
