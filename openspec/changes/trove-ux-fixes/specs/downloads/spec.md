## ADDED Requirements

### Requirement: Compact factual download rows
Rows SHALL limit filenames to two ellipsized title-medium lines and use a small format label. Full names SHALL remain available in details. Rows SHALL show size/count and localized creation date when present, plus existing progress/speed/ETA/swarm counts while downloading.

#### Scenario: Long ready MKV
- **WHEN** a ready item has a long release filename
- **THEN** the row remains bounded and its details retain the complete name.

### Requirement: Findable additions without disruptive refresh
Lists SHALL sort by creation time descending, preserve input order for equal/missing dates, put missing dates last, retain tab/search filtering, and confirm successful adds. Post-add refresh SHALL retain rows and omit the pull indicator. Classification and credential access SHALL run off Main; the add spinner SHALL occupy reserved space.

#### Scenario: Add succeeds
- **WHEN** TorBox accepts an addition
- **THEN** confirmation identifies the destination and a silent refresh updates the library without a loading flash.
