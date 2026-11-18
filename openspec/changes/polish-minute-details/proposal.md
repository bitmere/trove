# Polish minute details in the phone UI

## Changes

| File | Change and reason |
| --- | --- |
| `UpdateSection.kt` | Space the Stable and Beta chips by `TroveSpace.small` (8 dp). Replace the doubly inset ListItem header with a start-aligned, two-line header using the ListItem typography, minimum height and supporting-text color. Use the shared Close string in both updater dialogs. |
| `SettingsScreen.kt` | Add Try again and accessible error feedback inside the player picker. Retry discovery in place. Give radio rows at least 48 dp of height. Disable Show/Hide while the key field is disabled during verification and use the button's actual content color for the verification spinner. |
| `AddSheet.kt` | Present cache lookup and its result through a polite live region. Keep the existing reserved spinner space and use the disabled Add button's content color for the spinner. |
| `DownloadCard.kt` | Allow localized dates to wrap. Reserve room for the transfer percentage beside a wrapping status label and align the two vertically. Let the playback spinner accept accessibility semantics from callers. |
| `DownloadDetailsSheet.kt` | Label the progress indicator while loading file details. Keep the filename action label and expose the preparing state when a file's Play icon becomes a spinner. |
| `SettingsViewModel.kt` | Remove the unused command for clearing Settings feedback. |
| `strings.xml` | Remove the updater-specific Close resource after both dialogs move to the identical shared `close` resource. |

## Deferred and retained items

- Clear Search only clears the query and the search UI has no reset or go-to-top action. The pending `search-top-reset` change owns those updates.
- The Downloads screen also owns the delete confirmation and the loading and empty-state routing. Its source and the empty-state source are reserved for the pending work. The empty state already offers Retry, Clear search or Add as appropriate.
- Keep `downloadFormat` and `formatCount` because tests reference both helpers and the count pill calls `formatCount`. Keep `ic_sort` because the sort button uses it.
- Leave the floating action cluster and README with their current owners. All fixes fit the existing screens and permissions and reuse current dependencies.
- The existing UpdateSection instrumentation tests assert selection, callbacks, update states and dialog focus. They do not assert the previous alignment and need no edits.

## Validation

Review the changed sources and XML in the copy. After review, run focused tests, the full suite and lint. On a phone, check channel spacing and alignment, keyboard and large-text layouts, the player-picker retry path, Add cache announcements and per-file playback with TalkBack. Check the player chooser, incoming shares and credential storage through their normal on-device entry points.
