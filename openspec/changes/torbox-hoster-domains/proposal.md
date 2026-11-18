# TorBox hoster-domain parsing fix

TorBox's public `GET /v1/api/webdl/hosters` returns one object per hoster
with a `domains` array (e.g. Rapidgator: `["rapidgator.net", "rg.to"]`), a
display `name`, and a boolean `status`. Trove parsed each item as
`optString("domain") ?: optString("host") ?: optString("name")` — the real
API has no `domain` or `host` field — so it fell through to the display
name and returned ~170 names ("Rapidgator", "1Fichier", ...) instead of the
388 real domains. The share classifier then compared a link's host (e.g.
`rapidgator.net`) against those names, never matched, and classified every
real hoster link as `UnsupportedHoster`, leaving Add disabled. The feature
only worked when the hoster-list fetch itself failed, because an empty set
means accept-all. The same broken set fed `supportsUrl()`, so multi-service
Add checks were affected too. The `status` field was ignored.

Parse the `domains` array per item, lowercase each domain, strip a `www.`
prefix, keep only items with `status == true`, and keep the 1h cache.
Exact-host or subdomain-suffix matching is sufficient — the per-hoster
`regex` field is not needed. No dependency, version, signing, workflow or
release-process changes. Source-only work: the coordinator runs builds,
focused tests, the full JVM suite and lint after diff review. Live hoster
recognition on a phone remains a device check.
