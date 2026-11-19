# Providers as a glanceable visual list

## What the user asked for

The Providers settings group is currently a long scrolling page: a
compact list, then all six full sign-in sections inline (each with a
large header, credential field, Test & Save button, help lines, and
sign-out). Redesign it as a glanceable visual list: one row/card per
provider with a simple distinctive picture tile and signed-in status
visible at a glance; tapping a provider opens its sign-in/details.

## Verified current state (0.1.24, from code)

`SettingsScreen.kt` (`ProvidersContent`):

- `ProviderList` already exists at the top: six rows (TorBox,
  Real-Debrid, AllDebrid, Premiumize, Offcloud, Debrid-Link) with name,
  signed-in/not-signed-in status, account label, an "Active" marker, and
  a radio button. Tapping a signed-in row selects it as the active
  provider (`SettingsViewModel.setActiveProvider`).
- Below it, all six `ProviderSection`s render inline, each with a
  `primaryContainer` header, credential text field, Test & Save button,
  help lines, and (when signed in) a sign-out button. That is the long
  scroll.
- `ProviderSection` is one shared composable driven by a
  `ProviderSectionDescriptor` per service; the sign-in flow
  (`testAndSave`), credential masking, feedback, and sign-out are
  identical for all six providers.

## Decisions for review

1. **List only, details on demand.** The Providers destination shows
   only the visual list. Tapping a row opens that provider's detail as
   a sub-screen (the existing `ProviderSection`, unchanged), with a back
   arrow returning to the list — the same destination pattern the six
   Settings groups already use. The selected provider is kept in
   `rememberSaveable` state, so rotation and system Back behave as the
   group navigation does.
2. **Tile in Trove's own visual language.** Each row leads with a 48dp
   rounded tile carrying the provider's initial letter (T, R, A, P, O, D
   — all distinct) in SpaceGrotesk, on a tonal container color. No
   provider trademarked logo files are scraped or embedded. The active
   provider's tile uses the primary container; the others use the
   standard surface container.
3. **Status at a glance.** Each row shows the provider name, a status
   line ("Signed in" + account label, or "Not signed in"), and a
   trailing "Active" label when it is the active service, plus a
   chevron indicating tap-to-open. No radio buttons in the list.
4. **Instant switching is preserved, moved into the detail screen.**
   The detail screen gains a "Set active" action (visible when signed
   in and not active) calling the existing `setActiveProvider`, and an
   "Active provider" state when it is active. The switching itself —
   one call, account-change refresh of Downloads — is unchanged; only
   the gesture moves from tapping a list row to tapping a button in
   the detail screen. Sign-in, Test & Save, masking, feedback, and
   sign-out are untouched (same `ProviderSection` composable).
5. **No new dependencies, no behavior changes** beyond the presentation
   and the switching gesture above. All six providers, the coming-soon
   block, and the existing strings are reused.

## Impeccable review (design lens)

- **distill** — the page held six full forms at once; the list shows
  only what is needed to choose a provider, details on demand.
- **layout** — one row per provider, one shared axis: tile, name +
  status, trailing state. Related items close (tile + name), separate
  providers divided by list spacing.
- **typeset** — name in title size, status in body size, tile initial
  in the SpaceGrotesk display font; no new font.
- **clarify** — "Signed in / Not signed in / Active" is plain language
  at a glance; the chevron says the row opens.
- **harden** — a provider with no key shows "Not signed in" and its
  detail shows the sign-in form, as today; errors and feedback are
  unchanged.
- **audit** — rows meet the 48dp touch target (ListItem height);
  theme tokens only; TalkBack reads name + status + action.
- **polish** — no new decoration: one tile, one name, one status line.

## Out of scope

- No changes to provider APIs, sign-in flows, or credential storage.
- No provider logos or brand colors.
- No changes to the other five Settings groups.
