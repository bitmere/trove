## ADDED Requirements

### Requirement: Grouped Settings sub-screens
Settings SHALL present a root list of six groups — Providers, Library,
Playback, Appearance, Updates, Backup — instead of one long scrolling
page. Tapping a group SHALL open its sub-screen; each sub-screen SHALL
have a back arrow returning to the root list, and the system back button
SHALL return to the root list before leaving Settings. The existing
NavHost destination and `onBack` contract SHALL be unchanged.

#### Scenario: Finding a setting
- **Given** Settings is open
- **When** the user looks for the default player
- **Then** the root list shows six groups and Playback opens a screen containing only the default player setting.

#### Scenario: System back from a sub-screen
- **Given** a Settings sub-screen is open
- **When** the user presses system back
- **Then** the root list is shown, and a second back press leaves Settings.

### Requirement: Every setting preserved in its group
Every setting on the old page SHALL remain reachable with the same
behaviour in its group, with no setting added, removed or reworded in
behaviour:
- Providers: provider list with active selection, TorBox key sign-in, Real-Debrid, AllDebrid, Premiumize, Offcloud and Debrid-Link sign-in sections, and the coming-soon provider list.
- Library: show clutter files, clipboard offers, and release preferences (exclude and prefer words).
- Playback: default player selection, including the player picker dialog.
- Appearance: theme mode and accent.
- Updates: app updates, including the installed version, update channel and release notes.
- Backup: backup and restore, including the password flow.
No separate About section exists today (the version is shown inside App
updates), so no About group is added. The Settings ViewModels and their
state SHALL be unchanged; only the composition moves. Design tokens and
visual language SHALL be unchanged, and no new dependency SHALL be added.

#### Scenario: No setting lost
- **Given** the reorganized Settings
- **When** each group is opened
- **Then** every control from the old page is present in exactly one group and behaves as before.
