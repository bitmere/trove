# library-performance

## Requirements

### Requirement: Metadata hydration does not emit once per row
Hydration SHALL accumulate results and publish in batches (every ~10
rows or ~200 ms) instead of one StateFlow emission and full map copy
per resolved row.

#### Scenario: A large library hydrates with bounded emissions
- WHEN 3,000 rows hydrate
- THEN the number of state emissions is in the hundreds, not the
  thousands, and no emission copies the whole map per row.

#### Scenario: A stale batch never publishes
- WHEN a refresh or account switch happens mid-hydration
- THEN batches from the old load are dropped.

### Requirement: Search and sort do not run on Main per keystroke
Search/sort preparation SHALL run on a background dispatcher with a
150–250 ms results debounce (immediate clear/reset), reusing one
normalized preference set and precomputed sort keys.

#### Scenario: Typing does not re-sort three tabs on Main per keystroke
- WHEN the user types in search
- THEN filtering and sorting happen off Main and the UI stays responsive.

#### Scenario: Clear and reset are immediate
- WHEN the user clears the query or taps Reset
- THEN the result updates immediately, not after the debounce.

### Requirement: Card derivation is memoized
The per-card identity and added-date formatting SHALL be memoized so a
name is not re-parsed and a formatter is not rebuilt on every
composition.

#### Scenario: A rename is not stale
- WHEN a download is renamed
- THEN the card shows the new parsed title (memo key includes the name).

### Requirement: TorBox list loads run in parallel
The three `mylist` calls (torrent, web, usenet) SHALL run concurrently
instead of sequentially, keeping usenet failure isolation.

#### Scenario: Refresh latency drops on a slow network
- WHEN the three kinds are fetched
- THEN total latency is near the slowest single call, not the sum of
  three.

### Requirement: Posters decode outside the loader mutex
Poster decode SHALL happen outside the `PosterLoader` mutex so visible
posters load in parallel instead of one at a time.

#### Scenario: Several visible posters decode concurrently
- WHEN a list with many posters scrolls into view
- THEN decodes proceed in parallel, with no duplicate decode of the
  same URL in flight.
