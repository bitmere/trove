# Poster speed and enrichment provenance

## What the user asked for

Make posters responsive on slow connections, show their enrichment source in
download details, add a leading poster to the sheet, and prefer TMDB for
automatic enrichment when a key is configured.

## Verified current state

- The poster loader has a 4 MB decoded cache, a 32 MB disk cache and one
  in-flight request per URL. The 4 MB response limit and sampled decoding
  limit memory. The network still transfers the whole bounded response.
- Three attempts shared a 60-second miss window and library refresh
  suppressed the image effect. Hydration published at batch boundaries.
- Disk records used two source strings. The older `tmdb` value could
  represent a full lookup or poster-only enrichment.

## Proposed change

- Prefer advertised TMDB `w185` for the shared row and sheet poster,
  followed by `w342` and `w500`. The shared thumbnail can look softer in
  the sheet at high density, but it avoids a second download and decode.
  Reuse existing bytes for those sizes on
  TMDB's documented CDN with a shared cache key. Keep Cinemeta's supplied
  URLs because its responses can include different hosts and a general
  resize contract was not verified.
- Retry transient image failures at most twice with 1- and 2-second
  backoff and a shared 1-second miss window. Persistent failures use
  a 60-second miss window and expose an immediate, labelled retry tap.
- Key the image effect on its validated URL and manual retry action. Keep
  its reserved slot during refresh, loading, and failure. Start at most one
  speculative request at a time after the viewport settles and its image
  requests finish. Consider only the next three rows with resolved URLs.
- Publish viewport metadata independently within the four-lookup cap and
  batch offscreen results. Keep provider, row name, request, and override
  guards.
- Keep the existing Material 3 palette and type hierarchy. Use a shared
  8 dp poster shape and a 16 dp gap in the sheet header. The 80 × 120 dp
  slot becomes 64 × 96 dp below 300 dp of available header width or at
  font scale 1.3 and up. The row slot remains 48 × 72 dp.
- Carry typed provenance with matched metadata. Hold unsuccessful outcomes
  in the session and show the localized source line only in details.

## Conditional TMDB-first decisions

- **Identity and coverage.** Require a unique normalized title match and
  exact year when supplied. Request IMDb external IDs as part of the same
  TMDB detail response. Backfill by verified IMDb ID and type with the
  IMDb rating, episode list, and any missing text or art. Keep TMDB text
  and poster when present. A strong TMDB match without a shared ID is
  shown immediately and may omit the IMDb ID, IMDb rating, and episode
  list. No title-only merge is attempted. If a known IMDb ID cannot be
  backfilled, keep the ID and omit the unavailable rating and episode list.
  Manual picks remain tied to their chosen Cinemeta candidate, with optional TMDB
  poster-only lookup.
- **Lookup order and cache.** A configured key enables TMDB-first lookup.
  Without a key the resolver consults Cinemeta and sends zero TMDB
  API requests. Memory and disk entries record the configured order. A key
  enable or clear rehydrates loaded rows and skips entries resolved under
  the opposite order. Legacy records use the old Cinemeta-first policy.
  A compatible legacy `tmdb` stays ambiguous until ordinary revalidation
  establishes the source. The label itself triggers no lookup or
  artwork download.
- **Mixed provenance.** A confirmed backfill uses TMDB with Cinemeta
  backfill. A Cinemeta match with only a TMDB image uses Cinemeta with
  TMDB poster only. The source line is absent during pending lookups.
- **Rate limits.** A shared TMDB cooldown honors a valid Retry-After delta
  or HTTP date, with a one-second floor and a 60-second default. The
  current automatic lookup falls back to Cinemeta. The resolver sends no
  new TMDB calls during the cooldown and does not persist the temporary
  fallback as a long-lived TMDB-first result.
- **Poster timing.** External IDs ride in the existing TMDB detail
  response. For an IMDb-confirmed primary result, publish the poster to
  the viewport or open sheet before starting Cinemeta backfill. A strong
  TMDB-only result publishes in one step.

## Verification

Distinct posters already use independent IO requests. The cache and
in-flight map are synchronized, but the loader releases the mutex before
download and decode. No measurements establish image contention, so this
change adds no foreground concurrency. Network timeouts are outside scope.
