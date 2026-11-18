# Tasks

- [x] Read required Android development, testing and design skills and existing updater seams.
- [x] Add fake-connection tests for distinct verification issues, safe diagnostics, bounded retries, range success/fallback, resumed hash mismatch, headers and cancellation.
- [x] Add empty, truncated and digest-mismatch issues and separate resource strings; preserve install VERIFICATION behavior.
- [x] Carry one typed, bounded diagnostics record into Error.detail and make the existing detail selectable.
- [x] Add two cancellable retries with backoff; close active connections on cancellation and preserve failure cleanup.
- [x] Validate Range/Content-Range against held bytes and original size; rehash prefix and verify the published digest.
- [x] Forward allowlisted no-cache headers on manifest and APK requests; keep caches disabled.
- [x] Add ViewModel coverage for new issues, safe details and blocked installation.
- [x] Review source diff and XML/resource references without compiling.
- [ ] Coordinator: run ApkDownloadTest, UpdateCheckTest, UpdateViewModelTest and UpdatePolicyTest after reviewing the diff.
- [ ] Coordinator: run full app/core JVM suites, then lint. No builds or tests executed by this implementation task.
- [ ] Phone: verify affected carrier/network path, validated resume, cancellation during a blocked socket read, copying the final diagnostics, and signed installation with install-time verification.
