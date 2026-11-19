# Providers visual list

## List

- The Providers destination shows one row per provider (TorBox,
  Real-Debrid, AllDebrid, Premiumize, Offcloud, Debrid-Link), in the
  existing provider order, plus the coming-soon block when non-empty.
- Each row: a 48dp rounded tile with the provider's initial letter
  (T, R, A, P, O, D) in SpaceGrotesk on a tonal container color; the
  provider name; a status line ("Signed in" with the account label, or
  "Not signed in"); a trailing "Active" label when it is the active
  service; a chevron indicating the row opens. The active provider's
  tile uses the primary container; other tiles use the standard surface
  container. No trademarked logos or brand colors.
- Rows meet the 48dp touch target. TalkBack reads the provider name,
  its status, and that it opens details.

## Detail

- Tapping a row opens that provider's detail as a sub-screen, showing
  the existing `ProviderSection` unchanged: header, credential field
  with show/hide, Test & Save, help lines, feedback, and sign-out.
- The detail screen adds a "Set active" action when the provider is
  signed in and not active; when it is active, it shows the "Active
  provider" state instead. Both call the existing ViewModel methods.
- Back (toolbar arrow or system Back) returns to the list; the list
  selection survives rotation.

## Behavior preserved

- Sign-in flow, credential masking, Test & Save, feedback, sign-out,
  and active switching (`setActiveProvider` + account-change refresh
  of Downloads) are unchanged. Only the presentation changes and the
  switching gesture moves from the list radio to the detail action.
