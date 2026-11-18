# trove-v1 — spec deltas

> Historical change. [bitmere-releases](../../bitmere-releases/proposal.md) defines the current GitHub Releases distribution, update channels, and signing contract.

## Deviations

Status recorded 2026-11-16. The original requirements below describe
historical v1 scope, not current policy.

- Staged docs said createstream-only and prohibited requestdl playback;
  shipped code uses per-file `requestdl` for the selected download kind
  and real file ID. This PR corrects docs to match code. Play hands that
  bearer link only to the chosen Android ACTION_VIEW player with a
  concrete video MIME derived from the filename, never to logs, share
  targets or error messages. `createstream`/`getstreamdata` are
  plan-restricted on Essential and unused.
- Signing is now secrets-only through GitHub Actions; local release builds
  cannot sign. Historical debug-signing requirements are superseded.
- The user superseded v6 on 2026-11-15; 0.1.5 shipped the 2026 modern
  Material 3 UI: Downloads as the primary destination, no bottom nav,
  floating liquid-glass action cluster, Settings behind the avatar.
  mockup-v6.html is a historical reference only. Bounded snapshot blur
  shipped in 0.1.1; the original no-live-blur intent below is historical.


## ADDED Requirements

### Requirement: Provider layer is one interface, one adapter per service
The app SHALL define a single `DebridProvider` interface in Android-free
`:core` (`core/src/main/java/app/debrid/core/DebridProvider.kt`) with
methods `validateAccount`, `listDownloads`, `getDownload`, `add`,
`checkCached`, `getStreamUrl`, `control`, `delete`, `supportsUrl`,
plus `service` and `capabilities`. Each debrid service SHALL be one
adapter file implementing it. The UI SHALL program only against the
interface, never against a provider's HTTP API.

#### Scenario: A new provider plugs in without UI changes
- WHEN a second adapter is added and registered in the provider
  registry
- THEN the Downloads screen, Add sheet, share flow and Play flow work
  against it with no changes to `:app` code.

### Requirement: Unified download model and status mapping
The app SHALL model every download as `DebridDownload` with a unified
`DownloadStatus` (QUEUED, DOWNLOADING, READY, PAUSED, ERROR, UNKNOWN)
and `progress` 0.0–1.0. Each adapter SHALL map its provider's status
vocabulary to the unified enum; unknown strings SHALL map to UNKNOWN,
never crash.

#### Scenario: Unknown TorBox state string
- WHEN `mylist` returns a `download_state` the adapter does not know
- THEN the row shows status UNKNOWN with progress 0 and the app keeps
  working.

### Requirement: Typed errors, one line each
All provider failures SHALL surface as `DebridError` (AuthInvalid,
RateLimited, NotCached, QuotaExceeded, UnsupportedCapability,
NameRefused, Network, Provider). The UI SHALL map each to exactly one
plain-language line. A `RateLimited` SHALL trigger exponential
back-off with jitter in the adapter, never a tight retry loop.

#### Scenario: 429 from TorBox
- WHEN the API returns 429
- THEN the adapter backs off, the UI shows "Too many requests —
  trying again", and no further request fires until the back-off ends.

### Requirement: TorBox adapter covers the v1 endpoint set
The TorBox adapter SHALL implement, over `Authorization: Bearer`:
mylist (torrents/webdl/usenet), async creates, checkcached,
hosters list, createstream, user/me, controltorrent delete. JSON
parsing SHALL be defensive: unknown fields ignored, missing fields
→ null.

#### Scenario: mylist with missing optional fields
- WHEN a torrent entry lacks `files` or `download_speed`
- THEN the row renders with size/name from the fields present and no
  crash.

### Requirement: Share classifier
`classifySharedContent` SHALL classify shared input as Magnet,
TorrentFile, Nzb, HosterLink, UnsupportedHoster, or NotShareable.
`magnet:` URIs and `.torrent` links/files → torrent; `.nzb` →
Usenet; any other URL → web download, validated against the TorBox
hosters list first. A bare info-hash (40 hex chars) SHALL be treated
as a magnet. Malformed or empty input SHALL be NotShareable.

#### Scenario: Unsupported hoster shared
- WHEN the user shares a link from a hoster not in the TorBox list
- THEN the sheet shows "This link isn't supported by TorBox" and
  offers no Add button.

### Requirement: Cache check before Add
The Add sheet SHALL call `checkCached` for a magnet/torrent hash
before confirming, and SHALL show "Cached — starts instantly" when
the provider already holds it. For providers without a read-only
check, `checkCached` returns null and the sheet skips the line.

#### Scenario: Cached torrent
- WHEN checkcached reports the hash cached
- THEN the sheet shows the cached line and Add completes immediately
  to READY.

