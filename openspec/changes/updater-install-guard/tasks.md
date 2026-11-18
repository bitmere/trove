# Tasks

- [x] Fix `PendingUpdateSession.isOwnedPath()` to canonicalize both sides: require the candidate's canonical parent to equal the canonical updates directory, keep the `.apk` extension check, and require `file.canonicalFile == File(canonicalParent, file.name)` so a leaf symlink pointing outside the directory is still rejected. Keep the single shared predicate for install, save/load and cleanup so they agree.
- [x] Extract the install-time re-verification into a pure, JVM-testable guard (`verifyInstallGuard`) that checks ownership, then size, then SHA-256 in order, skipping later file reads when an earlier sub-check fails.
- [x] Add a typed `InstallDiagnostics` record (per-sub-check pass/fail/not-checked, expected vs observed bytes and SHA-256, whether deletion succeeded) carried on `InstallOutcome.Error` and surfaced through the existing `UpdateUiState.Error.detail` path; add a distinct `OWNERSHIP` issue and string for ownership failure and keep the existing hash/size string for genuine hash/size failures.
- [x] Add `PendingUpdateSessionTest` coverage: save/load/matches round trip through a symlinked files ancestor (absolute path differs from canonical path), and rejection of an `.apk` leaf symlink pointing outside the updates directory.
- [x] Add `InstallGuardTest` coverage: install guard passes through a symlinked updates directory; ownership failure reports size/hash as not checked with no observed values; size failure and hash failure each report the failing sub-check with expected vs observed values.
- [x] Add `UpdateViewModelTest` coverage: an install outcome carrying ownership diagnostics surfaces the distinct issue and the diagnostic detail with no install retry offered.
- [x] Review source diff and XML/resource references without compiling.
- [x] Coordinator: ran focused tests (InstallGuardTest 4, PendingUpdateSessionTest 5, UpdateViewModelTest 10), full app/core JVM suites (403 tests, 0 failures), lint once (BUILD SUCCESSFUL, 0 errors, 29 warnings, none new).
- [ ] Phone: verify an in-app update installs end to end, and that a forced failure shows the failing sub-check with copyable diagnostics.
