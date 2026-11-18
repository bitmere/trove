## MODIFIED Requirements

### Requirement: TorBox hoster domains come from the `domains` array
`TorBoxProvider.hosterDomains()` SHALL parse the live
`GET /v1/api/webdl/hosters` shape: one object per hoster with a `domains`
array, a display `name`, and a boolean `status`. It SHALL return the
domains — lowercased, with a `www.` prefix stripped — and SHALL NOT return
display names. Only items with `status == true` SHALL contribute domains;
items without a `status` field are treated as active. The JsonPrimitive
branch SHALL be kept for robustness against a plain-string list. The 1h
cache SHALL be kept, and `supportsUrl()` SHALL keep exact-host or
subdomain-suffix matching against the returned set.

#### Scenario: Real hoster payload
- **Given** `/webdl/hosters` returns objects such as Rapidgator with domains `["rapidgator.net", "rg.to"]` and status true, and 1Fichier with domains including `1fichier.com` and status true
- **When** the hoster set is read
- **Then** the set contains `rapidgator.net`, `rg.to` and `1fichier.com`, and does not contain `Rapidgator` or `1Fichier`.

#### Scenario: Disabled hoster excluded
- **Given** a hoster object with `status` false
- **When** the hoster set is read
- **Then** none of its domains are in the set, and a link to one of them does not match `supportsUrl()`.

## ADDED Requirements

### Requirement: Real hoster links classify as supported
A link whose host is in the parsed TorBox hoster set SHALL classify as
`HosterLink` (Add enabled); a link to an unknown host SHALL classify as
`UnsupportedHoster`. `supportsUrl()` SHALL agree with the classifier on
the same data.

#### Scenario: Rapidgator, 1Fichier and unknown hosts
- **Given** a parsed set containing `rapidgator.net`, `rg.to` and `1fichier.com`
- **When** links to `https://rapidgator.net/file/abc`, `https://rg.to/file/abc` and `https://1fichier.com/abc` are classified
- **Then** each classifies as `HosterLink`, while `https://unknown.example/abc` classifies as `UnsupportedHoster`.

### Requirement: No behaviour change outside hoster parsing
The hoster-list fetch, its failure fallback (empty set means accept-all),
the 1h cache, download creation, and the other providers' hoster parsing
SHALL be unchanged. No dependency, version, signing, workflow or
release-process changes.
