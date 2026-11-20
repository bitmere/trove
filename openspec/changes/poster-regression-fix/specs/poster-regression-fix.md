# Poster regression fix

## Legacy poster serving

- When the order-gated disk lookup misses, the resolver falls back
  to the disk entry for the same query even when its recorded order
  differs, provided the entry is fresh and carries a poster URL.
- The fallback result is served immediately with the entry's original
  provenance and order. It is treated as a legacy result: it does not
  satisfy the current order's freshness requirement and it schedules
  a background revalidation under the current order, using the same
  stale-entry revalidation path as a stale current-order entry.
- A background revalidation that fails, or that returns a result
  without artwork, leaves the served legacy poster in place. It must
  not clear the row's poster and must not overwrite the legacy disk
  entry with a posterless result.
- The memory cache keeps its order gate for current-order entries; a
  served legacy result is not stored as a current-order memory entry.

## Poster-preserving writes

- A disk write for a query never replaces an existing entry that
  holds a poster URL with a result that has no poster URL. The
  existing entry stays, with its poster, order, and provenance.
- A poster-bearing result replaces a posterless entry for the same
  query. A posterless result may be written only when no entry
  exists for the query, or the existing entry is also posterless.

## Artwork fallback when TMDB has no poster

- When TMDB is tried first and resolves the title but supplies no
  usable poster URL (null poster, or image configuration
  unavailable), the resolver tries Cinemeta for artwork before
  returning the posterless result.
- With a verified IMDb id, the artwork lookup is by id. Without a
  shared id, it is a strict title lookup (normalized title, exact
  year when supplied) and supplies artwork only — no Cinemeta text,
  rating, or episodes are merged into the TMDB result, and the
  provenance records that the artwork came from Cinemeta.
- When the TMDB poster URL is present but the image download fails,
  the known Cinemeta artwork URL is retained as the result's poster
  instead of the failed URL, so the row can still show art.

## Refresh retry for posterless rows

- An explicit user refresh requeues rows whose held metadata has no
  poster URL, even when those rows already hold metadata. The
  hydration enqueue skip for rows already holding metadata applies
  only to rows that already have a poster.
- Rows whose last lookup ended inside the TMDB cooldown are
  requeued by an explicit refresh once the cooldown has expired.
  A lookup that ends inside the cooldown keeps its short memory
  lifetime and does not write a posterless result over a
  poster-bearing disk entry.

## Displayed poster separated from revalidation

- The row and details-sheet poster slots keep the last successfully
  decoded poster bitmap across metadata revalidation and lookup-order
  changes. A new metadata result without a poster URL does not clear
  the displayed bitmap.
- The displayed poster is replaced only when a new image decodes
  successfully for the current poster URL. A failed load for a new
  URL leaves the previous bitmap in place and keeps the retry
  affordance for the new URL.
- The poster slot keeps its fixed size and placeholder behaviour
  from the previous change; this change adds retention only, with no
  layout or token changes.

## Verification

- A literal pre-0.1.30 disk entry (no `tmdbFirst` field, Cinemeta
  source, poster URL, fresh `fetchedAt`) with a preconfigured TMDB
  key and a blocked network resolves to the legacy poster
  immediately, with no network call.
- In-place upgrade with a warm cache: first-viewport rows show
  their cached posters at launch, and the count after hydration
  settles is at least the launch count — revalidation never reduces
  it.
- Key transitions (key added, key cleared, key swapped) with warm
  caches preserve displayed posters; a revalidation that returns
  no artwork leaves the previous poster in place.
- Cancellation mid-revalidation and a 429 during revalidation leave
  the displayed poster and the disk entry unchanged.
- Full JVM suite, lint, and the Impeccable detector on changed UI
  files all pass with no new findings.
