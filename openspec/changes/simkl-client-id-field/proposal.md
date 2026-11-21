# SIMKL client ID field in Settings

## What the user asked for

"Just add the field, I'll use my own ID" — the user will register
their own SIMKL app and paste its public client ID into Trove.
No bitmere-baked ID, no bitmere app registration.

## Verified current state

All line numbers are main @ `d849db26` (Trove 0.1.34).

- `SIMKL_CLIENT_ID` is a compile-time placeholder constant
  (`core/.../SimklClient.kt:23`,
  `"SIMKL_CLIENT_ID_NOT_REGISTERED"`); `SimklClient.isConfigured`
  is false while it is the placeholder, sign-in is unavailable,
  and syncs are skipped with zero network calls. Settings shows
  the not-registered note (`SettingsScreen.kt:427`).
- SIMKL OAuth tokens live encrypted in the credential store under
  `simkl-1` (`ProviderAccountRegistry.SIMKL_ACCOUNT_ID`); the
  client is built once in `DebridRepository` (`:269`) with the
  constant as its `clientId`.
- The TMDB key field IS in Settings (`SettingsScreen.kt:345-366`,
  masked, validated live before saving) — the pattern to follow,
  except the SIMKL client ID is public and is shown in plain text.
- Matrix does exactly this (`bitmere/matrix-launcher @ 605fd6ed`,
  `SimklClient.java`): Settings → Accounts → SIMKL →
  "Public Client ID", stored in SharedPreferences `"simkl_v2"`
  under key `"client_id"`; changing it wipes tokens + cache;
  sign-out keeps it. Matrix is MIT and the user's own code; Trove
  reimplements the behaviour fresh, and stores the ID encrypted
  (stricter than Matrix's plain text).

## The gap

There is no way to enter a client ID in the app at all, so SIMKL
sign-in stays unavailable until a release bakes in a registered
ID — which the user explicitly does not want.

## Change

- A "Public Client ID" text field in the Settings SIMKL group,
  always visible, with a hint pointing at
  simkl.com/settings/developer. Plain text: the ID is public.
- The saved value is stored encrypted in the credential store
  (same store as the TorBox key and the SIMKL tokens).
- A saved value overrides the compile-time placeholder; an empty
  field keeps the existing placeholder behaviour (not configured,
  not-registered note). Saving a value makes sign-in available
  immediately, with no rebuild of the client.
- Changing the ID wipes SIMKL tokens + the disk snapshot (the
  tokens were minted under the old ID); re-saving the same ID is
  a no-op and keeps them. Sign-out keeps the ID.
- No live validation at save time: there is no endpoint that
  validates a client ID without starting a device flow, and the
  flow itself reports an invalid client at sign-in.

## Seam

- `SimklClient` takes its client ID as a resolver,
  `clientId: () -> String = { SIMKL_CLIENT_ID }`, read on every
  call — the same shape as the TMDB resolver's key supplier
  (`ProviderAccountRegistry.tmdbResolver`). `isConfigured` compares
  the resolved value against the placeholder.
- `ProviderAccountRegistry` owns the new credential-store entry
  (`simkl-client-id-1`) with `simklClientId()` /
  `saveSimklClientId()` / `deleteSimklClientId()`.
- `DebridRepository` wires the resolver
  (`{ registry.simklClientId() ?: SIMKL_CLIENT_ID }`) and owns the
  wipe-on-change in `saveSimklClientId()` /
  `clearSimklClientId()` (best-effort revoke first, then delete
  tokens + clear the engine snapshot).
- `SettingsViewModel` mirrors the TMDB save/clear flow (field,
  saving flag, saved/error feedback, `refresh()` after save).
