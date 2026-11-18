# Updater install-guard fix

The in-app updater fails every update at install time. The install guard in
`AndroidUpdateBackend.install()` rejects the downloaded APK with
`UpdateIssue.VERIFICATION` before ever reading its size or hash, because
`PendingUpdateSession.isOwnedPath()` compares a raw absolute path with a
canonical path — and on Android `filesDir` lives under `/data/user/0`, a
symlink to `/data/data`, so the two never match. The download itself passes
its own size and SHA-256 checks; only the install-time re-verification is
broken. This same bug caused the 0.1.15 to 0.1.16 failure previously blamed
on the phone's network path (AdGuard/VPN ruled out by test: AdGuard off,
same failure).

The error is also misleading in two ways: it blames hash/size when the
ownership check is what failed, and it claims the download was deleted when
the orphaned APK is actually left in `filesDir/updates` (the old guard
returned false, so the cleanup `if (pending.isOwnedPath(...)) file.delete()`
never ran). No diagnostics are shown, because the install path never
attaches any.

Fix the ownership predicate by canonicalizing both sides, and make install
failures carry a typed, copyable diagnostic that names the sub-check that
actually failed. No dependency, version, signing, workflow, manifest URL or
release-process changes. Source-only work: the coordinator runs builds,
focused tests, the full JVM suite and lint after diff review. Copying
diagnostics and signed installation remain device checks.
