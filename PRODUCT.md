# Trove — product context

## Audience

People managing debrid downloads on their Android phone — TorBox,
Real-Debrid, AllDebrid, Premiumize, Offcloud and Debrid-Link — including
older and low-memory phones.

## Purpose

A fast, small-footprint debrid library: one primary destination,
Downloads, with Torrents, Web and Usenet subtabs; search at the top of
the list; add via clipboard magnet or link; play through the Android
player chooser. Settings behind the avatar, no bottom navigation.

## Constraints

- Phone-first, touch navigation. No D-pad assumptions, no TV UI.
- Small footprint. Must run on older phones (minSdk 26) and low-memory
  devices; bounded snapshot blur only on API 31+ outside low-RAM
  devices, scrim fallback otherwise.
- Package `app.trove.debrid`, store title "Trove – Debrid Library".
  License undecided — no LICENSE file yet.
- Distributed through GitHub Releases only, no mirrors. The in-app
  updater reads the Releases API.
- Provider credentials are the user's own, entered on-device, encrypted
  in Keystore, never in code, logs or backups.
- Playback hands a per-file `requestdl` link to the Android ACTION_VIEW
  chooser; the bearer link goes only to the selected player, never to
  logs, share targets or error messages.
- No functionality is removed to save space.
