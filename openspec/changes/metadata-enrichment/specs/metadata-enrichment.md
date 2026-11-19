# metadata-enrichment

## Requirements

### Requirement: Details sheet shows the synopsis and facts Cinemeta already returns
The details sheet SHALL show the synopsis, genres, runtime, and IMDb rating
from the Cinemeta detail response the resolver already fetches, with no
additional network call.

#### Scenario: A resolved movie shows its synopsis
- WHEN a download has resolved Cinemeta metadata with a description
- AND the user opens the details sheet
- THEN the sheet shows the synopsis and a facts row (year, genres,
  runtime, IMDb rating).

#### Scenario: Missing fields degrade gracefully
- WHEN the detail response lacks a description or a rating
- THEN the sheet omits that field and shows the remaining facts; the
  list and card are unaffected.

#### Scenario: The resolved IMDb id is retained
- WHEN the resolver validates an IMDb id
- THEN the returned metadata carries that id for future use (TMDB
  bridging, per-title overrides).

### Requirement: Series details show an episode list
The details sheet for a series SHALL list its episodes (season, episode,
title, released) from the detail response's `videos[]`, highlighting the
episode matching the parsed release details.

#### Scenario: Episode matching the download is highlighted
- WHEN the download filename parses to S02E05
- AND the series metadata contains that episode
- THEN the episode list highlights S02E05.

#### Scenario: A long series does not compose hundreds of rows at once
- WHEN a series has hundreds of episodes
- THEN the list renders a capped window (current season + scroll) rather
  than all episodes at once.

### Requirement: Recall improves without weakening the match gate
The resolver SHALL retry a missed exact match with year ±1 and SHALL strip
a trailing absolute episode number before matching, while keeping the
exact-title and single-candidate rules.

#### Scenario: Festival-year drift resolves
- WHEN the parsed year is off by one from the catalog year
- AND the normalized title matches exactly with a single candidate
- THEN the metadata resolves.

#### Scenario: An anime absolute number resolves
- WHEN the filename has no SxxExx token and ends in a bare episode
  number
- THEN the number is stripped before matching and the series resolves.

#### Scenario: A year-like sequel number is not treated as a year
- WHEN the filename is `Blade Runner 2049` with no release year
- THEN the title stays `Blade Runner 2049` and no wrong year is assumed.

### Requirement: Resolved metadata survives process death (durable cache)
Resolved metadata SHALL be stored on disk (one JSON file per title under
`filesDir/metadata/`, SHA-256 filename, 512 entries / 2 MB, 180-day TTL,
LRU eviction) and shared across providers, so a cold start shows cached
titles immediately, including offline.

#### Scenario: Cold start shows cached metadata instantly
- WHEN the app cold-starts with a library whose titles were resolved
  before
- THEN rows show the cached title/poster with no network wait.

#### Scenario: Offline cold start degrades to the cached value
- WHEN the device is offline and metadata is cached
- THEN the cached value is shown; when nothing is cached, the parsed
  title and placeholder poster are shown and the list never blocks on
  the network.

#### Scenario: Stale entries revalidate in the background
- WHEN a cached entry is past its TTL
- THEN the stale value is shown immediately and a background re-fetch
  replaces it when it succeeds.

#### Scenario: No-match results expire
- WHEN a title had no match
- THEN the no-match result expires within an hour instead of lasting
  for the process lifetime, so a title Cinemeta gains later can resolve.

### Requirement: The user can correct a wrong or missing match
The details sheet SHALL offer up to ~5 Cinemeta candidates on an
ambiguous or missing match, and the user's pick SHALL be stored per
download id with a way to clear it.

#### Scenario: A remake offers a choice
- WHEN two films share a title and year
- THEN the sheet lists both candidates and the user's pick is stored
  and applied to that download.

#### Scenario: Clearing an override restores the parsed title
- WHEN the user clears a stored override
- THEN the download falls back to the parsed title and re-resolves
  normally.

### Requirement: TMDB is opt-in with a per-user key only
TMDB SHALL be usable only when the user enters their own TMDB key in
Settings (stored encrypted); the app SHALL never ship a shared key, and
TMDB attribution SHALL be shown in About/Settings.

#### Scenario: No key means no TMDB calls
- WHEN no TMDB key is stored
- THEN no TMDB request is made and Cinemeta remains the only source.

#### Scenario: The IMDb id bridges to TMDB in one call
- WHEN a TMDB key is stored and a download has a resolved IMDb id
- THEN TMDB is queried via `/find/{imdbId}` before falling back to
  title search.

#### Scenario: Attribution is present
- WHEN TMDB is enabled
- THEN the About/Settings screen shows the TMDB logo and the "not
  endorsed or certified by TMDB" notice.
