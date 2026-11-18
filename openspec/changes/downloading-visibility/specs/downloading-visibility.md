# downloading-visibility

## Requirements

### Requirement: User-initiated refresh bypasses the TorBox list cache
A pull-to-refresh and the refresh after an in-app Add SHALL load the
list with `bypass_cache=true` on all three mylist calls (torrents,
web, usenet), so a just-added download appears immediately. Init
load, delete-confirm refresh, and the MainActivity share refresh
SHALL keep using the cached list.

#### Scenario: Pull-to-refresh sees a just-added torrent
- WHEN the user pulls to refresh after adding a torrent
- THEN the torrents, web, and usenet mylist requests all carry
  `bypass_cache=true`, and the new torrent is in the returned list.

#### Scenario: Refresh after Add is fresh
- WHEN an in-app Add succeeds
- THEN the following list load carries `bypass_cache=true` on all
  three mylist calls.

#### Scenario: Init and delete refreshes stay cached
- WHEN the list loads at init or after a confirmed delete
- THEN no mylist request carries `bypass_cache`.

### Requirement: Fresh list is TorBox-only by default
Other providers SHALL behave identically for a fresh list load and a
normal list load, because only TorBox caches its list endpoint.

#### Scenario: Non-TorBox provider ignores the fresh flag
- WHEN a fresh list load is requested from any provider other than
  TorBox
- THEN the provider performs its normal list load.

### Requirement: Early download states show as Downloading with progress
A download whose TorBox state is `metaDL`, `checking_resume_data`,
or starts with `stalled` (e.g. `stalled (no seeds)`) SHALL map to
DOWNLOADING, preserving the reported progress, speed, ETA, and swarm
counts, so the progress row renders.

#### Scenario: Magnet in metaDL shows progress
- WHEN a torrent reports `download_state` `metaDL` with progress 0.37
- THEN its status is DOWNLOADING and its progress is 0.37.

#### Scenario: Stalled torrent shows as Downloading
- WHEN a torrent reports `download_state` `stalled (no seeds)`
- THEN its status is DOWNLOADING.

#### Scenario: Resume check shows as Downloading
- WHEN a torrent reports `download_state` `checking_resume_data`
- THEN its status is DOWNLOADING.

### Requirement: Existing state mapping is unchanged
The existing mapping SHALL be preserved: `download_finished` and
`cached` / `completed` / `seeding` / `uploading` map to READY,
`downloading` to DOWNLOADING, `queued` to QUEUED, `paused` to PAUSED,
error/failure states to ERROR, and anything else to UNKNOWN. Seeding
SHALL NOT map to DOWNLOADING.

#### Scenario: Seeding is still not downloading
- WHEN a torrent reports `download_state` `seeding`
- THEN its status is READY, not DOWNLOADING.

### Requirement: No new status value
No new `DownloadStatus` value SHALL be introduced; the three states
above SHALL reuse DOWNLOADING, and the status-label mapping SHALL
need no change.

### Requirement: Single-item fetch still bypasses the cache
Fetching one download by id SHALL keep passing `bypass_cache=true`,
as today, regardless of the fresh-list change.
