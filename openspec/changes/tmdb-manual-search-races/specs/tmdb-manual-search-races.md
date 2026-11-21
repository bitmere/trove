# Manual TMDB search races

## Manual search ownership

- Submitting a manual TMDB search takes ownership of the open
  picker's candidate state for that download: the in-flight
  automatic candidate load (`metadataCandidateJob`) is cancelled
  at submit time, after the open-picker guard passes. A late
  automatic response MUST NOT replace manual candidates, erase
  manual feedback, or clear `SEARCHING` while a manual request
  is in flight.
- A stored-override resolution cancelled this way is not an
  error: it re-runs the next time the download is opened.

## Dismissed search leaves no stale state

- Dismissing the picker cancels any in-flight manual search job
  and removes that download's `manualTmdbSearch` entry, so
  reopening the same download starts with an enabled field and
  Search button.
- If a manual search job completes after its picker is gone
  (dismissed, or the download deleted), its completion guard
  clears the entry for that download instead of returning with
  `SEARCHING` still set.

## Found feedback

- A manual search that finds candidates sets
  `ManualTmdbSearchStatus.FOUND` (it previously removed the
  entry, leaving no visible change near the Search button).
- The sheet renders a "found — pick a match from the list above"
  line under the Search button while the status is `FOUND`.
- `FOUND` is replaced by `SEARCHING` on the next submit, removed
  when the picker is dismissed, and removed when the picker
  reloads its candidates.

## No-match guidance

- The no-match message tells the user to try the full title or
  paste a themoviedb.org link, because the manual title search
  uses the picker's exact normalized-title gate: a partial title
  yields no match by design, and a bare numeric id is invalid
  when the download type cannot be parsed.
