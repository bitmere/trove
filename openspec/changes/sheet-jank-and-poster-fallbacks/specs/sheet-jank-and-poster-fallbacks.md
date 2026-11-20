# Spec: Sheet jank + poster fallbacks

## Requirement: Details sheet does not bounce

The download details sheet must open and stay at a stable position
while the library hydrates in the background.

- Scenario: user opens the details sheet for any download while
  hydration is still running — the sheet does not translate up/down
  repeatedly; it settles at one position.
- Scenario: user opens the sheet for an already-cached download
  after hydration ends — the sheet is stable.
- Scenario: the sheet's content (title, facts, files) is unchanged
  while open — no content change is caused by the fix, and the list
  remains scrollable with safe-drawing padding preserved (no clipping
  under the status/nav bar).
- Scenario: the dead `animateContentSize` import is removed.

## Requirement: Poster-only TMDB fallback

When Cinemeta returns metadata without a poster, TMDB is consulted
for the poster only.

- Scenario: Cinemeta hit with `posterUrl == null` and a valid TMDB
  key — TMDB is called by IMDb id via `/3/find` when available,
  else by title search; the returned poster is stored and shown, and
  all Cinemeta text fields (title, synopsis, year, cast, rating)
  are kept unchanged.
- Scenario: Cinemeta hit with a poster — no TMDB call is made.
- Scenario: no TMDB key configured — behaviour is unchanged (no
  poster, no crash, no error surfaced).

## Requirement: Posterless entries are not frozen

A disk-cached entry without a poster is treated as poster-incomplete.

- Scenario: a cached entry has `posterUrl == null` — the next
  resolution re-runs the poster-only TMDB lookup (or Cinemeta
  re-fetch) instead of returning the entry for 180 days.
- Scenario: a cached entry has a poster — it is returned from disk
  with no extra network call.

## Requirement: Title parsing and query variants

Release names with edition/parenthetical/dub tags resolve to the
correct title.

- Scenario: `Avatar.The.Way.of.Water.Extended.2022…` parses to
  `Avatar: The Way of Water`, 2022 (no `Extended` in the title).
- Scenario: `Blade Runner 2049` and `1917` still parse with their
  year in the title intact.
- Scenario: query variants run in order and stop at the first hit:
  (a) cleaned title + year; (b) cleaned title without year (accept
  only a single candidate); (c) edition-stripped title + year;
  (d) leading `The` dropped / `The X` ↔ `X, The`; (e) raw
  release-name title as last resort. Each variant keeps the strict
  gate: more than one exact-year candidate is still a miss.

## Requirement: TMDB result matching

TMDB matching uses `original_title` and popularity as a tie-break,
never popularity alone.

- Scenario: TMDB results include a title whose `original_title`
  matches the query (e.g. "Harry Potter and the Philosopher's
  Stone" for a "Sorcerer's Stone" query) — it is accepted.
- Scenario: several results share the exact year — the one with an
  exact normalised `title` or `original_title` match wins; failing
  that, the highest `popularity` among exact-year matches wins.
- Scenario: results differ in title and none matches — no auto-pick,
  even for the most popular result.
- Scenario: TMDB `vote_average` is never stored as `imdbRating`.

## Requirement: Poster retry and refresh

The user can retry a missing poster and force a fresh lookup.

- Scenario: user taps a row's poster placeholder — that URL's 60 s
  miss entry is cleared and `loadPosterWithRetry` re-runs.
- Scenario: user triggers an explicit refresh — the 1 h in-memory
  miss is bypassed for that refresh only; background page loads do
  not bypass it.

## Requirement: Smaller row posters on slow links

Row posters from TMDB use `w342` instead of `w500`.

- Scenario: a TMDB poster URL for a 48x72 dp row requests the
  `w342` size.

## Requirement: Hydration reaches visible rows first

Hydration prioritises visible rows and does not serialise all
metadata traffic behind one lock held across network I/O.

- Scenario: opening the library hydrates visible rows before
  below-the-fold rows.
- Scenario: a refresh, sort change, or tab switch still abandons
  the in-flight hydration pass (`refreshVersion`).
- Scenario: no functionality is lost and no extra unbounded
  parallelism is introduced.
