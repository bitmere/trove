## ADDED Requirements

### Requirement: Explicit Android installation
Trove SHALL check canRequestPackageInstalls before creating any session. Without permission it SHALL return NeedsUnknownSources. With permission it SHALL settle a previous session, create a MODE_FULL_INSTALL session, set the expected package and size, require user action on API 31+, write then fsync then commit. Only an exception on the settled session path SHALL invoke the system installer through a narrow FileProvider for filesDir/updates.

#### Scenario: Grant missing
- **Given** unknown-source installation is denied
- **When** the user chooses Install
- **Then** no session or fallback is created and Settings offers Android install permission settings.

#### Scenario: Permission return
- **Given** the user left for Android settings
- **When** they return with the grant enabled
- **Then** Install is offered again without starting automatically.

#### Scenario: Session throws
- **Given** a session was created
- **When** opening, writing or committing fails
- **Then** the created session is abandoned and closed before fallback.
- **And** an abandonment failure preserves correlation and blocks fallback.

### Requirement: Durable correlated callbacks
Trove SHALL persist one bounded session id, version, canonical owned APK path, operation id and creation time in non-backup storage before writing the session. It SHALL reject callbacks with a different tuple, action or operation URI and callbacks older than 24 hours. Expired pending sessions SHALL still be settled before replacement. The non-exported receiver SHALL use an explicit PendingIntent component, goAsync and IO cleanup.

#### Scenario: Process replaced
- **Given** a correlated SUCCESS callback and no old process listener
- **When** the receiver handles success
- **Then** it deletes the APK, clears the record and independently attempts MainActivity relaunch with NEW_TASK and CLEAR_TOP.

#### Scenario: Confirmation required
- **Given** a correlated PENDING_USER_ACTION callback
- **When** a confirmation intent is available
- **Then** the receiver opens it and reports WaitingForConfirmation while retaining correlation.

#### Scenario: Stale callback or installer failure
- **Given** a stale/spoofed callback
- **When** it is received
- **Then** no confirmation, deletion, relaunch or UI change occurs.
- **Given** a correlated failure callback
- **When** it is handled
- **Then** Settings reports Failed with the bounded status message and retains the verified APK for retry.
