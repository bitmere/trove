# Architecture

## Modules

- **:core** — Android-free pure Kotlin. No Android imports. Providers,
  HTTP transport, rate limiting, share classification, models, errors.
  Unit-tested on the JVM with no Robolectric.
- **:app** — Android app. Compose UI (2026 modern Material 3), ViewModels,
  repository, Keystore credential storage, MainActivity + NavHost,
  share target. Depends on :core.

Package: `app.trove.debrid`. Version from `version.properties`
(versionName / versionCode). compileSdk 36, minSdk 26, targetSdk 34.
Kotlin 2.2.20, AGP 8.13.2, Compose BOM 2026.03.00 (UI 1.10.5),
navigation-compose 2.9.8. No DI framework; `AppContainer` wires
dependencies by hand.

## Provider abstraction

`DebridProvider` (core) is the interface every debrid service
implements: list, create (torrent / web / usenet), cache check, per-file playback link,
delete, account. `TorBoxProvider` is the reference implementation.
`DebridRepository` (app) owns the saved key and the active provider;
the UI never talks to a provider directly. Build order: TorBox, then
Real-Debrid, AllDebrid, Premiumize, Offcloud, Debrid-Link — see
[Providers](PROVIDERS.md).

## Screens and navigation

One primary destination: Downloads. NavHost has two destinations,
`downloads` and `settings`. Settings is reached from the avatar, never
from a bottom nav (there is none). Predictive back is enabled;
Settings back pops to Downloads without refreshing the list. Downloads
refreshes only when the account actually changes.

Downloads has three top subtabs (Torrents / Web / Usenet), a search
field at the top of the list, and a floating liquid-glass action
cluster (Add + Search) bottom-right. The Add sheet has a live cache
check. Play resolves a per-file TorBox `requestdl` direct link for the selected
download kind and real file ID, then hands it to the Android `ACTION_VIEW`
player chooser with a concrete video MIME derived from the filename.
This per-file bearer link goes only to the player the user picks, never
to logs, share targets or error messages. The streaming API
(`createstream`/`getstreamdata`) is plan-restricted on Essential and is not used.

## Data flow

UI state lives in ViewModels as StateFlow. Repository calls are
main-safe: the OkHttp transport moves blocking calls onto an IO
dispatcher and cancels the call when the caller is cancelled. Provider failures use `DebridError` (including AuthInvalid, RateLimited,
and Network) and the UI exposes error feedback.

Historical design reference: [mockup-v6.html](mockup-v6.html), superseded
by the modern UI shipped in 0.1.5.

## Backdrop blur

The floating Search button shows a true backdrop blur via a bounded
snapshot: the Downloads list content is recorded into a GraphicsLayer
on scroll/content changes (throttled), and the button draws that layer
blurred underneath itself. Only on API 31+ and non-low-RAM devices;
everywhere else it falls back to a plain translucent scrim. The
capture must never show a stale snapshot of a previous screen.
