# Providers

Trove is a multi-provider debrid library, modelled on DMM (Debrid
Media Manager, open-source AGPL-3.0 — study the architecture, write
all code fresh). One Downloads UI checks the connected services; each
service is one adapter behind the `DebridProvider` interface in
:core.

## Build order

TorBox, then Real-Debrid, AllDebrid, Premiumize, Offcloud,
Debrid-Link.

## What each provider can and cannot do

Only TorBox is shipped. Other entries are dated 2026-11-15 research
for possible adapters, not current app capabilities; reverify at implementation.

- **TorBox** (reference, shipped in v1): best-documented API, real
  `checkcached`, synchronous torrent creation and async web/Usenet creates.
  Play resolves per-file `requestdl` for the selected kind and real file ID,
  then uses Android's ACTION_VIEW player chooser with a concrete video MIME
  derived from the filename. The bearer link goes only to the chosen player,
  never logs, share targets or error messages. `createstream`/`getstreamdata`
  are plan-restricted on Essential and unused. Local rate budgets are ~5/s,
  50 torrent creates/hour and 25 web creates/hour.
- **Real-Debrid**: no read-only cache check (instantAvailability
  disabled since 2024-11-22). The probe is add-then-delete, ~30
  adds/min, bursts trigger 451 pauses. `selectFiles` mandatory; links
  are IP-locked.
- **AllDebrid**: no read-only probe either; the upload ready flag is
  the signal, and a miss must be deleted.
- **Premiumize**: transfer/directdl gives links in one call, real
  cache/check, points system.
- **Offcloud**: thinnest documentation — needs live verification at
  build time.
- **Debrid-Link**: torrent service reported unavailable Nov 2026 —
  verify before building it.

## TorBox API

2026-11-15 research snapshot; not independently rechecked by T2.

Still v1 — OpenAPI 3.1.0, info version 1.0.0, base
`https://api.torbox.app`. No v2 exists (checked live 2026-11-15).
Research referenced a workspace-only `torbox-openapi.json` (not bundled here). Auth is an API
key from account settings. Latest changelog v9.3.1 (2026-11-09).
