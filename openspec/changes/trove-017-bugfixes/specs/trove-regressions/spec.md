## ADDED Requirements

### Requirement: Player discovery and progress sorting

Trove SHALL publish only active discovery results and SHALL preserve order for equal displayed progress.

#### Scenario: Player discovery is superseded
- **WHEN** a newer discovery replaces a pending refresh
- **THEN** the previous refresh cannot change the choices or enable selection while the new refresh is pending.

#### Scenario: Zero progress ties
- **WHEN** progress values include positive and negative zero
- **THEN** progress sort preserves their input order.

### Requirement: Poster response and request limits

Trove SHALL bound poster buffering and close HTTP connections on cancellation.

#### Scenario: A large poster or cancelled request
- **WHEN** an encoded poster exceeds 4 MiB
- **THEN** loading stops at the limit and the card retains its placeholder.
- **WHEN** the caller cancels a provider, poster or updater HTTP request
- **THEN** the transport closes the active request to unblock pending reads.

### Requirement: Download action ownership

Trove SHALL use the account that supplied a row for its actions and allow dismissal during a details lookup.

#### Scenario: The provider changes or details close during a lookup
- **WHEN** an account changes
- **THEN** old rows and pending playback clear, and a stale action cannot use the replacement account.
- **WHEN** the user dismisses details during a file lookup
- **THEN** the lookup is cancelled and the sheet stays closed.

### Requirement: Updater state and diagnostics

Trove SHALL reflect the current download attempt and update state in Settings.

#### Scenario: Cancel, retry, or restore the UI
- **WHEN** the user cancels a download before its IO starts
- **THEN** the download action becomes available again.
- **WHEN** the downloader restarts from byte zero
- **THEN** progress resets for the replacement response.
- **WHEN** a chunk is rejected by the size limit
- **THEN** diagnostics count received bytes and hash only written bytes.
- **WHEN** a manifest cannot be decoded as UTF-8
- **THEN** Settings reports a manifest error.
- **WHEN** the release or signer error associated with a dialog disappears
- **THEN** the dialog closes and focus restoration skips the removed control.

### Requirement: Add input and optional cache checks

Trove SHALL preserve supported input when optional lookups fail and classify file links and magnet hashes by their relevant URL components.

#### Scenario: A lookup fails or a share contains URL metadata
- **WHEN** the optional magnet cache check fails
- **THEN** the classified magnet remains usable.
- **WHEN** hoster discovery fails
- **THEN** a later lookup can retry.
- **WHEN** a shared URL includes a fragment, a query, or trailing share text
- **THEN** file classification and upload naming use its path.
- **WHEN** text resembles a magnet hash outside a complete exact-topic parameter
- **THEN** it is ignored by the cache lookup.
