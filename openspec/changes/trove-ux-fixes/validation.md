# Coordinator validation (after diff review)

No Gradle compilation, JVM tests, lint, or device checks ran in the editing sandbox. Original app/core source trees are preserved at `/tmp/trove-ux-before/`; the review diff is `/tmp/trove-ux-fixes.diff`.

Run focused tests first:

```sh
./gradlew :app:testDebugUnitTest --tests 'app.trove.debrid.DownloadsOrderingRegressionTest' --tests 'app.trove.debrid.DownloadsSilentRefreshTest' --tests 'app.trove.debrid.AddIoRegressionTest' --tests 'app.trove.debrid.ShareRoutingTest' --tests 'app.trove.debrid.DownloadPresentationTest' --tests 'app.trove.debrid.AddViewModelTest' --tests 'app.trove.debrid.PlaybackPrivacyTest'
./gradlew :core:test --tests 'app.trove.debrid.core.TorBoxProviderTest' --tests 'app.trove.debrid.core.ShareClassifierTest'
```

Then the full JVM suite and lint once:

```sh
./gradlew :app:testDebugUnitTest :core:test
./gradlew :app:lintDebug
```

For real red/green evidence, use a separate temporary copy of this repository's build configuration with the preserved original app/src and core/src trees. Copy only the new baseline-compatible DownloadsOrderingRegressionTest.kt, AddIoRegressionTest.kt and ShareRoutingTest.kt into that copy. Run:

```sh
./gradlew :app:testDebugUnitTest --tests 'app.trove.debrid.DownloadsOrderingRegressionTest' --tests 'app.trove.debrid.AddIoRegressionTest' --tests 'app.trove.debrid.ShareRoutingTest'
```

All three should fail on the originals: unsorted ViewModel output; synchronous provider access; launcher owning share filters. These failures are expected but have NOT been executed as JVM tests. New silent-refresh/date tests target new APIs; parser assertions extend existing behavior and are not expected to fail on the original. A Python equivalent of the manifest routing assertions failed on the original and passed on the fix; changed XML parsed successfully. Version/release-workflow byte comparisons passed. Do not edit sources during any build.

Phone checks: Chrome HTTP/magnet SEND; magnet VIEW; torrent/NZB SEND/VIEW with content URI grants; file picker, cancel, failure/retry and rotation; close back to source app with no extra recents entry. Verify confirmation, newest ordering, tab/search preservation, long MKV titles and large fonts. Measure adding frame timing on a low-RAM/old phone and a current phone; verify no pull indicator or row flash. Verify requestdl per-kind playback still opens the player chooser and only the selected player receives the bearer link.

The supplied OpenAPI's successful mylist responses have no typed item properties; only validation-error fields were enumerated. No new API fields or network calls were introduced. Existing transfer/creation parsing was checked against code and synthetic fixtures. No MainActivity share fallback is needed: the new host handles every previous filter and passes all inputs to the same classifier/add flow.
