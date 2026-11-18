# Trove — Debrid Library

Trove is an Android app that puts your debrid downloads in one library.
Connect your TorBox account, and Trove shows everything you have added —
torrents, web links, and Usenet — with posters, titles, and one-tap
playback in the video player of your choice.

- **Package:** `app.trove.debrid`
- **Store listing title:** "Trove – Debrid Library" (`app.trove` is taken
  on Google Play by an unrelated card-collecting app, so the package is
  unique and the listing title distinguishes it)
- **Current version:** 0.1.18 (versionCode 18)
- **Releases:** [bitmere/trove on GitHub Releases](https://github.com/bitmere/trove/releases)
- **Status:** TorBox is the shipped provider. Real-Debrid, AllDebrid,
  Premiumize, Offcloud and Debrid-Link are supported too
  (see [Providers](#providers) below).

## What Trove does

- **One library for your downloads.** The Downloads screen lists your
  TorBox downloads in three subtabs — **Torrents**, **Web**, and
  **Usenet** — with a count on each tab. Pull down to refresh.
- **Posters, titles, and years.** Each row shows a poster, the proper
  title, the year, and the kind/status, resolved from the file name with
  a keyless Cinemeta lookup. If the title cannot be matched, the parsed
  title from the file name is kept. Posters are cached on disk (32 MB
  cap, LRU), so they survive a cold restart without a network round-trip.
- **Search and sort.** The search field at the top filters the current
  tab by file name (case-insensitive). The sort menu next to it offers
  Newest first, Oldest first, Name A–Z, Name Z–A, Largest first,
  Smallest first, and Progress highest first. Your sort choice is saved
  and restored across restarts; Newest first is the default.
- **Play in your player.** Tapping a ready download opens its files.
  Play resolves a per-file TorBox direct link and hands it to Android's
  player chooser, so VLC, MX Player, or any player that opens a video
  link works. See [Playback](#playback) below.
- **Add from anywhere.** Paste a link or pick a file (see
  [Adding downloads](#adding-downloads)). Sharing a link or file from
  another app opens Trove's small add window directly instead of the
  whole app.
- **In-app updates.** Trove checks GitHub Releases from Settings and
  verifies the APK before asking Android to install it (see
  [Updating](#updating)).

## Install

Trove is distributed as a signed APK from GitHub Releases — it is not on
the Play Store.

1. Open [Trove on GitHub Releases](https://github.com/bitmere/trove/releases)
   on your phone and download the latest `Trove-v<version>.apk`.
   Each release also has a `.sha256` sidecar file with the APK's SHA-256
   checksum, so you can verify the download if you want to.
2. Open the APK. Android may ask you to allow installs from the browser
   first — allow it for the browser you downloaded from, then install.
3. Open Trove and connect your TorBox account (see
   [Set up TorBox](#set-up-torbox)).

### Clean install note

Trove 0.1.18 uses a new signing key. If you are moving from a build
signed with an older key, the new APK **will not install over the old
one** — uninstall Trove first (in Android Settings → Apps), then install
the new APK. Uninstalling erases local settings and the saved API key,
so you will need to enter your TorBox API key again after installing.
Once you are on the new key, later updates install normally over the
existing app.

## Set up TorBox

Trove needs your TorBox API key. Your key is entered on your phone and
stored encrypted on the device — it is never in the code, in logs, or in
this repository.

1. Create a TorBox account at [torbox.app](https://torbox.app) if you
   do not have one.
2. In TorBox, go to **Settings → API** and copy your API key.
3. In Trove, open **Settings** (the avatar icon, top right). Paste the
   key into the API key field and tap **Test & save**. Trove checks the
   key against TorBox before saving it.
4. If the key is rejected, TorBox will say so — re-check the key in
   TorBox → Settings → API and try again. The **Show** / **Hide** button
   next to the field lets you see what you typed.

The key is sent only to TorBox. To disconnect, tap **Sign out** in
Settings — this removes the saved key from the device.

## Adding downloads

Tap **Add** (the **+** button, bottom right) and either:

- **Paste a link** — a magnet link, a torrent file link, an NZB link, or
  a hoster/web link. Trove detects what kind of link it is and adds it
  to the matching tab.
- **Choose a file** — pick a `.torrent` file or an `.nzb` file from your
  device.

Or **share** a link or file from another app (browser, file manager,
messenger, …) — Trove opens its add window directly.

After adding, Trove shows a "Download added to \<Tab\>" confirmation and
refreshes the library in the background.

## Using the library

- **Switch tabs** by tapping Torrents / Web / Usenet or swiping. Each
  tab keeps its own scroll position.
- **Search** by typing in the field at the top. The X icon clears the
  query. If nothing matches, the empty screen offers to clear the search.
- **Sort** with the sort icon next to the search field. The choice
  persists across restarts.
- **Open a download** by tapping it: if it has files, a details sheet
  lists them; if it does not yet, Trove loads the file list first.
- **Play** a ready download from the row or the details sheet. If a
  download has more than one file, pick the file to play.
- **Delete** a download by long-pressing it and confirming.
- **Refresh** by pulling down on the list. Rows stay visible while a
  refresh runs; only the pull indicator shows.

## Playback

Trove does not play video itself. For the file you pick, it asks TorBox
for a per-file direct download link (`requestdl`) and hands that link to
Android's player chooser with a video type derived from the file name,
so you pick the player every time — unless you set a default.

- **Default player (Settings → Default player):** "Ask every time" is
  the default. You can instead pick a preferred video player from the
  players installed on your phone. If that player is later uninstalled
  or unavailable, Trove falls back to the chooser without clearing your
  choice.
- **Play with…:** on any file, the "Play with…" option opens the chooser
  for that one playback, even when a default player is set.

The direct link is a per-file bearer link. It goes only to the player
you pick — never into a log, a share target, or an error message.
Trove does not use TorBox's streaming API (`createstream`), which is
restricted on some plans; the download-link path works with a standard
TorBox account.

## Updating

Trove updates itself from GitHub Releases — no need to re-download
manually.

1. In Trove, open **Settings** and tap **Check for updates**.
2. Pick an update channel first if you want: **Stable** (the default —
   latest stable release) or **Beta** (includes prereleases, picks the
   highest version code). The channel is saved on the device. Changing
   it clears any pending update offer, and the next check is manual.
3. If an update is found, Trove downloads it and verifies the APK's
   size, SHA-256, package, version, and signing identity before asking
   Android to install it. Android asks for confirmation, and Trove can
   relaunch itself after a successful update.

If a download fails, Trove tells you what went wrong (empty, truncated,
or digest mismatch) with copyable diagnostics — byte count, expected
size, and computed digest, with no URLs or keys in them — and retries
with resume where possible.

Note: returning to the Stable channel while a beta is installed leaves
the beta in place until a stable release with a higher version code
appears.

## Providers

TorBox is the reference provider. All six providers are supported, in
build order:

1. TorBox — shipped
2. Real-Debrid — supported
3. AllDebrid — supported
4. Premiumize — supported
5. Offcloud — supported
6. Debrid-Link — supported

Each provider gets its own client module behind one library UI. The
research behind this plan is in
[docs/MULTI-PROVIDER-RESEARCH.md](docs/MULTI-PROVIDER-RESEARCH.md) and
[docs/PROVIDERS.md](docs/PROVIDERS.md).

## Requirements

- Android 8.0 or later (API 26+). Trove is a phone app.
- A TorBox account and its API key.
- A video player that can open a web link (VLC, MX Player, and similar)
  for playback.

## Build from source

JDK 17, Android SDK (platform 36), Gradle 8.13:

```sh
export JAVA_HOME=~/workspace/android-tooling/jdk-17.0.21+9
export ANDROID_HOME=~/Android/sdk
export JDK_JAVA_OPTIONS="-Djava.net.preferIPv4Stack=true"
./gradlew :app:testDebugUnitTest :core:test lint
```

- The version comes from `version.properties` — `versionName` and
  `versionCode`. Increase `versionCode` for every newly installed
  artifact.
- Iteration builds run tests only; lint runs once before a PR.
- Release signing is secrets-only in GitHub Actions (keystore and
  password live in the `TROVE_KEYSTORE_BASE64` /
  `TROVE_KEYSTORE_PROPERTIES` secrets). Local release builds cannot
  sign; debug builds work locally without signing material.
- Do not run two Gradle gates in parallel on the same Gradle home: set
  `GRADLE_USER_HOME=~/workspace/android-tooling/gradle-home`, where the
  cached dependencies live.

## Modules

- `:core` — Android-free provider layer: provider abstraction, TorBox
  adapter, share classification, status mapping, rate limiting,
  credential-store abstraction. JVM-testable, no emulator.
- `:app` — Compose screens (Downloads, Add, Settings), share target,
  Play chooser, Keystore-backed credential storage.

## Security and privacy

- The TorBox API key lives only in encrypted storage on the device. It
  is never in the code, in logs, in backups, or in this repository.
- Playback links are per-file bearer links and are handed only to the
  player you choose — never logged, shared, or put in error messages.
- The in-app updater verifies the APK's size, SHA-256, package, version,
  and signing identity before installation, and reports distinct errors
  with copyable, privacy-safe diagnostics.

## Documentation

- [Documentation index](docs/README.md) — architecture, providers,
  distribution, design, and diagnostic history.
- [Changelog](CHANGELOG.md) — what shipped in each version.
- [House rules](AGENTS.md) — how to work in this repository.
- [Testing guide](docs/TESTING.md) — how to run the tests.
- [OpenSpec changes](openspec/changes/) — proposals, tasks, and
  specifications for each change, kept with the code.