### Requirement: Play uses presigned stream links only
Play SHALL call `getStreamUrl`, which for TorBox SHALL use
`stream/createstream` and return its presigned link. The app SHALL
hand that link to Android's `ACTION_VIEW` chooser (video/* MIME).
The app SHALL NEVER hand a `requestdl` link (which embeds the API
key) to a player or any other app.

#### Scenario: Play on a ready row
- WHEN the user taps Play on a READY download
- THEN the system chooser opens with the installed players (Nuvio,
  VLC, MX Player, …) and the stream URL contains no API key.

### Requirement: Downloads screen matches v6
The Downloads screen SHALL match mockup-v6.html: compact header
(20px Space Grotesk wordmark + plan label + avatar), Torrents/Web/
Usenet subtabs with counts, search field at the top of the list,
flat rows on hairlines with a 3px progress track and a right-hand
status column, a play button on ready rows, and NO bottom nav.
Add and Search SHALL be floating buttons bottom-right in the thumb
zone; the Add button is jade, the Search button is glass.

#### Scenario: Signed-out state
- WHEN no TorBox key is stored
- THEN the list shows "Add your TorBox API key in Settings" with a
  button that opens Settings, and no network call is attempted.

### Requirement: Settings and encrypted credentials
Settings SHALL sit behind the avatar and list one account row per
provider. The TorBox row SHALL accept a pasted API key, masked by
default with an eye icon, with a Test button calling `user/me`
before saving. Keys SHALL be stored in EncryptedSharedPreferences
backed by the Android Keystore, one entry per account, excluded
from backup. Keys SHALL never appear in logs, and SHALL leave the
device only in an `Authorization` header to that provider's API.

#### Scenario: Key validation
- WHEN the user taps Test with a wrong key
- THEN the row shows "Key rejected — check the key and try again"
  and nothing is saved.

### Requirement: Material 3 + liquid glass, glass once
The app SHALL use Material 3 components (Scaffold, sheets, FABs,
segmented subtabs, search field). The liquid-glass treatment SHALL
appear exactly once: the floating action cluster. The v1 treatment
is the mockup's exact translucent surface — white at 72% opacity,
a 65%-white hairline border, a soft shadow and a bright top edge —
which reads as frosted glass over the list scrolling beneath it. It
is NOT a live backdrop blur: true live backdrop blur in Compose
needs the Haze library or a snapshot-based blur, and neither is in
v1. The earlier "RenderEffect blur on API 31+" wording is
withdrawn — a RenderEffect on the button would blur the button's
own drawing, not the list behind it. Haze (or snapshot blur) is a
named follow-up, not a v1 blocker. Content SHALL never be blurred.

#### Scenario: API 26 device
- WHEN the app runs on API 26
- THEN the floating cluster renders as the translucent surface
  with no crash and no blur attempt.

### Requirement: Design tokens
The app SHALL define the v6 tokens as named values: paper
`#FFFFFF`, ink `#171717`, muted `#6E6E6E`, hairline `#E7E7E7`, jade
`#00A67E`, jade-soft `rgba(0,166,126,.12)`, track `#ECECEC`, chip
`#F2F2F2`. Type SHALL be Space Grotesk (wordmark, big numerals) and
Inter (everything else), bundled as OFL fonts in `res/font`.

#### Scenario: Token use
- WHEN a new screen is added
- THEN it uses the named tokens only — no ad-hoc hex colours.

### Requirement: Small footprint, no DI framework
The app SHALL use plain constructor wiring (no DI framework), one
HTTP client, and no unused dependencies. Release builds SHALL use
R8 minification. Version SHALL come from `version.properties`.

### Requirement: Play distribution, Trove only
Play distribution is Trove-only; Matrix stays on GitHub releases +
mirror and is unaffected. The Trove release build SHALL produce
both a signed release AAB (`:app:bundleRelease`) and a signed
release APK (`:app:assembleRelease`) suitable for upload to Play
internal app sharing. The v1 release build is debug-signed (internal
sharing accepts any signing key; Play re-signs the upload with its
own certificate). No dedicated upload key is created in v1; one is
created only if/when Trove goes to a production track. Because Play
re-signs, a Play-installed build SHALL NOT be expected to install
over a locally installed one — first Play install means
uninstalling the old build.

#### Scenario: Release artifacts
- WHEN `./gradlew :app:bundleRelease :app:assembleRelease` runs
- THEN `app-release.aab` and `app-release.apk` exist, both signed,
  and the APK verifies with `apksigner verify`.

## REMOVED Requirements
- None (new app; supersedes the draft `torbox-v1` change, which is
  left as a draft and not built).
