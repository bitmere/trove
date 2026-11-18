## ADDED Requirements

### Requirement: Aligned update channel controls

The App updates header, installed version, channel label and selector SHALL share a start edge. Stable and Beta chips SHALL use the 8 dp small spacing token. Selecting a channel SHALL keep its existing behavior.

#### Scenario: Open App updates

- **WHEN** Settings shows the App updates section
- **THEN** the header aligns with the selector and the two chips have a visible 8 dp gap.

### Requirement: Recoverable player picker and readable feedback

The player picker SHALL offer retry in place on player-setting errors. Selectable player rows SHALL be at least 48 dp tall. Busy Add and key verification buttons SHALL render their spinners with their current content color. Add cache feedback and download-details preparation SHALL expose accessible status. Download cards SHALL allow dates and transfer labels to wrap.

#### Scenario: Player discovery fails

- **WHEN** the open picker reports an error
- **THEN** a Try again action requests discovery again without closing the picker.

#### Scenario: Playback preparation is active

- **WHEN** the details sheet is fetching files or resolving a selected file for playback
- **THEN** accessible preparation status is exposed and the selected file's Play control retains its filename label while showing progress.

#### Scenario: Add checks the cache

- **WHEN** a cache lookup begins and then finds a match
- **THEN** the checking and match messages are exposed through polite live-region semantics.
