# Source review and coordinator handoff

> Historical change. [bitmere-releases](../bitmere-releases/proposal.md) defines the current GitHub Releases distribution, update channels, and signing contract.

Status: implementation and test source only. No Gradle, compiler, test, lint, emulator or phone commands were run, as requested. There is no passing-test claim. No device was used.

Static checks: strings.xml parses, resource names are unique, new message resources resolve, all UpdateStream call sites use the request seam, all new issues reach Settings, and download exceptions expose only typed diagnostics. Reviewed the source diff against snapshots made before editing. No versions, signing, workflows, tags or release files were edited; no git repository or branch was created.

The static JVM inventory changed from 159 to 175 @Test annotations across the same 30 test files (app: 103 to 119; core: 56 unchanged). These are source counts, not executed test results.

## Files changed

- `app/src/main/java/app/trove/debrid/ui/settings/UpdateSection.kt`
- `app/src/main/java/app/trove/debrid/ui/settings/UpdateViewModel.kt`
- `app/src/main/java/app/trove/debrid/update/ApkDownload.kt`
- `app/src/main/java/app/trove/debrid/update/UpdateBackend.kt`
- `app/src/main/java/app/trove/debrid/update/UpdateCheck.kt`
- `app/src/main/res/values/strings.xml`
- `app/src/test/java/app/trove/debrid/ApkDownloadTest.kt`
- `app/src/test/java/app/trove/debrid/UpdateCheckTest.kt`
- `app/src/test/java/app/trove/debrid/UpdateViewModelTest.kt`
- `openspec/changes/updater-hardening/proposal.md`
- `openspec/changes/updater-hardening/specs/download-verify/spec.md`
- `openspec/changes/updater-hardening/tasks.md`
- `openspec/changes/updater-hardening/validation.md`

## Test names added

- `ApkDownloadTest.emptyTruncatedAndDigestMismatchHaveDistinctIssues`
- `ApkDownloadTest.diagnosticsAreBoundedAndContainOnlySafeMeasurements`
- `ApkDownloadTest.transientNetworkFailureRetriesWithBackoffThenSucceeds`
- `ApkDownloadTest.permanentNetworkAndDigestFailuresStopAfterTwoRetries`
- `ApkDownloadTest.matchingRangeResumesAndVerifiesWholeFile`
- `ApkDownloadTest.ignoredRangeUsesFullResponseWithoutAppending`
- `ApkDownloadTest.invalidContentRangeFallsBackToFullDownload`
- `ApkDownloadTest.wrongResumedHashFailsAsDigestMismatchAndRestartsFull`
- `ApkDownloadTest.prematureReadExceptionCanResume`
- `ApkDownloadTest.cancellationDuringBackoffDeletesPartialAndDoesNotRetry`
- `ApkDownloadTest.manifestTransportForwardsNoCacheAndRejectsCredentialHeaders`
- `ApkDownloadTest.rangeHeadersSurviveAllowedRedirects`
- `ApkDownloadTest.unknownLengthPartialResponseRestartsWithoutRange`
- `ApkDownloadTest.publicConnectionSendsNoCacheAndRangeWithoutEnablingCaches`
- `ApkDownloadTest.cancellationClosesBlockedReadAndDeletesFileBeforeReturning`
- `UpdateViewModelTest.downloadIssuesExposeOnlyTypedDiagnosticsAndNeverOfferInstall`

Existing UpdateCheckTest transport assertions now require the two no-cache headers on every request and redirect instead of requiring empty headers. Existing tests were preserved.

## Coordinator checks (not executed)

Review `/tmp/trove-updater-hardening.patch` first. Verify the required toolchain per the supplied skills, then run focused tests before the full JVM suites and lint:

```sh
./gradlew :app:testDebugUnitTest --tests app.trove.debrid.ApkDownloadTest --tests app.trove.debrid.UpdateCheckTest --tests app.trove.debrid.UpdateViewModelTest
./gradlew :core:test --tests app.trove.debrid.core.update.UpdatePolicyTest
./gradlew :app:testDebugUnitTest :core:test
./gradlew :app:lintDebug
```

Device checks remain: real carrier/CDN response, blocked socket cancellation and cleanup, range behavior against the mirror, selection/copying of error details, and signed install with independent install-time verification. These JVM fixtures do not establish phone behavior.

## Historical size constraint

The earlier update metadata omitted the APK size. The bitmere release contract
supplies the API asset byte count for both initial download and resume.

The diagnostics record lives only in the current Error state and can be copied from Settings; it does not persist across process death. There is no diagnostics history, file, log or saved-state copy.
