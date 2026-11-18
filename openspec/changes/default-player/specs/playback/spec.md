## ADDED Requirements

### Requirement: Default external video player
Settings SHALL list installed video ACTION_VIEW handlers plus Ask every time. Ask every time SHALL be the default when no valid package/activity pair is stored. Only the selected package and activity identifiers SHALL persist in ordinary trove_accounts SharedPreferences through DebridRepository. API-key encryption SHALL remain unchanged. Changing the choice SHALL apply to the next Play; clearing SHALL restore the chooser.

#### Scenario: Choose and clear
- **WHEN** a user selects a discovered player and later restarts Trove
- **THEN** the component remains selected and normal Play targets it directly if available.
- **WHEN** the user selects Ask every time
- **THEN** both identifiers are removed and normal Play opens the chooser.

### Requirement: Synthetic discovery and pure selection
PackageManager queries SHALL use only a harmless synthetic video URL and a video MIME, with Android 11+ queries visibility declared for ACTION_VIEW video intents. They SHALL never receive a requestdl bearer URL. Discovery and preference I/O SHALL execute off Main. Saved choice plus available components plus one-play override SHALL yield a direct component or chooser using Android-free JVM-testable logic.

#### Scenario: Real file MIME
- **WHEN** playing a file with a saved component
- **THEN** availability is checked using the synthetic URL and the existing playbackMimeType result for that file.
- **AND** the real bearer URL is placed only in the external playback intent.

### Requirement: Safe fallback retaining saved choice
Unavailable packages, stale activity identifiers and MIME-incompatible components SHALL yield the chooser. Direct startActivity SHALL catch ActivityNotFoundException and SecurityException and retry through an untargeted chooser. Failure SHALL NOT erase the preference. Chooser launch failures SHALL show generic feedback without logging or exposing bearer URLs or exception details.

#### Scenario: Removed or blocked player
- **WHEN** the saved player is removed, its activity changes, or direct launch fails
- **THEN** this playback falls back to the chooser and the saved component remains unchanged.

### Requirement: One-play chooser override
Download details SHALL offer Play with… and each file SHALL offer that action through an accessible overflow control. The override SHALL resolve the selected file's real ID, open the chooser even with a saved default, and SHALL NOT write preferences. Consuming the playback event SHALL reset the override.

#### Scenario: Multi-file override
- **WHEN** Play with… is selected for a particular file
- **THEN** the chooser receives the requestdl link for that file and kind.
- **AND** the next ordinary Play still uses the saved default if available.

### Requirement: Existing playback privacy
Playback SHALL remain external ACTION_VIEW with requestdl and concrete filename-derived video MIME. The streaming API SHALL NOT be used. Bearer URLs SHALL NOT enter logs, share targets, error messages, persisted state, navigation arguments, notifications or exceptions. Existing transient playback state SHALL remain unsaved and consumed after dispatch.
