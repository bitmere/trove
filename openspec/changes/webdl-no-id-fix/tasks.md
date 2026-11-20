# Tasks

- [x] Switch the webdl add to the sync endpoint
  `POST /webdl/createwebdownload` (same form field `link`) and the
  usenet add to `POST /usenet/createusenetdownload` (same multipart
  file upload). Keep the same id reads and the webdl hourly budget
  guard. Update the stale async comment on the magnet branch so it
  no longer describes the webdl/usenet branches.
- [x] Recover from mylist when a create returns no id: for webdl,
  compute the MD5 of the link and look the row up by `hash` via
  `GET /webdl/mylist` with `bypass_cache=true`; for usenet, match
  the `hash` the create response carried via `GET /usenet/mylist`
  with `bypass_cache=true`. Return the matched row; only throw if
  it is absent. Never retry the create call.
- [x] Surface TorBox's `detail` on the success-without-id path:
  when the no-id throw fires, use the redacted `detail` when one is
  present, falling back to the existing "TorBox returned no … id"
  message when it is not. Keep the existing `safeDetail` redaction.
- [x] Add redacted diagnostic logging of the create response on the
  no-id path: endpoint, HTTP status, `success`, the redacted
  `detail`, and which id fields were present. Never log the link,
  the key, or the full body. Keep `core` Android-free with a minimal
  injectable diagnostic sink (no-op by default), wired to platform
  logging at the app's `TorBoxProvider` construction sites.
- [x] Handle 422 `detail`-as-array in `safeDetail`: render the
  first `ValidationError.msg` with its `loc`, under the same
  redaction and length cap as the string form.
- [x] Add tests for the webdl + usenet add paths: numeric and
  string ids, `queued_id`, `data` null, `data` empty, hash-only data
  recovered from mylist, and a `success:false` `DUPLICATE_ITEM`
  case asserting the real detail surfaces; plus a 422
  detail-as-array case and a diagnostic-sink capture case.
- [x] Coordinator runs the focused tests, the full suite, lint, and
  the Impeccable detector on changed UI files; reports the test
  count and baseline delta. Gates 2026-11-21: 650 tests, 0 failures
  (baseline 636, +14 net); lint 0 errors (24 warnings, 1 hint);
  detector 0 findings on the 4 changed .kt files (no UI files
  changed).
