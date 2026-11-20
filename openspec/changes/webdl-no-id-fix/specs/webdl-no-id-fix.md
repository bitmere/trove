# Web download add without an id

## Sync create endpoints

- The webdl add posts to `POST /webdl/createwebdownload` with the
  same single form field `link`. It no longer calls
  `/webdl/asynccreatewebdownload`.
- The usenet add posts to `POST /usenet/createusenetdownload` with
  the same multipart file upload. It no longer calls
  `/usenet/asynccreateusenetdownload`.
- The id reads are unchanged: `webdownload_id ?: queued_id` for
  webdl, `usenetdownload_id ?: queued_id` for usenet, each accepting
  a JSON number or a numeric string (`optLong`).
- The webdl app-side hourly budget guard (`HourlyBudget(25)`,
  `QuotaExceeded("TorBox allows 25 web adds per hour")`) still fires
  before the API is called.
- The torrent branches are unchanged. The stale comment on the
  magnet branch ("async returns data=null") must not describe the
  webdl/usenet branches after this change.

## Mylist recovery on a missing id

- When a sync create returns success but no usable id, the provider
  looks the created item up in mylist instead of throwing
  immediately.
- For a web link, the lookup key is the MD5 hex (lowercase) of the
  exact link string as sent, compared case-insensitively against
  each mylist row's `hash`. The lookup uses
  `GET /webdl/mylist` with `bypass_cache=true` and pages through
  results until the row is found or a page comes back short.
- For usenet, the lookup key is the `hash` field of the create
  response's `data` object when present, compared
  case-insensitively against each mylist row's `hash` via
  `GET /usenet/mylist` with `bypass_cache=true`, paging the same
  way. When the create response carries no `hash`, there is nothing
  to match on and the provider throws (see below) without a mylist
  lookup.
- A matched row is returned as the added download (resolved through
  the existing row mapping, same as `resolveCreated` produces for a
  visible row). A recovery that finds no matching row throws.
- The create call is never retried on the missing-id path: a retry
  risks a genuine duplicate add.

## Surfacing TorBox's detail on the no-id path

- When the no-id path throws, its `detail` is TorBox's redacted
  `detail` from the create response envelope when one is present,
  and the existing message ("TorBox returned no web download id" /
  "TorBox returned no Usenet id") only when no usable `detail`
  exists.
- The redaction is the existing `safeDetail` rule: the `detail`
  never reaches UI or logs when, after URL-decoding, it contains
  the API key, `requestdl`, or `http`; in that case the generic
  fallback sentence is used instead.
- A `success:false` refusal (for example `DUPLICATE_ITEM`) still
  throws from `parseEnvelope` with the provider code and detail
  preserved, before any of this path runs.

## Redacted diagnostic logging

- When a create returns no id, one diagnostic line is emitted
  recording: the endpoint, the HTTP status (2xx — only a 2xx
  response reaches this path, because `parseEnvelope` throws on
  every other status), the envelope `success` value, the redacted
  `detail`, and which id fields were present
  in `data` (field names only, never values).
- The line never contains the link, the API key, or the full
  response body. The `detail` in the line is the same redacted
  value surfaced to the user.
- `core` stays Android-free: the provider takes a minimal
  injectable diagnostic sink (a `(String) -> Unit`, no-op by
  default) instead of calling any platform logger. The app wires
  the sink to platform logging at its `TorBoxProvider`
  construction sites, so the line reaches the device log in
  production.
- The sink is invoked only on the no-id path, not on successful
  creates with an id.

## 422 detail-as-array in safeDetail

- When the envelope's `detail` is a JSON array (the 422
  `HTTPValidationError` shape), `safeDetail` renders the first
  element's `msg` with its `loc` (loc segments joined with `.`),
  for example `body.link: <msg>`.
- The rendered string goes through the same redaction (key /
  `requestdl` / `http` filter) and the same 300-character cap as
  the string form.
- When the array is empty, or its first element has no usable
  `msg`, the generic fallback sentence is used, as before.
- This changes failure-path surfacing only; the success-path
  envelope handling is unchanged.

## Tests

- Webdl add: a numeric `webdownload_id`, a string
  `webdownload_id` (`"42"`), and a `queued_id` each resolve the
  created row; the create posts to the sync endpoint with the
  `link` form field.
- Webdl add with `data: null` and with `data: {}` recovers the row
  from mylist by link-hash match and performs exactly one create
  call; with no matching row it throws the no-id error.
- Webdl add with hash-only `data` (for example
  `{"hash": "<md5>"}`) recovers from mylist the same way.
- Usenet add: a numeric `usenetdownload_id`, a string id, and a
  `queued_id` each resolve; hash-only `data` recovers from the
  usenet mylist by hash; hash-less no-id data throws.
- A `success:false` `DUPLICATE_ITEM` create response surfaces the
  provider code and the real `detail`, with no mylist lookup.
- A 422 response whose `detail` is an array surfaces the first
  `ValidationError.msg` with its `loc`.
- The diagnostic sink receives exactly one redacted line on a
  no-id create, containing the endpoint and the id-field presence
  and containing neither the link nor the API key; a successful
  create with an id emits nothing.
