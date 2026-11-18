# Trove — multi-provider debrid Android app, v1

> Historical change. [bitmere-releases](../bitmere-releases/proposal.md) defines the current GitHub Releases distribution, update channels, and signing contract.

> Status — 2026-11-16: historical v1 (0.1.0) proposal, shipped as
> 0.1.0 on 2026-11-15. The Play path as built is requestdl, not
> createstream; see the [deviation note](specs/trove.md#deviations).
> V6 is historical only. Current design is the 2026 modern Material 3 UI
> shipped in 0.1.5: Downloads as the primary destination, no bottom nav,
> floating liquid-glass action cluster, Settings behind the avatar.
> The original intent below is preserved, not current policy. Signing
> is now secrets-only in GitHub Actions; local release builds cannot sign.

## Why

**Name: Trove.** The app is called Trove (decided by the user
2026-11-15; package `app.trove.debrid`, store listing title "Trove –
Debrid Library" — `app.trove` is taken on Google Play by an unrelated
card-collecting app, and another app called Trove exists there too).
The working name "Debrid" is dropped.

The original requester used TorBox Essential and asked (2026-11-15) for a phone app
that does what the TorBox web app does: see downloads, add downloads,
share a link from any browser and get easy add options, with the app
deciding torrent vs web download — and tapping a download plays it.

He then asked whether the app could be a debrid library like DMM
(Debrid Media Manager), working across multiple debrid services, not
TorBox-only. Research confirmed yes: DMM (open source, AGPL-3.0)
unifies Real-Debrid, AllDebrid, TorBox, Premiumize, Offcloud and
Debrid-Link behind one UI. The research report
(`docs/MULTI-PROVIDER-RESEARCH.md`, dated 2026-11-15)
covers DMM's architecture, all six providers' APIs, a capability
matrix, and a proposed Kotlin `DebridProvider` interface. Build order
from the research: TorBox → Real-Debrid → AllDebrid → Premiumize →
Offcloud → Debrid-Link.

On 2026-11-15 (18:47 IST) he said: "ok build using liquid glass
floating items and material 3". Design v6 is approved
(`docs/mockup-v6.html`): no bottom nav (one primary
destination), Torrents/Web/Usenet as top subtabs, search field at the
top of the list, floating liquid-glass action cluster bottom-right
(Add + Search), Settings behind the avatar.

## What v1 is (and is not)

In scope — the first working slice, TorBox as the reference provider:

- **Downloads list** (the one primary screen): Torrents / Web / Usenet
  subtabs with counts, one flat row per download (name, size, status,
  progress as a 3px track under the row), search field filtering the
  list, pull-to-refresh. Data comes from TorBox `mylist` (all three
  kinds). Delete per item. Empty / offline / signed-out states.
- **Add**: paste a magnet or link, or pick a `.torrent` / `.nzb` file.
  Cache check first (`checkcached`), so the user knows instantly
  whether it starts at once. Add uses the async create endpoints so
  adding feels instant.
- **Share target**: share a link from any browser → the app opens an
  add sheet with the type already detected: `magnet:` → torrent,
  `.torrent` link/file → torrent, `.nzb` → Usenet, any other URL → web
  download, checked against the supported-hoster list first, warning
  if unsupported.
- **Play**: tap Play on a ready row → TorBox `stream/createstream`
  returns a presigned stream link → handed to Android's
  `ACTION_VIEW` chooser, so any player that can open a web link works
  (Nuvio, VLC, MX Player, …). Play is never tied to one player.
- **Settings / accounts**: per-provider account rows. TorBox row in
  v1: paste the API key once, masked by default with an eye icon,
  "Test" calls `user/me` before saving. Keys are stored encrypted
  on-device (Android Keystore + EncryptedSharedPreferences) and never
  leave the device except in an `Authorization` header to TorBox's
  own API. The user enters the key in the app; nobody else ever sees
  it. Other providers appear as "coming soon" rows in v1.
- **Provider layer**: one `DebridProvider` interface in Android-free
  `:core`, with capability flags, a unified `DownloadStatus` enum and
  a typed error model (from the research, §4). The TorBox adapter is
  the reference implementation. Other providers plug in later without
  touching the UI.

Explicitly NOT in v1: adapters for Real-Debrid, AllDebrid, Premiumize,
Offcloud, Debrid-Link; RSS feeds; integrations (Dropbox/Drive/…);
subtitle/audio/resolution picker UI for streams (createstream defaults
are used); pause/resume UI (TorBox `controltorrent` operation values
are undocumented in the public spec — verify live before promising
it); device-code OAuth sign-in (paste-the-key is v1; device flow is a
later improvement); put.io.

## Architecture

- Kotlin + Compose phone app, same stack as Matrix. Modules: `:app`
  plus Android-free `:core` for the provider layer, TorBox client,
  share classifier, status mapping, rate limiting and credential-store
  abstraction — all JVM-testable without an emulator. `:core` is
  justified because the provider layer is the app's core asset and
  must be testable and reusable across providers; `:app` holds only
  Android adapters and Compose screens. No DI framework — plain
  constructor wiring, same as Matrix.
- Version catalog in `gradle/libs.versions.toml`; minSdk 26,
  targetSdk 34, compileSdk 36; versionName/versionCode in
  `version.properties` (0.1.0 / 1). AGP 8.13.2, Kotlin 2.2.20,
  Compose BOM 2026.03.00 (Material 3 1.4.0).
- Network: one HTTP transport interface in `:core`; OkHttp 4.12.0
  implementation. JSON via Gson 2.11.0 (cached locally; no new
  serialization dependency). No Retrofit — the TorBox surface is
  small and a hand-rolled client keeps the footprint small and the
  error mapping explicit. Rate limiting: a token bucket per provider
  (TorBox ~5 req/s general; `createtorrent` 50/hr, `createwebdownload`
  25/hr). Back off on 429, never retry in a tight loop.
- TorBox API (verified live 2026-11-15, spec at
  `docs/PROVIDERS.md` (TorBox API section)): base
  `https://api.torbox.app`, `/v1/api/...`, `Authorization: Bearer`.
  Endpoints used in v1: `GET /v1/api/torrents/mylist`,
  `GET /v1/api/webdl/mylist`, `GET /v1/api/usenet/mylist`,
  `POST /v1/api/torrents/asynccreatetorrent`,
  `POST /v1/api/webdl/asynccreatewebdownload`,
  `POST /v1/api/usenet/asynccreateusenetdownload`,
  `GET/POST /v1/api/torrents/checkcached` (+ webdl/usenet variants),
  `GET /v1/api/webdl/hosters`, `GET /v1/api/stream/createstream`,
  `GET /v1/api/user/me`, `POST /v1/api/torrents/controltorrent`
  (delete only in v1).
- **Security (hard rules):** a plain `requestdl` link embeds the API
  key — it must NEVER be handed to a player or any other app. Play
  uses only `createstream` presigned links. API keys are never logged,
  never put in a shared link, never backed up. Usenet is Pro-plan
  only — the Usenet subtab shows a clear "Pro plan" state when the
  account is Essential.

## Design — v6 tokens and rules (decided, not to be re-litigated)

Mockup: `docs/mockup-v6.html`.

- Colour: paper `#FFFFFF`, ink `#171717`, muted `#6E6E6E`,
  hairline `#E7E7E7`, jade `#00A67E` (the one accent: ready,
  progress, play, active), jade-soft `rgba(0,166,126,.12)`,
  track `#ECECEC`, chip `#F2F2F2`. Glass
  `rgba(255,255,255,.72)` — **glass appears exactly once, on the
  floating action cluster**. Everything else is solid.
- Type: Space Grotesk for the wordmark and big percent numerals,
  Inter for everything else (not Inter-only). Fonts are OFL-licensed
  and bundled in `res/font`.
- Layout primitive: a flat list on hairlines; progress is a 3px
  track under each row, never a card. Signature move: the right-hand
  status column — one word or one number per row (Ready / 62% /
  Queued) — plus a real play button on ready rows.
- Navigation (per One UI + Material 3 + Apple HIG, checked
  2026-11-15): one primary destination (Downloads), so **no bottom
  nav**. Torrents/Web/Usenet are top subtabs (One UI subtab pattern).
  Search is a field at the top of the list. Settings live behind the
  avatar. Add and Search are floating buttons bottom-right in the
  thumb zone (One UI 9.5 pattern).
- Material 3 components throughout (Scaffold, SearchBar-style field,
  SegmentedButton-style subtabs, sheets, FABs). Glass is the
  mockup's translucent surface (white 72% + hairline border +
  shadow); it is not a live backdrop blur — true backdrop blur
  (Haze or snapshot-based) is a named follow-up, not v1. Blur is
  confined to the floating cluster; content never blurs.
