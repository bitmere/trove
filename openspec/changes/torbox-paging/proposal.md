# TorBox paging: remove the silent 1000-item cap

## What the user asked for

Every app open loads the whole list — why is it stuck at 1000, is there
no pagination, and would infinite scroll with few records at a time be
better? Also: how does TorBox's own web page do it, and which TorBox
APIs are actually available?

## Verified current state (0.1.23, from code + live API, 2026-11-19)

- `TorBoxProvider.listKind` (`core/.../TorBoxProvider.kt:363-381`) sends
  exactly one GET per kind with `limit=1000&files=true` and **never sends
  `offset`** — no loop, no second call, no "load more".
  `listDownloadsInternal` (`:154-168`) fires that once each for torrents /
  web / usenet, merges, and sorts. The UI renders whatever that single
  call returned.
- The cap is per kind (1000 torrents + 1000 web + 1000 usenet), and a user
  with >1000 items in one kind **silently loses everything past the first
  1000** — no search hit, no count, no notice. Which 1000 survive is
  decided by the server's undocumented list order.
- Live TorBox OpenAPI (`https://api.torbox.app/openapi.json`, fetched
  2026-11-19) vs the local spec: **zero path drift (90/90 paths)**.
  `offset` (default 0) and `limit` (default 1000) exist on all three
  `/mylist` routes; there is no server-side search/filter/sort and no
  total-count field. The `active` param is declared but undocumented
  everywhere else (not in the SDK docs or generated client) — its
  filtering behavior is **unverified: do not use it until tested with a
  live key**.
- TorBox's own web UI does **not** paginate the visible list: per the v4.8
  changelog it load-alls and sorts/filters/fuzzy-searches client-side.
  Paging exists at the API layer — v4.8 added `offset`/`limit` to all
  `/mylist` routes precisely "so users with over 1000 downloads can get
  all of their downloads". TorBox's own media-center app loops
  `offset = 0, limit = 1000, offset += limit` until a short page.
  Trove should copy that shape: page at the API layer, keep client-side
  search/sort.
- Real-Debrid and Debrid-Link already page inside Trove
  (`RealDebridProvider.kt:198-211`, `DebridLinkProvider.kt:80-101`) —
  the in-repo precedent is "loop until a short page", not the Paging
  library. No `androidx.paging` dependency exists anywhere in the repo.

## Decisions for review

1. **Step 1 first: page at the API layer, no UI change.** `listKind`
   loops `offset += limit` until a short page, deduping by `(kind, id)`,
   same cache mode for all pages of one load. This alone removes the
   data loss and keeps the whole existing UI contract (full-list search /
   sort / counts). Ship this before any viewport work.
2. **Hand-rolled offset paging, not Paging 3.** Zero new dependencies,
   keeps `core` Android-free, fits the small-footprint rule. Paging 3
   would not fix offset instability anyway (no cursor exists; mitigate
   with back-to-back page fetches + dedupe).
3. **Step 2 (viewport appends) only after measuring Step 1** on a large
   library. It changes the data contract, so the search/sort/count
   decisions below must be made first: when the user types a query,
   finish the background sync for that tab first so search keeps today's
   semantics (never silently present partial search results as complete);
   tab counts become "loaded so far" unless a full sync finishes.
4. **Scroll-state rules** (from the 2026-11-19 Codex UI research §1):
   per-tab list states keyed by (account, tab, criteria); provider
   switch resets all three tabs to top; sort/query Reset sends the active
   tab to top; process-death restoration only once the anchor row is in
   the loaded set, else start at top. Never persist credentials,
   playback links, or row payloads in saved state.
5. **Page size:** `limit=100` for Step 2 appends (matches the
   Real-Debrid precedent), `limit=1000` for Step 1 full syncs (fewer
   calls against the 5/sec bucket). Never `limit > 1000` without a
   live-key test (no documented maximum).
6. **Bugs fixed in the same area:** the `checkingResumeData` state never
   matches (lowercased to `"checkingresumedata"` but compared against
   `"checking_resume_data"` — dead branch, shows UNKNOWN instead of
   DOWNLOADING); readiness gates on `download_finished` /
   `download_present` / `cached` booleans instead of
   `download_state == "completed"` (the SDK says explicitly "Do not use
   this for download completion status"); per-page usenet errors surfaced
   with retry instead of silently swallowed.
7. **Do not adopt:** Paging 3, `createstream` (plan-restricted,
   deliberately unused), queued endpoints (deprecated in TorBox v6.2,
   HTTP 410, despite still appearing in the spec), RSS.

## Out of scope

- `files=false` list pages + single-item fetch on row open (separate
  decision — it changes the Play path; measure first).
- Dashboard-style "Default" sort (active first, cached/inactive last) —
  optional 8th sort option once the readiness fields are read.
