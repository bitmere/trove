# Multi-provider feature adoption — requirements for the recommended set

Scope: only the items the proposal recommends adopting. The A1–A5
provider adapters are covered by the existing multi-provider roadmap
(`docs/PROVIDERS.md`); this spec covers what is new in this change.
Each requirement applies only if the user approves its item.

## Multi-provider behaviour (A6, A7)

- The Add sheet SHALL, when two or more providers are signed in, check
  each provider that can report cache state and offer the one that
  already has the torrent, with the user able to choose another.
- Settings SHALL list every provider with its sign-in state, and
  switching the active provider SHALL be instant with no re-entry of
  credentials.
- Each provider's credential SHALL be stored separately in the
  Keystore-encrypted store and SHALL be sent only to that provider.
- The active provider SHALL be persisted. Switching SHALL require a
  stored credential. When the selected provider signs out, the library
  SHALL fall back to the other signed-in provider when available.
- Settings SHALL accept and validate an AllDebrid API key and select
  AllDebrid after saving it. When AllDebrid is active, Add SHALL validate
  hoster links using the adapter and direct users to Settings if their
  key is rejected.
- Real-Debrid web downloads with a remote ID SHALL be looked up in
  download history for details and playback. Playback SHALL request
  a fresh link from the original hoster URL when available, or use
  the validated generated URL from the history entry.

## Library (B1, B3, B4, B5)

- A download card SHALL show the parsed episode token and quality
  alongside the title and year (for example "The Bear · S02E05 ·
  1080p") when the filename carries them; titles that carry neither
  SHALL look exactly as they do today.
- The details sheet SHALL hide release-group clutter files (`.nfo`,
  sample clips, cover images, "Downloaded from" text files) by
  default, and Settings SHALL offer a toggle that brings them back.
- Long-pressing a download SHALL enter a selection mode in which
  several downloads can be selected and deleted together with one
  confirmation; the existing single-item delete SHALL keep working.
- Settings SHALL offer an exclude-words list (CAM and TS by default)
  and a prefer-words list (x265, REMUX, and user-added words).
  Excluded releases SHALL NOT appear in the library, and preferred
  words SHALL rank a release higher in the library order.

## Convenience (E1, E2, E4, E5)

- When the app opens with a magnet link or `.torrent` link on the
  clipboard, it SHALL offer once to add it, and SHALL NOT offer the
  same copied link again. Settings SHALL offer a switch to turn the
  offer off. The switch defaults to on. Trove checks the clipboard
  only when foregrounded and the switch is enabled. Android 12+ may
  show a system toast when Trove reads the clipboard. This is expected
  OS behaviour. Accepting opens Add with the copied text filled in.
  Trove remembers the offer across restarts even if it is dismissed.
- The launcher SHALL offer a static "Add" shortcut that opens the Add
  sheet.
- Settings SHALL offer backup and restore of Trove settings. Sign-ins
  SHALL be included only if the user opts in, and then only encrypted
  with a password the user chooses (PBKDF2 + AES-256-GCM). A restore
  SHALL NOT overwrite the stored credentials unless the backup
  contains them and the password verifies.
  The version 1 JSON envelope holds settings and an optional Base64
  sign-in payload encrypted with AES-256-GCM (random 12-byte IV and
  128-bit tag). PBKDF2-HMAC-SHA256 derives the key with 210,000 iterations
  and a random 16-byte salt. Readers ignore unknown fields within the
  envelope version. Restore validates the envelope and authenticates
  any included sign-in before writing settings or credentials.
- Settings SHALL offer a pure-black OLED theme in addition to the
  existing light and dark themes, and a small set of accent colour
  themes. The chosen theme SHALL persist across restarts and SHALL
  apply at normal and large font sizes.
  Appearance offers System, Light, Dark, and OLED black. Accent options
  are Jade, Ocean, Ember, and Rose. Both values are included in backups.
  Older backups restore System and Jade.

## Non-goals (this change)

- No in-app player, no continue-watching, no subtitles handling, no
  DLNA cast (C1–C6).
- No download-to-device engine, no direct-link downloads, no
  whole-torrent zip (D1–D3).
- No follow-a-show auto-download, no Plex/Jellyfin layout (B7, B8).
- No new languages, no TV/tablet layouts, no built-in browser, no
  Trakt sync, no auto-remove of torrents (E6–E10, B7).
- No new dependency. No code copied from any other app: every adopted
  feature is implemented fresh.
