# Poster regression: restore cached posters on upgrade

## What the user asked for

After updating to 0.1.30 over the previous install, posters load for
very few rows — fewer than before the update. The user asked to fix all
three parts of the plan: restore the cached posters, close the artwork
fallback gaps, and keep the last good poster on screen while metadata
re-checks in the background.

## Verified current state

- On 0.1.30 with a TMDB key already configured, every pre-0.1.30
  cached metadata entry is skipped. `resolveOutcome` filters the disk
  entry by `tmdbFirst` before any freshness check, and a legacy entry
  (no `tmdbFirst` field on disk) decodes to `false` while the current
  order requires `true`. The memory cache has the same order gate, and
  the process starts with empty memory on upgrade. Every hydrated row
  therefore needs a foreground network lookup before the image cache
  is even consulted.
- A posterless new write can overwrite the legacy entry at the same
  query key: the disk-write guard allows the write whenever the stale
  entry had a poster, regardless of whether the new result has one.
  The old poster URL is then gone for good.
- A TMDB primary match with no usable IMDb id skips Cinemeta entirely,
  so the row is blank when TMDB has no usable poster, even when
  Cinemeta had artwork. A non-null TMDB poster URL that fails to
  download also suppresses the alternate artwork, because the backfill
  only supplies a Cinemeta poster when the TMDB URL is null.
- Rows already holding posterless metadata are skipped by hydration
  enqueue, and an explicit refresh does not requeue them. A lookup
  that ends inside the TMDB cooldown leaves the row blank until
  another eligible lookup happens.
- The displayed poster is tied to the current metadata result, so a
  posterless revalidation result clears the poster that was showing.

## Proposed change

- Serve a valid cached poster from a legacy (order-mismatched) disk
  entry immediately when no compatible poster-bearing result is
  available, keeping its original order and provenance, and
  revalidate under the current order in the background. A failed
  revalidation, or one that returns no artwork, must not take the
  known poster away.
- Never overwrite a disk entry that holds a poster URL with a
  posterless result for the same query.
- When TMDB resolves the title but supplies no usable poster, try
  Cinemeta for artwork: by-ID lookup when a verified IMDb id is
  available, otherwise a strict title lookup without merging provider
  identities. Keep a known artwork URL when the new image fails to
  download, and make an explicit refresh retry posterless rows and
  rows affected by an expired cooldown.
- Keep the last successfully decoded poster on screen across metadata
  revalidation and order changes. Replace it only when a new image
  decodes successfully.

## Out of scope

- No change to the lookup order rules themselves: TMDB-first with a
  key configured, Cinemeta-first without one, zero TMDB requests
  without a key. No change to the strict title/year match gate or to
  the manual candidate picker.
- No change to poster sizes, retry timing, or prefetch behaviour
  beyond what the fallback and retry work above requires.
- No new network calls on the upgrade path: the legacy poster is
  served from disk, and revalidation stays in the background.
