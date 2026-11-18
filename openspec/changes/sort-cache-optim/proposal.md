# Download sorting, persistent posters and metadata reuse

Offer client-side sorting beside Downloads search. Persist the selection in the
existing private `trove_accounts` SharedPreferences file, under `download_sort`.
Keep Newest first as the default. Sort after kind and search filtering; keep
provider order for equal values and unknown dates/sizes last in either direction.

Add a two-level poster cache: the existing 4 MB sampled bitmap LRU, then encoded
bytes in `cacheDir/posters`, then network. Disk entries use SHA-256 URL hashes,
are limited to 4 MB each and 32 MB total, and use last-access timestamps for LRU
eviction across restarts. Decode before accepting a disk hit; corruption or disk
I/O failure falls back to network silently. Cache only `publicPosterUrl` values.
All disk I/O and bitmap decoding run on Dispatchers.IO. No new dependency.

The selected optimization is to skip metadata resolution for unchanged rows with
retained successful metadata. Renamed rows resolve again; misses keep retrying via
the existing Cinemeta retry policy. Initial rows remain available before hydration.
No changes to DownloadCard visuals, playback, requestdl handling or API traffic.

## API evidence and limit

Read `/home/hatch/workspace/torbox-app/torbox-openapi.json` (read-only reference).
All three `/v1/api/{torrents,webdl,usenet}/mylist` endpoints exist, but their HTTP 200
JSON schema is `{}` with no response properties or examples. This file cannot
independently verify any field. The implemented options use only fields already
parsed by `TorBoxProvider.toDownload` and covered by repository fixtures:
`name`, `size`, `progress`, `created_at`. The parser also reads `download_speed`,
`eta`, `seeds`, `peers` and status inputs; these do not become sort options.
No inference that the OpenAPI file documents these response fields is made.

## Design tokens (existing theme, unchanged)

Paper #F7FAF6, Ink #171717, Hairline #E7E7E7, Jade #007D60, Chip #F2F2F2.
Use MaterialTheme color/shape/type roles and TroveSpace spacing; Space Grotesk
headings and Inter body stay unchanged. Keep the single library column, subtabs,
avatar settings and existing floating liquid-glass action cluster. Add only a
48 dp sort icon target and a stock Material dropdown next to the search field,
with resource-backed labels, current choice checkmark and accessible description.

## Validation ownership

Source-only implementation by Codex (GPT-6); coordinator owns all Gradle builds,
focused tests, full JVM suite and lint. Phone verification remains outstanding.
No Git commands, version changes, signing, artifact, PR or remote actions.
