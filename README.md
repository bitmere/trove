# Trove

Multi-provider debrid Android app. One library UI over TorBox,
Real-Debrid, AllDebrid, Premiumize, Offcloud and Debrid-Link — in that
build order. v1 ships TorBox only, as the reference provider.

Store listing title: "Trove – Debrid Library" (package
`app.trove.debrid`; `app.trove` is taken on Google Play by an unrelated
card-collecting app, and a different app called Trove exists there too
— the package is unique and the listing title distinguishes it).

## Distribution

Get signed APKs from [Trove on GitHub Releases](https://github.com/bitmere/trove/releases).
In Settings, select Stable (default) or Beta, then tap **Check for updates**.
Beta includes stable releases and prereleases. Trove verifies the APK before
asking Android to install it.

Moving to the bitmere signing key requires a clean install. Uninstall the
previous build, install the signed APK, and enter your API key again.
Uninstalling erases local settings. See [Distribution](docs/DISTRIBUTION.md).

Current design: 2026 modern Material 3 UI, one primary destination
Downloads, no bottom nav, floating liquid-glass action cluster (Add +
Search), Settings behind the avatar. [mockup-v6.html](docs/mockup-v6.html)
is a historical design reference only. Historical v1 proposal:
[openspec/changes/trove-v1/](openspec/changes/trove-v1/).

Start with the [documentation index](docs/README.md),
[house rules](AGENTS.md), and [testing guide](TESTING.md).

## Build

JDK 17, Android SDK (platform 36), Gradle 8.13:

```sh
export JAVA_HOME=~/workspace/android-tooling/jdk-17.0.21+9
export ANDROID_HOME=~/Android/sdk
export JDK_JAVA_OPTIONS="-Djava.net.preferIPv4Stack=true"
./gradlew :app:testDebugUnitTest :core:test lint
```

Version comes from `version.properties`. No repo is created or pushed
from this checkout without the user's explicit tap.

## Modules

- `:core` — Android-free provider layer: `DebridProvider` interface,
  TorBox adapter, share classifier, status mapping, rate limiting,
  credential-store abstraction. JVM-testable, no emulator.
- `:app` — Compose screens (Downloads, Add, Settings), share target,
  Play chooser, Keystore-backed credential storage.

## Security

API keys live only in EncryptedSharedPreferences on the device. Play
resolves a per-file TorBox `requestdl` direct link for the selected download kind
and real file ID. This sensitive bearer link goes only to the chosen ACTION_VIEW
video player with a concrete video MIME derived from the filename, never to logs, share targets or error messages. The plan-restricted
streaming API is not used.
