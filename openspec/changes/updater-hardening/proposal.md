# Updater hardening

> Historical change. [bitmere-releases](../bitmere-releases/proposal.md) defines the current GitHub Releases distribution, update channels, and signing contract.

The first real update failed reproducibly with one combined hash/size error and no measurements. Make a failed phone network response diagnosable without exposing any account or download credentials.

Distinguish empty, size-mismatched and digest-mismatched APK downloads. Carry one bounded, typed diagnostics record in the terminal error state, readable and selectable in Settings: received bytes, response Content-Length, SHA-256 of held bytes, fixed framing label, numeric HTTP status and expected full size. Keep it in memory only, with no saved-state, backup or log copy. Never retain URLs, response bodies, raw headers or exception messages.

Allow three download request chains (initial plus two retries), with cancellable 500 ms and 1 s backoff and at most four redirect responses per chain. Send Cache-Control: no-cache and Pragma: no-cache on manifest and APK requests, including redirects, retries and resumes. Preserve useCaches=false and the public transport header allowlist.

Resume only a nonempty partial download with a known original Content-Length. Require HTTP 206, an exact Content-Range start at the held count, an end at the original total minus one, the same numeric total, and a consistent response length when supplied. Rehash the held prefix incrementally before appending; the final complete file must match the published SHA-256. HTTP 200 replaces the file; invalid ranges discard the partial and request the full file within the remaining budget. Unknown-length failures restart from zero. Cancellation closes active connections on IO and deletes the file; final failure deletes it too. Existing pending-session cleanup and installation re-verification remain.

The earlier contract derived size from HTTP Content-Length. The
bitmere-releases change supersedes it with the GitHub Release asset byte count.

No dependency, version, signing, workflow, manifest URL or release-process changes. Source-only work: the coordinator runs builds, focused tests, the full JVM suite and lint after diff review. Phone networking, cancellation, copying diagnostics and installation remain device checks.
