# Manual in-app updater

> Historical change. [bitmere-releases](../bitmere-releases/proposal.md) defines the current GitHub Releases distribution, update channels, and signing contract.

Add an account-independent, user-initiated updater in phone Settings. Fetch the public Trove mirror, validate metadata, stream and verify the APK, and request Android installation explicitly. Preserve account, playback, share and navigation behavior. No startup polling, notifications, credentials, distribution workflow changes, dependency additions or version bump.

Use Android-free core policy and Gson parsing, narrow fakeable adapters, an activity-owned ViewModel, and durable correlated PackageInstaller callbacks. A signer mismatch blocks upgrading and links to clean-install instructions without promising settings survival.

New permission (flagged for review): this change adds the android.permission.REQUEST_INSTALL_PACKAGES declaration, required for PackageInstaller self-updates. It is the only new permission; the app still requests only INTERNET, ACCESS_NETWORK_STATE and REQUEST_INSTALL_PACKAGES.

Validation is source-only in this checkout: the coordinator runs Gradle; phone installation and Compose tests remain unrun.
