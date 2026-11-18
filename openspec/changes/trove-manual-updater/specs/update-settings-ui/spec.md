## ADDED Requirements

### Requirement: Phone Settings updater
Settings SHALL retain its account UI and navigation and show the current version and Check for updates. It SHALL use existing Material 3 controls, Surface, ListItem, Button, OutlinedButton, Feedback and TroveSpace with string resources. It SHALL distinguish Idle, Checking, UpToDate, Available, Downloading, ReadyToInstall, Installing, WaitingForConfirmation, Installed, NeedsUnknownSources and Error.

#### Scenario: Explicit sequence
- **Given** a newer release
- **When** checking completes
- **Then** the user can read its version and scrollable notes and explicitly choose Download.
- **When** verification completes
- **Then** Install is a separate explicit action.

#### Scenario: Notes accessibility and back
- **Given** notes longer than the dialog viewport
- **When** the user scrolls or presses Back
- **Then** the notes are reachable, Back dismisses only the dialog, and focus returns to the notes button.
- **And** reopening notes and returning from permission settings preserve scroll position.

### Requirement: Single ViewModel owner
An activity-hoisted UpdateViewModel SHALL own one operation and expose StateFlow. Network, download and installation work SHALL execute on IO; state writes SHALL run on the main ViewModel scope. Clearing the model SHALL cancel its job and event subscription. Install errors SHALL retain a retryInstall release/file pair so Try again retries installation rather than checking or downloading again.

#### Scenario: Repeated taps
- **Given** an update operation is running
- **When** actions are tapped repeatedly
- **Then** a second operation does not begin.

#### Scenario: Installation retry
- **Given** an install failed after successful download verification
- **When** Try again is selected
- **Then** the same file is reverified and installation is retried without network access.
