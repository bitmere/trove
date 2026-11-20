# Spec: Poster speed and enrichment provenance

## Requirement: Reserved poster slots use shared cached thumbnails

A valid poster URL uses a shared decoded bitmap and in-flight request
across the 48 × 72 dp row and the leading 80 × 120 dp details slot.
The details slot shrinks to 64 × 96 dp for narrow widths or large text.
Only the title and kind, size, and status sit beside it. The filename,
date, file count, transfer status, and synopsis keep the full width.

- Scenario: TMDB advertises w185 — the resolver uses it on a cache miss.
- Scenario: the CDN poster was previously cached at w342 or w500 — the
  new rendition reuses the existing bytes and the shared cache key.
- Scenario: a sheet has no valid poster URL — its header has no image slot.
- Scenario: the library refreshes during download or retry — the current
  request or wait continues while the row's validated URL remains valid.

## Requirement: Poster failures get bounded retry and quiet feedback

The neutral placeholder holds its size while loading. An exhausted lookup
shows a labelled retry button within the same slot.

- Scenario: network, timeout, or 5xx failure — the visible loader retries
  at most twice after the shared miss expires, with a bounded backoff.
- Scenario: 404, invalid URL, or rejected image — no automatic retry is
  scheduled. A valid URL can be retried immediately by tapping retry.
- Scenario: the visible rows are served and the viewport is idle — warm
  only resolved URLs from the subsequent three rows, serially. Scrolling,
  a new foreground image load, and changing the viewport cancel warmup.

## Requirement: Visible metadata publishes independently

The hydration queue keeps the four-lookup cap and prefers visible rows.

- Scenario: one visible sibling finishes before a slow sibling — the
  finished row receives its metadata and poster without waiting.
- Scenario: a row is replaced, its account changes, or its override is
  edited during lookup — a superseded result cannot replace current state.

## Requirement: Details show localized enrichment provenance

The source line uses bodySmall and onSurfaceVariant after the synopsis,
facts, and credits. It can stand alone and appears only after resolution.

- Scenario: enrichment resolves from one source or combines Cinemeta text
  with a TMDB poster or TMDB text with Cinemeta backfill — the line reports
  the corresponding source.
- Scenario: a legacy cache entry has source `cinemeta` or `tmdb` — the
  label says Cinemeta or TMDB used with details unknown, respectively.
- Scenario: no lookup returns a match — the details line says No match.
  If a lookup fails and no match is available, it says Unavailable.

## Requirement: Configured TMDB is the first automatic source

- Scenario: a key is configured and TMDB finds a unique, eligible record
  with an IMDb ID — publish its available poster before fetching Cinemeta
  by ID and merge only confirmed fields, including IMDb rating and episodes.
- Scenario: a strong TMDB result has no verified common ID — keep its
  poster and metadata without attempting a title-only merge. When TMDB
  misses, use the Cinemeta result if available.
- Scenario: the key is enabled or cleared — resolve in the new order even
  if an opposite-order entry is fresh. Clearing the key stops TMDB calls.
- Scenario: TMDB returns 429 — fall back to Cinemeta and suppress new TMDB
  requests until Retry-After or the default cooldown has elapsed.
