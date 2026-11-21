# Tasks

- [ ] Client ID resolver in core (S, low): `SimklClient` takes
  `clientId: () -> String = { SIMKL_CLIENT_ID }` and reads it on
  every call; `isConfigured` compares the resolved value against
  the placeholder. Update the class KDoc. Update the two core
  test helpers (`SimklClientTest`, `SimklSyncEngineTest`) to pass
  a lambda.
- [ ] Encrypted storage (XS, low): in `ProviderAccountRegistry`,
  add `SIMKL_CLIENT_ID_ACCOUNT_ID = "simkl-client-id-1"` plus
  `simklClientId()` / `saveSimklClientId()` /
  `deleteSimklClientId()` on the credential store. Blank values
  read as absent; saving a blank value is rejected.
- [ ] Repository wiring + wipe-on-change (S, low):
  `DebridRepository` passes
  `{ registry.simklClientId() ?: SIMKL_CLIENT_ID }` to the
  client, and adds `simklClientId()` / `saveSimklClientId()` /
  `clearSimklClientId()` to `TroveRepository` (with defaults).
  Saving a changed ID revokes best-effort, then deletes tokens
  and clears the engine snapshot; re-saving the same ID is a
  no-op. Clearing does the same wipe. Sign-out keeps the ID.
- [ ] Settings UI (S, low): "Public Client ID" field at the top
  of the SIMKL group, always visible, plain text, test tag
  `simkl_client_id_field`; saved ID shown in plain text above
  it; Save + Clear buttons; saved/error feedback; hint pointing
  at simkl.com/settings/developer. New strings; the
  not-registered note now points at the field. `SettingsViewModel`
  mirrors the TMDB save/clear flow and refreshes after save.
- [ ] Tests (S, low): core — a changed resolver value flips
  `isConfigured` and the device-flow request carries the new ID.
  Repository (`SimklClientIdTest`, real `DebridRepository` +
  in-memory credential store + fake transport + temp disk cache)
  — empty means not configured; saving configures and the device
  flow uses the saved ID; changing the ID wipes tokens + disk
  cache; re-saving the same ID keeps them; clearing unconfigures
  and wipes; sign-out keeps the ID. ViewModel
  (`SimklSettingsTest`) — save configures and clears the field;
  empty save is rejected without a repository call; clear
  unconfigures; changing the ID signs the user out in state.
  `FakeRepository` models save/clear with the wipe behaviour.
- [ ] Gates (XS, low): full test suite 0 failures, lint 0 errors,
  detector 0 findings, `git diff --check` clean.
