# Tasks

- [x] Fix `TorBoxProvider.hosterDomains()` to parse the `domains` JsonArray per item (lowercase each domain, strip a `www.` prefix), keep the JsonPrimitive branch for robustness, and filter to items with `status == true`. Keep the 1h cache. Items without a `status` field are treated as active.
- [x] Fix `TorBoxProviderTest` to fake `/webdl/hosters` with the real shape (objects with `name` + `domains` arrays + `status`): assert the parsed set equals the domains (not the names), assert `rapidgator.net` / `rg.to` / `1fichier.com` links classify as `HosterLink` and an unknown host as `UnsupportedHoster`; add a regression test with a realistic Rapidgator/1Fichier payload, and cover `supportsUrl` with the same data. Cover the `status == false` exclusion and the retained JsonPrimitive branch.
- [x] Audit (report only, no fix in this task) — see parent report: no same-class bug found in any other provider. the other providers' `hosterDomains()` implementations (AllDebrid, RealDebrid, DebridLink, Premiumize, Offcloud) against their live endpoint shapes and report whether the same class of parsing mistake exists there.
- [x] Review source diff and XML/resource references without compiling.
- [x] Coordinator: ran focused tests (TorBoxProviderTest 22, ShareClassifierTest 13), full app/core JVM suites (405 tests, 0 failures), lint once (BUILD SUCCESSFUL, 0 errors, 29 warnings, none new).
- [ ] Phone: verify a real Rapidgator/1Fichier link shared to Trove enables Add, and an unknown host still warns as unsupported.
