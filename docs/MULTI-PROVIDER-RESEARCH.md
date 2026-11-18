# Multi-Provider Debrid Research — building a DMM-like app

> Correction and status — 2026-11-16: this is research dated 2026-11-15,
> not feature commitments. Only TorBox is shipped; other provider adapters,
> provider switching and proposed interface capabilities are plans. Shipped
> Play resolves a per-file `requestdl` direct link for the selected kind and
> real file ID, then uses the Android ACTION_VIEW chooser with a concrete
> video MIME derived from the filename. The bearer link goes only to the
> chosen player, never logs, share targets or error messages.
> `createstream`/`getstreamdata` are plan-restricted on Essential and unused.
> Contrary Play recommendations below are preserved research, not policy.
> Current design is the 2026 modern Material 3 UI: Downloads is the primary
> destination, no bottom nav, floating liquid-glass action cluster, Settings
> behind the avatar. mockup-v6.html is historical only.

**Date:** 2026-11-15
**Decision behind this:** user chose multi-provider, "like DMM" — not TorBox-only.
**Scope:** research only. No code written, no mockup edited, nothing pushed.
**Local study copy:** a local shallow clone (depth 1, HEAD `e743a1eb4027706227a053b1b0bd9f862fa2eed9`, 2026-11-15), kept outside the repo. Read-only study — no DMM code is copied into our project.

## 0. Executive summary

Yes, it is buildable, and DMM proves the shape in production. DMM (Debrid Media Manager) is a free open-source Next.js web app that unifies **Real-Debrid, AllDebrid, TorBox, Premiumize, Offcloud, Debrid-Link and put.io** behind one library UI. Its unification is **not** a single shared interface class. It is:

1. a six-way union type — `DebridService = 'RD' | 'AD' | 'TB' | 'PM' | 'OC' | 'DL'`;
2. one standalone client module per provider (`src/services/realDebrid.ts`, `allDebrid.ts`, `torbox.ts`, `premiumize.ts`, `offcloud.ts`, `debridLink.ts`), each exporting its own functions;
3. orchestration layers on top — a unified availability check, a transfer registry/reconciler that tracks downloads from every provider in one list, and per-provider "cast" adapters for streaming.

For our Kotlin Android app, the cleaner move is what DMM *implies* but doesn't do: define one `DebridProvider` interface in Kotlin and write one adapter per service (section 5). The per-provider quirks that drive the design are real and documented below — the biggest are: **Real-Debrid and Debrid-Link have no read-only cache check** (the probe is add-then-delete, and RD punishes bursts of adds), **Real-Debrid requires `selectFiles` after every add**, **AllDebrid's cache signal is the `ready` flag on upload**, and **TorBox's plain download links embed the API key** (Play must go through the streaming API's presigned links).

Build order recommendation: **TorBox first** (the user has it, Essential plan, API access confirmed), **Real-Debrid second** (largest user base, hardest quirks — get it right early), then AllDebrid, Premiumize, Offcloud, Debrid-Link last (see §5.5 for reasoning).

---

## 1. DMM deep dive

