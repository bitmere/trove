# simkl-client-id-field

## The field

- The Settings SIMKL group always shows a "Public Client ID"
  text field at the top, in plain text (the client ID is public,
  not a secret), with a hint pointing at
  simkl.com/settings/developer.
- The saved ID is shown in plain text above the field. The field
  itself starts empty; typing a new ID and saving replaces it.
- Save is disabled while a blank field, a sign-in, or a sync is
  in flight. Clear is shown only while an ID is saved.

## Precedence

- A saved client ID overrides the compile-time placeholder
  constant; the client resolves it on every call, so saving
  takes effect without rebuilding anything.
- With no saved ID, behaviour is exactly today's placeholder
  behaviour: not configured, sign-in unavailable, the
  not-registered note shown, syncs skipped with zero network
  calls.

## Changing the ID

- Saving a different ID wipes the SIMKL OAuth tokens and the
  disk snapshot (both were minted/fetched under the old ID).
  The user signs in again under the new ID.
- Re-saving the same ID is a no-op: tokens and the snapshot
  are kept.
- Clearing the ID wipes tokens and the snapshot and returns
  the app to the not-configured state.
- Signing out keeps the saved ID.

## Storage

- The ID is stored encrypted in the credential store, under its
  own id, like the TorBox key and the SIMKL tokens. It is never
  logged, backed up, or shipped.
