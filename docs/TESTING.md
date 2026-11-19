# Testing

## Environment

JDK 17 (Temurin), Android SDK platform 36, Gradle wrapper 8.13.
Check that the JDK, SDK and wrapper exist before building. The following
paths are workspace examples; use your installed locations:

```sh
export JAVA_HOME=~/workspace/android-tooling/jdk-17.0.21+9
export ANDROID_HOME=~/Android/sdk
export GRADLE_USER_HOME=~/workspace/android-tooling/gradle-home
export JDK_JAVA_OPTIONS="-Djava.net.preferIPv4Stack=true"
"$JAVA_HOME/bin/java" -version
"$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" --version
./gradlew --version
```

Use the repository wrapper, not a system Gradle. Confirm SDK platform 36
is installed (`sdkmanager --list_installed`, using its installed path).
Keep machine-specific proxy settings outside the repo's `gradle.properties`.

## Commands

Run focused tests first, then collect edits before the full verification:

- Unit tests: `./gradlew :app:testDebugUnitTest :core:test`
- Full app lint (once before a PR): `./gradlew :app:lint`
- Debug APK: `./gradlew :app:assembleDebug`
- CI gate: `./gradlew --no-daemon :app:testDebugUnitTest :core:test :app:lint :app:assembleDebug`
- Source guard: `python3 scripts/check-senior-rewrite.py`
- Verification timing: add `--profile`; prefer incremental builds to `clean`.
- Release APK: local release builds cannot sign. Signing is secrets-only
  and happens only in GitHub Actions; `:app:assembleRelease` and
  `:app:bundleRelease` are not local artifact-producing commands.

## Debug-only convention

`./gradlew test` is not the CI gate: it runs `:app` unit tests twice
(debug + release variants) and reports 808 results for 570 unique tests
(312 `:app` debug + 238 `:app` release + 258 `:core`; 566 `@Test`
annotations — the parameterized add-rejection test in
`AddAuthRejectionTest` is one annotation run × 5 services, so
annotations undercount executions by 4). `:core` has no release-variant
run. CI runs debug-only (`:app:testDebugUnitTest :core:test`),
which is the 570. Run debug-only locally too; the release-variant re-run
adds ~20 s (measured 20.4 s in the 2026-11-19 audit run) for no new
coverage. Keep the release run available for the 1–2 R8-sensitive tests
in `StartupRegressionTest` (shipped account-JSON compat cases).

Do not edit app/core sources during a build. The 0.1.14 baseline
(2026-11-16) is 107 JVM tests and lint 0 errors; this is a recorded
baseline, not a claim that T2 reran the suite. TARS runs the build checks.

## What JVM tests cannot prove

A passing suite does not prove on-device behaviour: player chooser,
share target, encrypted storage, glass backdrop and navigation must be
verified on a real phone.