Sources: the cloned repo itself; [DMM README](https://github.com/debridmediamanager/debrid-media-manager/blob/main/README.md); [DMM `cast-alldebrid.md`](https://github.com/debridmediamanager/debrid-media-manager/blob/HEAD/cast-alldebrid.md) (a design doc inside the repo that records measured provider behaviour, incl. corrections dated 2026-11-05).

### 1.1 What DMM is

> "Debrid Media Manager is a free open source web app for building a movie and TV library without filling a hard drive. It adds torrents to Real-Debrid and AllDebrid and TorBox and Premiumize and Offcloud and Debrid-Link, and to put.io." — README

It is a **Next.js web app** (not a native app): `src/` has `components`, `contexts`, `hooks`, `pages`, `scrapers`, `services` (179 non-test `.ts` files), `torrent`, `types`, `utils`; persistence via Prisma (`prisma/`). Its core loop is media-library-first, not download-manager-first: search for a movie/TV release, see which of your connected debrid services already has it cached, add it to one of them, and the library view unifies all of them. It also has Stremio integration (DMM as a Stremio addon), Trakt/Simkl sync, and "Cast" (sharing streams).

### 1.2 How it unifies providers

**There is no `DebridProvider` interface.** The unification is three layers:

**Layer 1 — a union type.** `src/hooks/useAvailabilityCheck.ts:24`:

```ts
export type DebridService = 'RD' | 'AD' | 'TB' | 'PM' | 'OC' | 'DL';
```

Everywhere the app needs to talk about "which service", it uses this code. Keys are stored per service code.

**Layer 2 — one client module per provider** in `src/services/` (line counts from the clone):

| File | Lines | Auth | Notable exports |
|---|---|---|---|
| `realDebrid.ts` | 1,141 | OAuth2 device flow | `getDeviceCode`, `getToken`, `getUserTorrentsList`, `getTorrentInfo`, `addHashAsMagnet`, `addTorrentFile`, `selectFiles`, `deleteTorrent`, `unrestrictLink` |
| `allDebrid.ts` | 947 | API key (Bearer) + PIN flow | `getPin`, `checkPin`, `uploadMagnet`, `uploadTorrentFile`, `getMagnetStatus`, `getMagnetFiles`, `getMagnetLinks`, `deleteMagnet`, `restartMagnet`, `unlockLink` |
| `torbox.ts` | 992 | API key (Bearer) | `createTorrent`, `controlTorrent`, `deleteTorrent`, `getTorrentList`, `requestDownloadLink`, `checkCachedStatus`, `createWebDownload`, `getWebDownloadList`, `createUsenetDownload`, `getUserData`, `getStats` |
| `premiumize.ts` | 562 | API key (Bearer) / OAuth | `checkPremiumizeCache`, `listPremiumizeTransfers`, `createPremiumizeTransfer`, `directDownloadPremiumize`, `deletePremiumizeTransfer`, `listPremiumizeFolder` |
| `offcloud.ts` | 552 | API key (Bearer) | `checkOffcloudCache`, `addOffcloudCloud`, `getOffcloudCloudStatus`, `exploreOffcloudCloud`, `getOffcloudHistory`, `removeOffcloudCloud` |
| `debridLink.ts` | 1,347 | OAuth2 device flow (+ API key) | `listSeedboxTorrents`, `addSeedboxTorrent`, `deleteSeedboxTorrents`, `zipSeedboxTorrent`, `checkDebridLinkCache`, `getSeedboxLimits` |

Each module is axios-based, self-contained, and exports plain functions — no shared base class, no common method names. `realDebrid.ts` calls its add `addHashAsMagnet`; `allDebrid.ts` calls it `uploadMagnet`; `torbox.ts` calls it `createTorrent`. The modules also carry heavy provider-specific machinery: RD has add-pause tracking (`RD_ADDS_PER_MINUTE = 30`, `RD_ADD_MIN_SPACING_MS`, `RD_BULK_ADD_SPACING_MS = 60_000`, `rdAddPause.ts`, `deInfringe.ts` name filter); TorBox has rate-limit/server-lockout tracking (`torbox.rateLimit.ts`, `torbox.serverBudget.ts`, `torbox.serverLockout.ts`); AllDebrid has `waitForAdRateLimit`.

**Layer 3 — orchestration.** The shared behaviour lives in cross-provider modules, not in the clients:
- `src/hooks/useAvailabilityCheck.ts` — the unified cache check. It calls each provider's check function for the connected services and merges results into `availableHashesByService: Partial<Record<DebridService, Set<string>>>`. Its long comment block is the most important design note in the repo: RD retired `/instantAvailability`, so the only way to ask whether RD holds a hash is to add the torrent and delete it again — and RD answers a run of adds by refusing every add on the account for a while (`451 infringing_file`). DMM measured this (2026-08-28): a sweep at concurrency 3 got 2 usable answers out of 15 hashes and left the account refusing the user's own adds for minutes. So RD probes run one at a time, starting at the bulk spacing, widening on pushback, aborting after 5 throttled rows.
- `src/services/transferList.ts`, `transferRegistration.ts`, `debridTransferReconcile.ts`, `contentRequestReconcile.ts`, `debridUploaderRegistration.ts` — the unified transfer registry. Every add, from any provider, is registered in the database as a `TransferRow`/`DebridTransferRecord` and reconciled (status polling, filing into the library, dedupe). This is what makes "one library" real: the list view reads the registry, not six provider lists.
- `src/services/database/availability*.ts` + `src/utils/instantChecks.ts` — a DB-backed cache of availability answers, so the app doesn't re-probe providers for hashes it already knows.
- Per-provider "cast" adapters (`allDebridCastSelection.ts`, `database/*Cast.ts`, `utils/*CastApiClient.ts`) — each provider needs its own stream-resolution path for Cast/Stremio.

### 1.3 Auth per provider (as DMM implements it)

| Provider | DMM's auth path | Notes |
|---|---|---|
| Real-Debrid | OAuth2 device flow (`/oauth/v2/device/code` → `/oauth/v2/device/credentials` → `/oauth/v2/token`, grant `http://oauth.net/grant_type/device/1.0`); tokens in `rdTokenStorage` | No API key pasted; user approves on the RD site. DMM routes calls through its own proxy (`getProxyUrl`) because RD blocks browser origins. |
| AllDebrid | API key, `Authorization: Bearer` header; PIN flow (`GET /v4.1/pin/get` → user enters PIN on alldebrid.com → `POST /v4/pin/check`) for in-app signup | PIN flow means the user never pastes a key. |
| TorBox | API key, `Authorization: Bearer` header | Key pasted by user. |
| Premiumize | API key (Bearer) or OAuth (PKCE/device code) via `premiumizeOAuth.ts` + a same-origin proxy (`/api/premiumize`) | Uploads go through the proxy (`premiumizeUploadProxy.ts`). |
| Offcloud | API key, `Authorization: Bearer` header | Key pasted by user. |
| Debrid-Link | OAuth2 device flow (`debridLinkOAuth.ts`, device code) via proxy `/api/debridlink-oauth`; API key also supported (`access_token` query param per DL docs) | DMM prefers device flow. |

Key architectural consequence: **a mobile app does not need DMM's proxies** — DMM proxies because browsers enforce CORS and RD blocks web origins. A native Android app calls the provider APIs directly. But the OAuth device flows still matter: RD and DL are best onboarded with a code the user enters on the provider's site, not a pasted key.

### 1.4 How Add / cache-check / stream-link differ per provider (DMM's own words)

From the README:

> "**Add** accepts full magnets standalone v1 info hashes and `.torrent` files for Real-Debrid AllDebrid TorBox Premiumize Offcloud and Debrid-Link. Debrid-Link hash lists stay **cached-only**. Full magnets and `.torrent` files can start a download. TorBox also accepts direct HTTP(S) links."

- **Cache check:** TB, PM and OC have real read-only endpoints (`checkcached`, `/cache/check`, `cache`). RD has none since 2024-11-22 — probe is add-then-delete, paced, abort-on-throttle. AD has no read-only probe (`/v4/magnet/instant` 404s; v4 `/magnet/status` discontinued) — the probe *is* the upload, and the upload's `ready` flag is the answer; a miss must be deleted afterwards, and AD dedupes re-uploads of the same hash so a repeated check doesn't create a duplicate. DL's probe is also add-then-remove (DMM's `checkDebridLinkCache` has a probe budget, concurrency limit and hour-long lockout handling; "a hit lands in the probe account… and is removed again").
- **Add:** RD needs `selectFiles` after every add or the torrent never starts; the other five start automatically. RD also refuses some release names with `451 infringing_file` (May 2026 filter) — DMM keeps a blocklist (`deInfringe.ts`) and skips probing blocked names. DL hash-only adds are cached-only: "The hash is only added if it is already cached on our servers" (DL docs).
- **Stream link:** RD is two-step (`torrents/info` → `links[]` → `unrestrict/link` → `download` URL). AD is two-step too (`magnet/files` → locked `/f/` token links → `link/unlock`). TB gives a direct link (`requestdl`) or a stream (`stream/createstream`). PM's `transfer/directdl` returns links with no unrestrict step. OC resolves via `cloud/explore`. DL seedbox files already carry `downloadUrl`.

### 1.5 How the UI switches providers

DMM does **not** have a single "active provider" switcher. It is **multi-provider simultaneously**: the user connects as many accounts as they have, and the search/library UI shows **per-service availability** for every result. `src/components/CheckAvailabilityButtons.tsx` renders one button per service that needs a manual check — RD, AD and DL get buttons (because their probes are mutating/expensive: "Each Debrid-Link hit is added and removed again, which is why it is a button"), while TB, PM and OC are checked automatically. `MovieSearchResults.tsx` / `TvSearchResults.tsx` then display which services have each release cached. When the user adds something, the reconcile layer picks/registers the transfer against one provider and the library lists it regardless of provider.

For our app this is a design choice, not a given: DMM's "check everything, show availability per service" vs a simpler "one active provider at a time with a switcher". See §5.5 and §7.

