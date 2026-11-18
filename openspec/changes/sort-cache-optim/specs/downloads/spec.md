## ADDED Requirements

### Requirement: Persisted client-side library sorting
Downloads SHALL offer Newest first (default), Oldest first, Name A to Z,
Name Z to A, Largest first, Smallest first and Progress highest first beside
search, using only already parsed mylist data and making no extra provider calls.
The selection SHALL persist in private SharedPreferences across restarts.
Sorting SHALL follow tab and case-insensitive search filtering. Equal values
SHALL retain provider order. Missing dates and sizes SHALL come last in both
directions. Name comparisons SHALL ignore case using Locale.ROOT. Invalid saved
choices SHALL default to Newest first. A late preferences read SHALL NOT replace
a newly selected choice; successive writes SHALL preserve selection order.

#### Scenario: Sort a filtered tab and refresh
- **WHEN** the user searches in Torrents, selects Largest first and refreshes
- **THEN** only matching torrent rows are sorted by descending size
- **AND** the selection and search remain in place after refresh.

#### Scenario: Dates absent from response
- **WHEN** either date direction is selected
- **THEN** rows without created_at follow dated rows, preserving their order.

### Requirement: Bounded public poster disk caching
The app SHALL retain its existing 4 MB in-memory bitmap cache as first level,
use cacheDir/posters as second level, and use network only on a disk miss or
invalid entry. Only URLs accepted by publicPosterUrl SHALL enter the disk path.
Filenames SHALL be SHA-256 hex hashes with no URL text. Encoded entries SHALL
be at most 4 MB, with at most 32 MB total and least-recently-used eviction.
Disk I/O and sampled bitmap decode SHALL run off Main. Disk failures SHALL be
silent and allow network fallback. Disk hits SHALL be decoded without network.

#### Scenario: Cold start cache hit
- **WHEN** the process restarts and requests an already cached public poster
- **THEN** its disk bytes decode to the poster without a network request.

#### Scenario: Corruption or disk failure
- **WHEN** a cached entry fails decoding, reading or writing
- **THEN** the network path remains available without a user-facing error
- **AND** corrupt cached bytes are discarded and valid fetched bytes may replace them.

#### Scenario: Cache bounds
- **WHEN** adding an entry would exceed 32 MB
- **THEN** the oldest last-access entries are removed before writing
- **AND** bytes larger than 4 MB are never stored or decoded.

### Requirement: Reuse unchanged row metadata
A refresh SHALL retain metadata for matching download kind, ID and name and
SHALL skip resolving those successful entries again. Renamed/new rows SHALL
resolve; removed rows SHALL lose metadata. Misses SHALL continue through the
existing retry policy. Hydration SHALL NOT delay initial list availability.

#### Scenario: Transfer-only refresh
- **WHEN** progress or speed changes but kind, ID and name stay unchanged
- **THEN** successful metadata is retained without another resolveMetadata call.

#### Scenario: Rename or previous miss
- **WHEN** the name changes or the previous lookup returned no metadata
- **THEN** the next refresh attempts metadata resolution again.
