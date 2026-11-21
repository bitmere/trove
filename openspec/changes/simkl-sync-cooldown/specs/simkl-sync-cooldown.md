# simkl-sync-cooldown

## Cooldown gate

- Automatic SIMKL syncs (sheet open, pull-to-refresh) make no
  network call when the persisted snapshot was last checked less
  than 30 minutes ago. The result is `SimklSyncResult.CooledDown`
  and the snapshot is left untouched.
- A first sync with no snapshot always runs, regardless of the
  cooldown.
- `sync(force = true)` bypasses the cooldown. Settings "Sync now"
  always forces; automatic triggers never force.
- The daily-quota block is evaluated before the cooldown: a spent
  quota still reports `Failed(QUOTA)` even inside the cooldown
  window.

## Badges during the cooldown

- Row and sheet badges keep being served from the disk snapshot
  (loaded into the in-memory index) while automatic syncs are
  cooled down. No badge ever waits on a network call.

## Persistence

- The cooldown keys off the snapshot's persisted `lastCheckedMs`,
  so it survives app restarts. `lastCheckedMs` is updated on
  every sync outcome that reaches the network, exactly as today.
