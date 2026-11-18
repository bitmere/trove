## MODIFIED Requirements

> Historical change. [bitmere-releases](../../../bitmere-releases/proposal.md) defines the current GitHub Releases distribution, update channels, and signing contract.

### Requirement: Streaming bounded download
Trove SHALL retain the existing 8 KB streaming buffer, incremental SHA-256, 256 MB safety cap, serialized download gate, pending-session cleanup and installation verification. It SHALL delete the target on final failure or cancellation. A partial file SHALL exist only within one download call and SHALL never become installable until complete hash verification passes.

#### Scenario: Separate failure reasons
- **Given** an empty response, a count different from the expected Content-Length, or a complete response with the wrong digest
- **When** the bounded retries are exhausted
- **Then** Settings shows EMPTY, TRUNCATED or DIGEST_MISMATCH respectively, with distinct localized messages and no installation action.
- **And** the install path retains its own VERIFICATION issue and rechecks integrity.

## ADDED Requirements

### Requirement: Privacy-safe diagnostics
Trove SHALL attach at most one terminal failure record to Error.detail in memory. The record SHALL contain received byte count, reported response Content-Length (including zero or unknown), computed SHA-256, a fixed response framing label, numeric response status and expected full size. For resume, count and digest cover the held prefix plus newly received bytes; Content-Length describes the latest response. Settings SHALL allow text selection for user-initiated copying. No diagnostics SHALL retain a URL, API key, requestdl bearer link, response body, raw header or exception message. No logging, saved-state or disk history SHALL be introduced.

#### Scenario: Wrong response contains sensitive-looking data
- **Given** a fake body or header containing a URL, key or bearer link
- **When** verification fails
- **Then** only numerical measurements, fixed labels and a computed digest appear in the record and error UI.

### Requirement: Bounded retries
Trove SHALL permit an initial APK request chain and at most two retries, delayed by 500 ms and 1 s. Each chain SHALL retain the existing four-response redirect bound and APK mirror allowlist. Manifest checks SHALL retain their existing no-retry behavior. Download retries SHALL use no-cache headers, including complete digest mismatches. Unsafe redirects and manifest policy failures SHALL not be retried. Cancellation SHALL stop retries, close the owned active connection on IO and remove its file.

#### Scenario: Transient or permanent failure
- **Given** a stream failing twice then succeeding, or a stream failing forever
- **When** a user starts downloading
- **Then** the third request succeeds or surfaces an error, and no fourth request chain starts.

#### Scenario: Cancel during backoff or read
- **Given** a partial file and an active request or pending retry
- **When** cancellation occurs
- **Then** the connection is closed, no retry starts and the partial file is deleted.

### Requirement: Validated resume
Trove SHALL request bytes starting at the held count only for a nonempty truncated file with a known original length. It SHALL append only on 206 with a numeric Content-Range matching the exact start, original total and final byte, and a consistent response length when present. A 200 response SHALL replace the partial file; an invalid 206 SHALL trigger a full request within the remaining retry budget. All completed files SHALL match the published SHA-256. The existing manifest has no published APK size field; HTTP totals SHALL be checked consistently without changing that contract.

#### Scenario: Confirmed range
- **Given** one held byte of a three-byte file
- **When** the server answers Range bytes=1- with 206 and Content-Range bytes 1-2/3
- **Then** the two new bytes are appended and the entire file hash gates readiness.

#### Scenario: Unconfirmed range
- **Given** a held prefix
- **When** the response is 200 or a 206 with mismatched start, total, end or length
- **Then** no unvalidated bytes are appended and downloading restarts from zero within the same retry budget.

#### Scenario: Wrong resumed digest
- **Given** range framing is valid but resumed bytes are wrong
- **When** full-file verification runs
- **Then** the issue is DIGEST_MISMATCH and any remaining retry starts from zero with no-cache headers.

### Requirement: No-cache public requests
Trove SHALL send Cache-Control: no-cache and Pragma: no-cache on every manifest and APK request, including redirects, retries and resumes. PublicUpdateStream SHALL keep useCaches=false. The public transport SHALL reject headers outside these values and a syntactically valid APK Range; PublicManifestTransport SHALL accept only the two no-cache header values.

#### Scenario: Request headers
- **Given** a manifest check or APK download
- **When** the fake transport or connection factory receives a request
- **Then** both no-cache headers are present, and no account authorization headers are forwarded.