### 1.6 License — what AGPL-3.0 means for us

DMM's `LICENSE` is the **GNU Affero General Public License v3**. Consequences:

- **Studying the architecture is fine.** Reading how DMM structures providers, auth and reconciliation, and writing our own code from that understanding, does not trigger AGPL obligations.
- **Copying DMM code into our app would.** AGPL-3.0 is a strong copyleft licence: if we copied DMM source (or a modified version of it) into our app and distributed the app, we would have to release our app's corresponding source under AGPL-3.0 too, including to users who only interact with it over a network. Our app is intended to be a normal closed-source personal app, so: **do not copy, port, or translate DMM code** — not the client modules, not the reconciler, not even "small" helpers. Write everything from the provider APIs themselves (section 2) and from the design in section 5.
- The same applies to zurg (same org, same licence family) and to any AGPL-licensed debrid code we read during this research.

### 1.7 zurg (sister project, one paragraph)

[zurg](https://github.com/debridmediamanager/zurg-public) (site: https://zurg.debridmediamanager.com/) is DMM's sister project: instead of an app UI, it **puts every debrid account and Usenet in one folder** that Plex, Jellyfin and Emby read like a normal drive — it serves the accounts over WebDAV, rclone mounts it, and the media servers see ordinary movies and shows. The public stable release (v1.0.0) covers Real-Debrid only; sponsor nightlies add TorBox, AllDebrid, Premiumize, Debrid-Link, Offcloud and Usenet. It matters to us only as confirmation of the same idea at a different layer: one abstraction over all providers, with the per-provider quirks hidden inside a backend per service. We are not using zurg — our app talks to the provider APIs directly.

---

## 2. Each provider's API

TorBox was already researched live on 2026-11-15 and is summarised in §2.1 from the TorBox OpenAPI spec (90 endpoints, OpenAPI 3.1.0, info 1.0.0, base `https://api.torbox.app`, still v1 — no v2 exists), source https://api.torbox.app/openapi.json. The other five were researched from their official docs on 2026-11-15. One gap: Real-Debrid's official docs page (`https://real-debrid.com/apidocs`) returned HTTP 403 when fetched on 2026-11-15, so RD endpoints below are verified against DMM's `realDebrid.ts` source, the [Ferrite RD/TorBox API reference](https://github.com/gavanduffy/Ferrite/blob/HEAD/real-debrid-torbox-api.md) and [debrid-ts](https://git.tbdh.app/Libraries/debrid-ts) — all three agree.

### 2.1 TorBox — `https://api.torbox.app`, `/v1/api/...`

- **Auth:** API key. Free plan has **no API access**; Essential+ required (user is on Essential). Key sent as `Authorization: Bearer` (DMM) or `?token=` query param on some endpoints.
- **List:** `GET /v1/api/torrents/mylist`, `GET /v1/api/webdl/mylist`, `GET /v1/api/usenet/mylist`.
- **Add:** `POST /v1/api/torrents/createtorrent` (magnet or .torrent file), `POST /v1/api/webdl/createwebdownload` (direct link), `POST /v1/api/usenet/createusenetdownload` (.nzb). Async variants exist: `asynccreatetorrent`, `asynccreatewebdownload`, `asynccreateusenetdownload` (return immediately, process in background).
- **Cache check:** `GET/POST /v1/api/torrents/checkcached` (also webdl/usenet variants) — a real read-only check.
- **Get link:** `GET /v1/api/torrents/requestdl` (`token`, `torrent_id`, `file_id`, `zip_link`, `redirect`), batch variant `requestdl/batch` (GET+POST). **Caution:** plain `requestdl` links embed the API key — never hand one to a player directly.
- **Play (the right path):** `GET /v1/api/stream/createstream` (`id`, `file_id`, `type`, `chosen_subtitle_index`, `chosen_audio_index`, `chosen_resolution_index`, `scrobbling_enabled`) + `GET /v1/api/stream/getstreamdata` (`token`, `presigned_token`, same choices). Returns a presigned stream link any player can open — this is what Play should use.
- **Control/delete:** `POST /v1/api/torrents/controltorrent`, `POST /v1/api/torrents/controlqueued`, `PUT /v1/api/torrents/edittorrent`, `GET /v1/api/torrents/torrentinfo`. (The public spec does not document `controltorrent`'s operation values — verify against live behaviour before building pause/resume.)
- **Extras:** `GET /v1/api/webdl/hosters` (public list of supported hosters — use this to validate a shared link before adding), RSS feeds (`/v1/api/rss/*`), integrations (export to Dropbox/Google Drive/OneDrive/Gofile/Pixeldrain/1Fichier, with job status), notifications, `GET /v1/api/user/me`, `GET /v1/api/user/stats`, speedtest.
- **Rate limits:** ~5 requests/sec per IP general; `createtorrent` 50/hour per IP; `createwebdownload` 25/hour per IP (TorBox docs, checked 2026-11-15). DMM additionally tracks TorBox server-side lockouts, so the client must back off on 429s rather than retrying blindly.
- **Quirks:** Usenet is **Pro plan only**. Max torrent size and active slots vary by tier (Essential: 3 active, 200 GB max). Latest changelog v9.3.1 (2026-11-09).

### 2.2 Real-Debrid — `https://api.real-debrid.com/rest/1.0/`

Official docs: https://api.real-debrid.com/rest/1.0/ is the API base; the docs page at `https://real-debrid.com/apidocs` returned **403** on 2026-11-15, so endpoints are cross-checked from DMM source + Ferrite + debrid-ts (all agree).

- **Auth:** OAuth2 device flow (recommended for apps): `GET /oauth/v2/device/code?client_id=…&new_credentials=yes` → user approves on real-debrid.com → `GET /oauth/v2/device/credentials` → `POST /oauth/v2/token` (grant `http://oauth.net/grant_type/device/1.0`). A static API token also exists at `https://real-debrid.com/apitoken` for simple clients. All calls send `Authorization: Bearer <token>`. Premium account required for most API features.
- **List:** `GET /torrents?page=&limit=` (paginated). `GET /torrents/info/{id}` for one torrent (status, files, links).
- **Add:** `POST /torrents/addMagnet` (form `magnet=`), `PUT /torrents/addTorrent` (upload .torrent file). **Then `POST /torrents/selectFiles/{id}` (`files=all` or comma-separated IDs) is REQUIRED — the torrent will not start without it.** Response is HTTP 204.
- **Cache check:** **none.** `GET /torrents/instantAvailability/{hashes}` was disabled 2024-11-22 (`error_code` 37) and nothing replaced it. The only probe is add → check status → delete. DMM's measured sustainable rate is ~30 adds/minute per account, with bulk spacing 60 s; a burst of adds triggers `451 infringing_file` pauses. `unrestrict/link` needs ≥5 s spacing; `addMagnet` ~20 s.
- **Get link (two steps):** `torrents/info/{id}` returns `links[]` — restricted host URLs, parallel to the *selected* files in order. Then `POST /unrestrict/link` (form `link=`) returns `{ download }` — the real stream URL. Also `POST /unrestrict/check` (no auth) to test a hoster link before unrestricting, and `POST /unrestrict/folder`, `PUT /unrestrict/containerFile` (RSDF/CCF/DLC decryption).
- **Delete:** `DELETE /torrents/delete/{id}`. **No pause/resume endpoint** in the API.
- **Statuses:** `magnet_error`, `magnet_conversion`, `waiting_files_selection`, `queued`, `downloading`, `downloaded`, `error`, `virus`, `compressing`, `uploading`, `dead`.
- **Rate limits:** 250 requests/minute published; refused requests return 429 and count against the limit (brute-forcing can leave the account blocked for an undefined time).
- **Quirks:** links are **IP-locked** (the link only works from the IP that requested it — matters for Play: request the unrestrict on the phone, play on the phone). Since May 2026 RD refuses some releases by name (`451 infringing_file`) — DMM keeps a name blocklist and skips probing blocked names. No Usenet. No bandwidth cap; 100 active torrents (per awesome-debrid's current listing; older docs say 25 — plan limits change, verify at build time).

### 2.3 AllDebrid — `https://api.alldebrid.com`, v4 / v4.1

Official docs: https://docs.alldebrid.com/ (read in full 2026-11-15).

- **Auth:** API key, `Authorization: Bearer` header. Keys managed at `alldebrid.com/apikeys`. **PIN flow for in-app sign-in:** `GET /v4.1/pin/get` returns `{ pin, check, user_url, expires_in: 600 }` → user enters the PIN on alldebrid.com → poll `POST /v4/pin/check` until `activated: true`, which returns the `apikey`. As of 2025-01-15 the `agent`/`version` params are no longer required. Demo keys (`staticDemoApikeyPrem` etc.) return static demo data — useful for testing without an account.
- **List:** `POST /v4.1/magnet/status` with no `id` returns all magnets (filter by `status`: `active` / `ready` / `expired` / `error`). Live mode available (session id + counter, diff-only updates).
- **Add:** `POST /v4/magnet/upload` (form `magnets[]=` — magnet URIs *or bare v1 info hashes*, repeatable) and `POST /v4/magnet/upload/file` (multipart `files[]=` .torrent files). Response per magnet includes `ready: true/false` — **this flag is the cache signal** (there is no read-only probe: `/v4/magnet/instant` returns 404, v4 `/magnet/status` is discontinued). A miss must be deleted afterwards; AD dedupes re-uploads of the same hash, so a repeated check doesn't create a duplicate. Errors: `MAGNET_MUST_BE_PREMIUM` (magnets need Premium), `MAGNET_TOO_MANY_ACTIVE` (max 30 active magnets).
- **Status:** `POST /v4.1/magnet/status` (`id` optional). `statusCode`: 0 queued, 1 downloading, 2 compressing/moving, 3 uploading, **4 ready**, 5–15 error (5 upload fail, 7 not downloaded in 20 min, 8 file too big, 10 >72h, 15 no peer, etc.). Progress = `downloaded / size` (compute it — not provided).
- **Get link (two steps):** `POST /v4/magnet/files` (`id[]=`) returns the files as a **hierarchical tree** (`n` name, `s` size, `l` download link, `e` children) — it must be flattened. File links are locked `/f/` tokens, **not** delivery URLs. Then `POST /v4/link/unlock` (`link=`) returns the direct URL. Also `POST /v4/link/streaming` (transcoded stream links) and `POST /v4/link/delayed` (links that need waiting).
- **Delete/restart:** `POST /v4/magnet/delete`, `POST /v4/magnet/restart`. No pause.
- **Hoster links:** `POST /v4/link/infos`, `/v4/link/redirector`, `/v4/link/unlock` — hoster links go through the same unlock step. `GET /v4/hosts`, `/v4/hosts/domains` list supported hosters.
- **Rate limits:** **12 req/s, 600 req/min**; excess returns 429 or 503.
- **Quirks:** no Usenet. Free trial exists (7 days / 25 GB) but magnet features require Premium. Download links work from any IP, but datacenter IPs are blocked and VPN IPs need approval (up to 12 h).

### 2.4 Premiumize — `https://www.premiumize.me/api`

Official docs: https://www.premiumize.me/api (read in full 2026-11-15).

- **Auth:** API key **or** OAuth 2.0 access token — same `Authorization: Bearer` header either way (recommended; keeps the key out of logs/Referer). Legacy `?apikey=` query param and POST body still accepted. OAuth flows: Authorization Code with PKCE (recommended for mobile), Device Code (TV/CLI — `POST /token` with `response_type=device_code`, 10-min expiry, poll interval 5 s), plain Authorization Code, legacy password/implicit flows. Authorize at `https://www.premiumize.me/authorize`, token at `https://www.premiumize.me/token`, single scope `full`.
- **List:** `GET /api/transfer/list` (all transfers, newest first; statuses `queued`, `running`, `finished`, `seeding`, `error`; `progress` 0.0–1.0) and `GET /api/folder/list` (cloud contents, with breadcrumbs; folders + files).
- **Add:** `POST /api/transfer/create` (`src` = magnet URI, .torrent/.nzb URL, hoster link, or multipart file upload; `folder_id`, `password` optional). NZB URLs are accepted through the same `src` field (sparsely documented in PM's own docs, widely confirmed in community use — DMM and the PVTKRR brief both rely on it). `POST /api/transfer/directdl` (`src`) generates direct links **without storing anything in the cloud** — one call returns every file in the source with its link; instant for cached content.
- **Cache check:** `POST /api/cache/check` (`items[]=` links/magnets/hashes) — a real read-only check returning parallel arrays (`response[]` booleans, `filename[]`, `filesize[]`). Also `GET /api/services/list` tells you per service whether it supports `cache`, `directdl` or `queue`, with `fairusefactor` multipliers and URL regex patterns — useful for validating a shared link before adding.
- **Get link:** `directdl` content entries have `link` directly (no unrestrict step). Finished transfers live in the cloud: `folder/list` / item details give direct links. `POST /api/zip/generate` bundles files/folders into a .zip with a `location` URL.
- **Delete/retry:** `POST /api/transfer/delete` (`id`), `POST /api/transfer/retry` (re-queue a failed transfer), `POST /api/transfer/clearfinished`. No pause.
- **Account:** `GET /api/account/info` returns `premium_until`, `limit_used` (fair-use fraction 0–1), `booster_points`.
- **Points/fair use:** 1,000-point pool; roughly 1 point = 1 GiB downloaded, with per-service multipliers (`fairusefactor`, some hosters ×2–5). `account_limit_reached` is the error when points/active jobs are exhausted. 1 TB cloud storage.
- **Rate limits:** no published numeric limit; `rate_limit_reached` error exists — back off on it.
- **Quirks:** Usenet works via `transfer/create` with an NZB URL but is no longer advertised. Transcoding is retired (`transcode_status` is a dummy value now) — play source files directly. Error envelope is `{ status: "error", message, code }` with HTTP 200 for business errors.

### 2.5 Offcloud — `https://offcloud.com/api`

Official docs are thin: there is no full public reference comparable to the others. Base URL confirmed from DMM source (`OC_API_BASE = 'https://offcloud.com/api'`); behaviour confirmed from DMM's `offcloud.ts`, the [`offcloud-api` npm package README](https://app.unpkg.com/offcloud-api@1.0.2/files/README.md) and the [php-offcloud docs](https://www.wallogit.com/composer/type/3.library/patriotblog/171323.php-offcloud). Treat endpoint details as "verified against DMM + community clients", not against an official reference — flag for live verification at build time.

- **Auth:** API key from account settings (offcloud.com/#/account). DMM sends `Authorization: Bearer <key>`; legacy docs show `?apikey=` query param. All requests are POST, all responses JSON.
- **List:** `cloud/history` — the download history (the "list" view). `account/info` for the account.
- **Add:** `cloud` (POST a magnet / hoster URL / .torrent for cloud download); `instant` download is the "add a URL for instant downloading" path (instant = cached, returns a link immediately). `remote` download sends a URL to remote storage (Remote option id + folder id) — not needed for v1.
- **Cache check:** `cache` (POST with info hashes — `OC.instant.cache([hashes])` in the npm client; DMM's `checkOffcloudCache` posts hashes and reads `cachedItems`).
- **Get link:** `cloud/status` (poll by request id) then `cloud/explore/{requestId}` — lists the files inside the downloaded torrent/archive so the app can pick a file and get its link. DMM's flow is `cloud.history()` → `cloud.explore()` (or add via `cloud` first).
- **Delete:** `cloud/remove/{requestId}`.
- **Extras:** proxy list endpoint (Offcloud routes some downloads through proxies — `proxies()` in the PHP client).
- **Rate limits:** none published anywhere found — mark unknown.
- **Quirks:** no Usenet. Its core strength is hoster links (it started as a premium link generator). Archive exploration matters: many torrents arrive as archives, and `explore` is how you find the playable file inside.

### 2.6 Debrid-Link — `https://debrid-link.com/api/v2` (docs) / `https://debrid-link.fr/api/v2` (DMM)

Official docs: https://debrid-link.com/api_doc/v2 — the page is JS-heavy and rendered almost no content when fetched on 2026-11-15, so endpoints below come from the docs' search-indexed content, DMM's `debridLink.ts` (1,347 lines) and the [stremthru debridlink client](https://pkg.go.dev/github.com/MunifTanjim/stremthru/store/debridlink). Note the two domains: docs say `https://debrid-link.com/api/v2`; DMM uses `https://debrid-link.fr/api/v2`. Both belong to the same service; verify which base answers at build time.

- **Auth:** OAuth2 (spec RFC 6749): endpoint `https://debrid-link.com/api/oauth`, authorization at `https://debrid-link.com/oauth/auth`. Scopes are verb+service pairs, e.g. `get.post.delete.downloader`, `get.post.delete.seedbox`; default scope `get.post.downloader get.post.seedbox get.account`. DMM uses the OAuth device-code flow via its proxy. An API key also exists (account settings → API tab; sent as `access_token` query param) and is what Stremio addons use.
- **List:** `GET /seedbox/list` (torrents; a file is ready when `files[].downloadPercent == 100`) and `GET /downloader/list` (hoster links). `GET /seedbox/activity` for live activity.
- **Add:** `POST /seedbox/add` — **a torrent URL must be public; a bare hash is only added if it is already cached on DL's servers** (cached-only hash adds — same rule as DMM's README). Full magnets and .torrent files start a download. `POST /downloader/add` for hoster links.
- **Cache check:** **no read-only endpoint.** `CheckSeedboxTorrentsCached` is deprecated — "Debrid-Link removed the endpoint" (stremthru). DMM's `checkDebridLinkCache` probes by adding and removing again, with a probe budget, concurrency limit and hour-long lockout handling; a hit "lands in the probe account… and is removed again". The UI note in DMM says it plainly: "Each Debrid-Link hit is added and removed again, which is why it is a button."
- **Get link:** seedbox files already carry `downloadUrl` (e.g. `https://seed20.debrid.link/dl/...`); downloader links carry `downloadUrl` too. `POST /seedbox/zip` zips a torrent for one download.
- **Delete:** remove seedbox torrents / downloader links (DELETE endpoints; DMM: `deleteSeedboxTorrents`).
- **Statuses:** `0` paused, `1` queued, `2` verifying, `4` downloading, `8` seeding, `100` finished.
- **Limits:** 20 active torrents, 1 TB max torrent, 2,500 files per torrent, 50 uncached torrents/day (cached don't count), 400 GB/day. Seeds for 20 days. Download links work from any IP. Prices from €3/15 days.
- **⚠️ Current-status caveat (Nov 2026):** awesome-debrid notes "Its torrent page says the torrent service is currently unavailable (Nov 2026)". Verify the service is actually up before building this adapter — it may be the lowest-priority provider for that reason alone.
- **Quirks:** no Usenet. Multi-IP support (unlike RD's IP-locked links).

---

## 3. Capability matrix

Legend: ✅ supported · ◐ partial / with a catch (see note) · ❌ not supported · ? not confirmed in official docs.

| Capability | Real-Debrid | AllDebrid | TorBox | Premiumize | Offcloud | Debrid-Link |
|---|---|---|---|---|---|---|
| Add magnet | ✅ `POST /torrents/addMagnet` | ✅ `POST /v4/magnet/upload` | ✅ `POST /torrents/createtorrent` (+ async) | ✅ `POST /transfer/create` | ✅ `POST cloud` | ✅ `POST /seedbox/add` (magnet/.torrent URL) |
| Add .torrent file | ✅ `PUT /torrents/addTorrent` | ✅ `POST /v4/magnet/upload/file` | ✅ `createtorrent` (multipart) | ✅ `transfer/create` (multipart) | ✅ `cloud` | ✅ `seedbox/add` (file) |
| Add hoster link | ✅ `POST /unrestrict/link` | ✅ `POST /v4/link/unlock` | ✅ `POST /webdl/createwebdownload` | ✅ `transfer/create` / `directdl` | ✅ `cloud` / `instant` (core feature) | ✅ `POST /downloader/add` |
| Add NZB / Usenet | ❌ | ❌ | ✅ Pro plan only | ◐ via `transfer/create` with NZB URL (sparsely documented, community-confirmed) | ❌ | ❌ |
| Cache check (read-only) | ❌ `instantAvailability` disabled 2024-11-22; probe = add → delete | ◐ no read-only probe; upload's `ready` flag is the signal; miss must be deleted after | ✅ `checkcached` (GET/POST) | ✅ `POST /cache/check` | ✅ `POST cache` (hashes) | ❌ cache endpoint removed; probe = add → delete |
| List downloads | ✅ `GET /torrents` (paged) | ✅ `POST /v4.1/magnet/status` (no id = all) | ✅ `mylist` (torrents/webdl/usenet) | ✅ `GET /transfer/list` + `GET /folder/list` | ✅ `cloud/history` | ✅ `GET /seedbox/list` + `GET /downloader/list` |
| Delete | ✅ `DELETE /torrents/delete/{id}` | ✅ `POST /v4/magnet/delete` | ✅ `controltorrent` / delete endpoints | ✅ `POST /transfer/delete` | ✅ `cloud/remove/{id}` | ✅ remove seedbox/downloader |
| Pause / resume | ❌ no endpoint | ◐ `restart` only, no pause | ◐ `controltorrent` exists; operation values not documented in public spec — verify | ❌ `retry` only, no pause | ❌ | ❌ (status includes `paused`, but no pause/resume endpoint found) |
| Get stream / download link | ✅ two-step: `info.links[]` → `unrestrict/link` → `download` | ✅ two-step: `magnet/files` tree → `link/unlock` | ✅ `requestdl` or `stream/createstream` (presigned — use for Play) | ✅ `directdl` returns links directly | ✅ `cloud/explore/{id}` → file link | ✅ seedbox/downloader `downloadUrl` |
| File selection inside a torrent | ✅ `selectFiles` — required after every add | ❌ all files download; pick from `magnet/files` tree at link time | ◐ no selection API; pick file at `requestdl`/`createstream` time via `file_id` | ◐ pick file from `directdl` content list or cloud folder | ◐ `cloud/explore` lists files; pick one | ◐ per-file `downloadUrl` in seedbox files |
| Rate limit | 250 req/min published; adds ~30/min sustainable; unrestrict ≥5 s apart | 12 req/s, 600 req/min (429/503) | ~5 req/s general; `createtorrent` 50/hr; `createwebdownload` 25/hr | ? none published; `rate_limit_reached` error exists | ? none published | ? none published; flood lockout exists (DMM guards it) |
| Auth type | OAuth2 device flow (recommended); static API token also exists | API key (Bearer); PIN flow for in-app sign-in | API key (Bearer) | API key (Bearer) or OAuth2 (PKCE / device code) | API key (Bearer; legacy `?apikey=`) | OAuth2 device flow (DMM) or API key (`access_token`) |
| Free tier with API access | ❌ premium required | ◐ free trial 7d/25 GB, but magnets need Premium | ❌ free tier exists, no API access (Essential+ required) | ❌ premium required | ? free tier exists; API access on free tier not confirmed in official docs | ❌ premium only |
| Extra notes | Links IP-locked; `451` name filter since May 2026 | Max 30 active magnets; links work from any IP | RSS, integrations, user stats; Usenet Pro-only | Points system (1,000 pool); 1 TB cloud; transcoding retired | Archive exploration via `explore` | ⚠️ torrent service reported unavailable Nov 2026 — verify before building |

---

## 4. Unified interface for our Kotlin Android app

DMM unifies with a union type plus per-provider modules plus orchestration. For a native app, a single interface with capability flags is cleaner — the UI and the share flow program against the interface, and each provider is one adapter file. Nothing below is copied from DMM; it is written from the provider APIs in §2.

### 4.1 The interface (Kotlin-like pseudocode)

```kotlin
/** One connected debrid account. `service` is fixed; `credential` is the
 *  stored secret (API key, or OAuth access+refresh tokens) — see §4.4. */
data class DebridAccount(
    val id: String,                 // stable local id (UUID)
    val service: DebridService,     // RD | AD | TB | PM | OC | DL
    val label: String,              // user-visible name, e.g. "TorBox (Essential)"
    val credential: StoredCredential,
    val addedAtEpochMs: Long,
    val lastValidatedEpochMs: Long? // null until first successful user/me call
)

enum class DebridService { REAL_DEBRID, ALLDEBRID, TORBOX, PREMIUMIZE, OFFCLOUD, DEBRID_LINK }

data class DebridFile(
    val id: String,                 // provider-local file id (opaque to the UI)
    val name: String,
    val sizeBytes: Long,
    val path: String? = null       // slash-joined path inside the torrent, when known
)

data class DebridDownload(
    val id: String,                 // provider-local download id
    val service: DebridService,
    val name: String,
    val sizeBytes: Long?,
    val status: DownloadStatus,     // unified — see §4.2
    val progress: Float,            // 0.0..1.0; 0 when unknown, 1 when ready
    val files: List<DebridFile>,    // empty until the provider reports them
    val kind: DownloadKind,         // TORRENT | WEB | USENET
    val createdAtEpochMs: Long?,
    val errorMessage: String? = null
)

enum class DownloadKind { TORRENT, WEB, USENET }

/** Everything the UI ever asks a provider to do. One method per user intent,
 *  not one per endpoint — the adapter hides how many HTTP calls that takes
 *  (RD's getStreamUrl is two calls; PM's is one). */
interface DebridProvider {
    val service: DebridService
    val capabilities: ProviderCapabilities

    /** Cheap "who am I" check; also the first call after sign-in. */
    suspend fun validateAccount(): Result<AccountInfo>

    /** All downloads of every kind this provider holds, newest first. */
    suspend fun listDownloads(): Result<List<DebridDownload>>

    /** One download's fresh state (for polling progress). */
    suspend fun getDownload(id: String): Result<DebridDownload>

    /** Add a magnet / .torrent file / hoster link / NZB. The input is already
     *  classified by the share/add flow (magnet vs .torrent vs .nzb vs URL).
     *  Returns the created download; adapters that need a second step
     *  (RD selectFiles) do it inside this call. */
    suspend fun add(input: AddInput): Result<DebridDownload>

    /** Read-only "is this already cached?" — returns null when the provider
     *  has no read-only probe (RD, DL): the caller must then decide whether
     *  to run a mutating probe (add → delete) or skip the check. */
    suspend fun checkCached(hashes: List<String>): Result<Map<String, CachedInfo>?>

    /** Resolve a playable/downloadable URL for one file of a ready download.
     *  MUST return a presigned/link URL that does not expose the API key
     *  (TB: createstream; RD: unrestrict; AD: unlock). The UI hands this
     *  URL to Android's ACTION_VIEW chooser — any player may take it. */
    suspend fun getStreamUrl(downloadId: String, fileId: String): Result<StreamUrl>

    /** Pause/resume where supported; no-op Result.failure(Unsupported) otherwise. */
    suspend fun control(id: String, action: ControlAction): Result<Unit>

    suspend fun delete(id: String): Result<Unit>

    /** Hoster support for the share flow: "can this provider take this URL?" */
    suspend fun supportsUrl(url: String): Result<Boolean>
}

sealed interface AddInput {
    data class Magnet(val magnetUri: String) : AddInput
    data class TorrentFile(val bytes: ByteArray, val fileName: String) : AddInput
    data class HosterLink(val url: String) : AddInput
    data class Nzb(val bytes: ByteArray, val fileName: String) : AddInput
    data class NzbUrl(val url: String) : AddInput
}

data class CachedInfo(val ready: Boolean, val name: String?, val sizeBytes: Long?)
data class StreamUrl(val url: String, val expiresAtEpochMs: Long? = null)
data class AccountInfo(val username: String?, val isPremium: Boolean, val detail: String?)
enum class ControlAction { PAUSE, RESUME, RETRY }
```

### 4.2 Capability flags (per provider — the adapter declares what it can do)

```kotlin
data class ProviderCapabilities(
    val magnet: Boolean = true,
    val torrentFile: Boolean = true,
    val hosterLink: Boolean = true,
    val nzb: Boolean = false,
    /** True only when the provider has a READ-ONLY cache check. */
    val readOnlyCacheCheck: Boolean = false,
    /** True when a cache probe is possible at all (possibly mutating). */
    val cacheProbeMutating: Boolean = false,
    val fileSelection: Boolean = false,   // RD selectFiles
    val pauseResume: Boolean = false,
    val restart: Boolean = false,
    val streamingApi: Boolean = false,    // first-class stream endpoint (TB createstream)
    val multiFilePick: Boolean = false,   // choose one file of many at Play time
    val maxActiveDownloads: Int? = null,
    val authFlow: AuthFlow
)

enum class AuthFlow { API_KEY, OAUTH_DEVICE, API_KEY_OR_OAUTH_DEVICE }
```

| | RD | AD | TB | PM | OC | DL |
|---|---|---|---|---|---|---|
| `nzb` | false | false | true (Pro) | true | false | false |
| `readOnlyCacheCheck` | false | false | true | true | true | false |
| `cacheProbeMutating` | true | true (upload) | — | — | — | true |
| `fileSelection` | true | false | false | false | false | false |
| `pauseResume` | false | false | ? (verify) | false | false | false |
| `restart` | false | true | false | true (`retry`) | false | false |
| `streamingApi` | false | false (`link/streaming` exists — transcoded) | true | false | false | false |
| `authFlow` | OAUTH_DEVICE | API_KEY | API_KEY | API_KEY_OR_OAUTH_DEVICE | API_KEY | API_KEY_OR_OAUTH_DEVICE |

### 4.3 Unified status enum + per-provider mappings

```kotlin
enum class DownloadStatus { QUEUED, DOWNLOADING, READY, PAUSED, ERROR, UNKNOWN }
```

| Unified | Real-Debrid (`status`) | AllDebrid (`statusCode`) | TorBox (`download_state` / `state`*) | Premiumize (`status`) | Offcloud (`status`) | Debrid-Link (`status`) |
|---|---|---|---|---|---|---|
| QUEUED | `queued`, `magnet_conversion`, `waiting_files_selection` | 0 Processing | `queued` / `downloading (0%)`† | `queued` | `queued` | 1 queued |
| DOWNLOADING | `downloading`, `compressing`, `uploading` | 1 Downloading, 2 Compressing/Moving, 3 Uploading | `downloading` | `running` | `downloading` | 2 verifying, 4 downloading |
| READY | `downloaded` | 4 Ready | `cached` / `completed`† | `finished`, `seeding` | `downloaded` | 100 finished (files `downloadPercent == 100`) |
| PAUSED | — | — | — | — | — | 0 paused |
| ERROR | `magnet_error`, `error`, `virus`, `dead` | 5–15 | error states† | `error` | error states | error states |

\* TorBox's list responses use `download_state`/`state` strings that vary by endpoint; the exact vocabulary must be pinned against live `mylist` responses at build time.
† Same for TorBox progress: the unified `progress` comes from the provider's own progress field where present (AD: `downloaded/size`; PM: `progress` 0–1; RD: `progress` 0–100; TB: provider-specific).

Error model: every provider call returns `Result<T>`; failures are typed, never bare strings:

```kotlin
sealed class DebridError(message: String) : Exception(message) {
    class AuthInvalid : DebridError("Key/token rejected — re-authenticate")
    class RateLimited(val retryAfterSecs: Long?) : DebridError("Slow down")
    class NotCached : DebridError("Not on this provider's servers")
    class QuotaExceeded(val detail: String) : DebridError("Plan limit hit")
    class UnsupportedCapability(val what: String) : DebridError("This provider can't do that")
    class NameRefused(val detail: String) : DebridError("Provider refused this release name") // RD 451
    class Network(val cause: Throwable) : DebridError("Network failed")
    class Provider(val code: String, val detail: String) : DebridError("Provider error $code")
}
```

The UI maps these to exactly one line each ("Key rejected — sign in again", "Too many requests — trying again in 30s", …). A `RateLimited` must trigger exponential back-off with jitter in the adapter, never a tight retry loop — RD and TB both punish bursts.

### 4.4 Credential storage

- **On-device only, encrypted.** Android Keystore-backed `EncryptedSharedPreferences` (or Tink): one entry per account, keyed by account id. Keys/tokens never leave the device except in an `Authorization` header to that provider's own API. The app never logs them, never puts them in a shared link, never backs them up to the cloud.
- **OAuth tokens (RD, DL, optionally PM):** store access + refresh token + expiry; refresh silently on 401; if refresh fails, mark the account "needs sign-in" and show the device-code flow again. Device-code flow is the right onboarding for RD/DL: show the code + URL (and QR) in the app, user approves in their browser, app polls for the token. No password is ever typed into our app.
- **API keys (TB, AD, PM, OC, DL fallback):** pasted once by the user in Settings → per-provider account row; masked by default with an eye icon (same rule as the launcher's API-key fields); a "Test" button calls `validateAccount()` before saving.
- **Play safety:** the stream URL handed to the player chooser must be a presigned/tokenised link (TB `createstream`, RD `unrestrict`, AD `unlock`). Never hand a raw `requestdl` URL (TorBox embeds the API key in it) or a key-bearing URL to any external player.

### 4.5 Build order

1. **TorBox** — the user has it (Essential, API access confirmed), the API is the best-documented of the six (full OpenAPI spec), and it exercises every shape the interface needs (torrents + web + Usenet + streaming + async adds). It is the reference adapter: get the interface right against TB first.
2. **Real-Debrid** — largest user base of any debrid service, and the hardest quirks (device-flow OAuth, mandatory `selectFiles`, two-step unrestrict, no cache check, IP-locked links, name filter, add pacing). Building it second forces the interface to handle the worst case early, while only two adapters exist to refactor.
3. **AllDebrid** — straightforward after RD (same two-step shape, PIN flow is easy, `ready` flag pattern is simple), and its 30-active-magnet limit exercises the quota error path.
4. **Premiumize** — the `directdl` one-call shape and the points/fair-use system exercise different UI (quota display, "cached → instant" Play without adding). Also the first provider with a real cloud-folder model.
5. **Offcloud** — smallest API surface; mainly hoster links + `explore`. Its thin official docs mean the adapter needs live verification.
6. **Debrid-Link** — last: smallest user base of the six, OAuth device flow like RD (so the flow is already built), and its torrent service was reported unavailable in Nov 2026 — verify it is actually up before spending time on it.

---

## 5. Open questions

1. **One active provider or all-at-once?** DMM checks every connected service and shows per-service availability. Our app can do that too, but it means a cache-check sweep hits every provider on every search/share. Simpler v1: one active provider (switcher in the header) + the option to check others on demand. Which does the user want?
2. **Mutating cache probes (RD, DL, AD):** for RD/DL the probe adds and deletes a torrent on the user's account; for AD the upload leaves a magnet behind unless deleted. Do we probe automatically on share (fast, but touches the account), or only when the user taps "check"? DMM's answer: automatic for TB/PM/OC, manual button for RD/AD/DL. Recommend the same.
3. **RD add pacing:** DMM sustains ~30 adds/min and spaces bulk adds 60 s apart. Our share flow adds one item at a time, so this mostly matters for bulk/hash-list adds — decide the pacing policy when (if) bulk add is built.
4. **TorBox `controltorrent` operations:** the public spec doesn't document the operation values — pause/resume needs a live check before we promise it in the UI.
5. **Offcloud endpoint verification:** no full official reference exists; the adapter's endpoint paths must be verified live against an Offcloud account before release.
6. **Debrid-Link service status:** torrent service reported unavailable Nov 2026 — confirm it's back before building the adapter; if not, ship five providers.
7. **Naming:** the app is no longer "the TorBox app". It needs a neutral name (the user picks this — don't decide for them in the spec).
8. **put.io:** DMM also supports it (device-link OAuth, no cache check, size check against free space first). Out of scope unless the user asks — note it as a possible seventh provider.
9. **Rate-limit budgets:** each adapter needs its own throttle (RD 250/min with add pacing, AD 12/s, TB ~5/s, PM/OC/DL unknown). A shared token-bucket per provider in the networking layer is the clean way to enforce this.

## 6. What this means for the mockup (2–3 lines)

The Downloads screen needs a **provider switcher** — DMM's model is "all connected services, per-service availability shown", while the simpler model is one active provider at a time. Either way the header gains a provider control (and Settings gains per-provider account rows with key/token status), and the Play button stays provider-agnostic: it hands a stream link to Android's player chooser. The mockup itself was not edited in this task.

---

## Sources

- DMM repo (cloned 2026-11-15, HEAD `e743a1eb`): https://github.com/debridmediamanager/debrid-media-manager
- DMM README: https://github.com/debridmediamanager/debrid-media-manager/blob/main/README.md
- DMM `cast-alldebrid.md` (measured RD/AD behaviour, corrections 2026-11-05): https://github.com/debridmediamanager/debrid-media-manager/blob/HEAD/cast-alldebrid.md
- DMM `src/hooks/useAvailabilityCheck.ts`, `src/components/CheckAvailabilityButtons.tsx`, `src/services/{realDebrid,allDebrid,torbox,premiumize,offcloud,debridLink}.ts` (in the local clone)
- zurg: https://github.com/debridmediamanager/zurg-public and https://zurg.debridmediamanager.com/
- awesome-debrid (service limits/prices, Nov 2026): https://github.com/debridmediamanager/awesome-debrid
- TorBox OpenAPI spec — source https://api.torbox.app/openapi.json, docs https://api.torbox.app/docs
- Real-Debrid API base https://api.real-debrid.com/rest/1.0/ (official docs page returned 403 on 2026-11-15); cross-checked via https://github.com/gavanduffy/Ferrite/blob/HEAD/real-debrid-torbox-api.md and https://git.tbdh.app/Libraries/debrid-ts
- AllDebrid API v4 docs: https://docs.alldebrid.com/
- Premiumize API docs: https://www.premiumize.me/api
- Offcloud API base https://offcloud.com/api (per DMM source); client reference https://app.unpkg.com/offcloud-api@1.0.2/files/README.md
- Debrid-Link API docs: https://debrid-link.com/api_doc/v2 (JS-heavy, little content rendered); client reference https://pkg.go.dev/github.com/MunifTanjim/stremthru/store/debridlink
- PVTKRR Codex brief (RD/AD/PM verified live 2026-05-23): https://github.com/kepners/pvtkrrx/blob/HEAD/docs/CODEX_BRIEF_V1.3_DEBRID.md
