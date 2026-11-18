# ADDED Requirements

## Media identity in download headers
The header SHALL show a locally parsed title and optional year immediately,
then resolved title, poster, year and type from keyless Cinemeta when confidently
matched. It SHALL NOT display a format label. Kind and status SHALL remain visible.
Titles and original names SHALL use at most two lines with ellipsis.

### Scenario: release names
Baththa.2026.1080p.WEB-Rip.HEVC-GROUP.mkv yields Baththa / 2026.
Baahubali.The.Beginning.2015.1080p.BluRay.x264-GROUP.mkv yields
Baahubali The Beginning / 2015.

## Safe, bounded enrichment
Only locally parsed titles SHALL be searched; no credential or bearer link SHALL
be sent. Exact normalized title and matching supplied year SHALL be required;
ambiguous results SHALL fall back. Network/error/missing poster SHALL NOT prevent
list display or playback. Metadata and sampled artwork caches SHALL be bounded;
refresh/scroll SHALL reuse entries. I/O SHALL run off Main outside composition.
Cancellation SHALL propagate. Core SHALL remain Android-free.

### Scenario: repeated title
Two releases with the same parsed title/year/type reuse a cached result or miss,
without repeated metadata HTTP calls until eviction or failure retry expiry.
