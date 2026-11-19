# Library restore on cold start

## Snapshot

- `LibrarySnapshot`: the provider service name the rows belong to, a
  `savedAt` timestamp, and per-tab loaded rows (max 100 rows per tab,
  300 total) with each tab's `endReached` and `nextOffset`.
- Rows are stored as `DebridDownload` without `files` (opening a row
  with empty files already triggers `loadFiles()`) and without live
  fields (download speed, ETA, seeds, peers). The file carries no
  credentials, keys, or bearer links.
- Storage is one JSON file under `filesDir/library-cache/`, written
  atomically (temp file + rename), with a schema version. A corrupt or
  wrong-version file is deleted on read and treated as no snapshot.
  The cache lives in Android-free core and follows the `MetadataDiskCache` pattern.

## Restore

- On ViewModel init, the snapshot is loaded on IO before the first
  refresh. When its service matches the current connection and it holds
  rows, the rows are published into `pagedByTab` (loaded rows,
  `nextOffset`, `endReached`, `visited = true`) and `downloads` with
  `loading = false`, so the library renders immediately with no network.
- The background refresh then runs as today, except rows are already
  present, so only the pull indicator shows (`refreshing = true`,
  `loading = false`); loaded rows stay visible until page 0 replaces
  them. A failed background refresh keeps the cached rows and shows the
  load error, as a refresh failure does today.
- A missing, corrupt, or mismatched snapshot behaves exactly as today:
  full-screen spinner until the first page lands.
- The saved scroll anchor restores against the restored rows through the
  existing `pendingAnchor` mechanism; with no cached rows it behaves as
  today (drops when the tab finishes loading without the anchor row).

## Saving

- Each successful page publish schedules a snapshot of the union of
  loaded rows, capped. Writes are debounced for 500ms and run on IO.
  The UI publishes rows immediately and ignores write failures.
- A restored tab reloads page 0 before appending. Its saved offset may
  cover more rows than the cached prefix.
- The snapshot is keyed by provider service name. Swapping the
  credential for a different account on the same service shows the old
  rows until the background refresh lands; signing out leaves the file
  inert (the signed-out state never restores it).

## Repository seam

- `TroveRepository` gains `loadLibrarySnapshot()` and
  `saveLibrarySnapshot()` with default no-op implementations, so
  existing fakes compile unchanged. `DebridRepository` implements them
  via `LibraryDiskCache`, constructed like `MetadataDiskCache`.