- Motion: state changes only; no entrance animation, no pulsing.
  Progress updates are plain state changes. The glass cluster does
  not animate on scroll.

## Testing plan

- `:core` JVM tests (no emulator, no network, no real key): share
  classifier (every input shape incl. unsupported hoster and
  malformed link), TorBox status mapping (every known state string),
  TorBox adapter against a fake HTTP transport (mylist parsing,
  createtorrent/checkcached/createstream request shapes,
  error-code mapping incl. 401/429), token-bucket rate limiter,
  credential-store masking (key never appears in a log/backup
  payload — tested via a fake store).
- `:app`: JVM tests for the view-model state machine (list loading /
  empty / error, search filtering, add-sheet state). Compose UI
  verification is by inspection against the v6 mockup; no screenshot
  tests in v1 (no device on this VM).
- Lint runs once before finishing; iteration builds run tests only.

## Distribution — Play, Trove only (decided 2026-11-15)

The user enabled internal app sharing in the Play Store. **Play
distribution is Trove-only — Matrix stays on GitHub releases +
mirror** and is unaffected by anything in this change.

- The Trove release build produces both a release AAB
  (`bundleRelease`) and a release APK (`assembleRelease`), signed
  and suitable for upload to Play internal app sharing.
- Internal sharing accepts an AAB/APK signed for the historical internal-sharing plan, so the
  v1 release build is debug-signed. No upload key is created for
  v1; a dedicated, permanent upload key (like Matrix's release key)
  is created only if/when Trove goes to a production track.
- Play re-signs internal-sharing uploads with its own certificate.
  Consequence: a Play-installed build will NOT install over a
  locally installed one (different certificate) — the first Play
  install means uninstalling the old build.
- The debug APK is for local testing only and is never uploaded.

## Risks / open questions

- TorBox `mylist` response field names for state/progress are not
  fully documented in the public OpenAPI spec (empty response
  schemas). The adapter parses defensively (unknown fields → null,
  unknown state strings → `UNKNOWN`) and the exact vocabulary is
  pinned from live responses at first run — see tasks.
- `controltorrent` operation values are undocumented — v1 uses it
  for delete only; pause/resume waits for a live check.
- No real TorBox account is available to this VM, so the adapter is
  verified against canned responses, not a live account. The user
  enters their own key in the app; first live run is on their phone.
