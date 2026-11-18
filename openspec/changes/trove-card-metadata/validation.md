# Investigation and decision (before implementation)

2026-11-17. DownloadCover currently renders downloadFormat: one extension,
MIXED or FILE. DebridDownload and TorBoxProvider.toDownload contain names,
files, status and transfer fields, but no IMDb identifier, poster or media title.
The supplied torbox-openapi.json mylist routes (torrents/webdl/usenet) have
unspecified success schemas: they do not establish any media metadata fields.
Do not assume an undocumented IMDb ID exists or change provider parsing.

Choose Cinemeta: public HTTPS Stremio catalog/meta protocol, no API key or
new Android permission (INTERNET already exists). Search:
https://v3-cinemeta.strem.io/catalog/{movie|series}/top/search={encoded-title}.json
Detail: https://v3-cinemeta.strem.io/meta/{movie|series}/{imdb-id}.json
Search returns metas; detail returns meta with name, poster, releaseInfo/year,
type and id. Use exact normalized title and year where provided; ambiguous,
missing, failed or mismatched results retain local parsed title/year.

Sources checked via web on 2026-11-17:
- https://v3-cinemeta.strem.io/manifest.json declares catalog/meta, movie/series,
  top catalogs with search, IMDb tt IDs, no configuration/authentication.
- https://raw.githubusercontent.com/Stremio/stremio-addon-sdk/master/docs/api/requests/defineCatalogHandler.md
- https://raw.githubusercontent.com/Stremio/stremio-addon-sdk/master/docs/api/requests/defineMetaHandler.md
- https://github.com/Stremio/stremio-core/blob/development/src/constants.rs
- https://github.com/NuvioMedia/NuvioMobile : Nuvio is a client application
  using addons, not a public filename resolver. Its issue 1491 documents
  Cinemeta metadata use: https://github.com/NuvioMedia/NuvioMobile/issues/1491
- https://github.com/Viren070/AIOStreams/wiki/API-Documentation : aggregates
  streams for media IDs (including IMDb); configured search also exists.
  Neither product exposes a drop-in unauthenticated filename resolver or a
  portable card UI template. Adopt poster/title/year/type presentation only.
- https://developer.themoviedb.org/docs/authentication-application : requires
  an API credential; rejected for this feature.

Live manifest fetched without credentials. Direct search/meta probes were
blocked by this environment's network restrictions; protocol verified from
sources, not a claimed live end-to-end success. Runtime verification remains.

Architecture: Android-free parser and Cinemeta resolver on existing HttpTransport;
repository-owned bounded cache including misses, serialized requests; metadata
hydration after list display in ViewModel, no blocking list refresh or composition.
Only parsed title is sent to Cinemeta; no TorBox key, raw URL or bearer link.
Poster loading uses existing HTTP transport and bounded sampled Android bitmap
cache off Main, with a small concurrency limit; no new dependencies.

Design tokens: retain theme primary/secondary/tertiaryContainer and their on-colors,
existing titleMedium/bodySmall/labelLarge typography and TroveSpace spacing.
One layout: compact portrait beside title/year/type, kind/status below the title.
Signature: actual media poster, with quiet placeholder on failure. No new gradients,
blur, intrinsic measurements, typography system or decorative badges.

## Verification status
Implementation and synthetic tests pending. No build, test, lint or phone pass
claimed. Show diff before compiling per AGENTS.md. No version/signing/workflow changes.
