# Trove release-path audit — 2026-11-17

This report records the pre-migration release path. The current signing and
public GitHub Release contract is documented in [Distribution](docs/DISTRIBUTION.md).

Historical scope: every step/job in `.github/workflows/release.yml`, every function in
`tools/release_gate.py`, its tests, and the release signing configuration in
`app/build.gradle.kts`. Changes are confined to the workflow, gate, tests and this
report. No user-facing app behavior changed; no OpenSpec change is needed.

## Findings and fixes

| Location | Evidence / failure mode | Fix applied |
| --- | --- | --- |
| `release_gate.py: release_state`, `recover`, `publish_private` | GET-by-tag can return 404 for an existing draft. The supplied incident identifies draft 448631230 with pending tag `v0.1.15`, an untagged URL and no assets. Old publication dereferenced `None`. | Paginate release listings, match `tag_name`, reject ambiguity, and use the existing release ID. Regression fixture has an untagged URL and a failing by-tag lookup; verifies upload and un-draft without creation or deletion. No live draft was accessed or modified. |
| `publish_private`: creation | `gh release create` output was discarded, then the same unreliable by-tag lookup was repeated. An interrupted response could leave a successfully created draft. | POST JSON, capture and validate its ID, fetch by ID; on a transport/create failure, rediscover a matching draft before proceeding. Never blindly retry a mutation. |
| `publish_private`: upload / publish | Tag-based upload/edit repeated discovery assumptions; successful CLI exit did not prove the uploaded bytes or final publication state. | Upload and PATCH by ID. Download and compare both required assets before publication, then verify published state and bytes. Existing assets are never replaced; published reruns perform reads only. |
| `api`, `api_list`, `validate_release`, `release_by_id` | Old parser depended on the printed HTTP header/status layout. Nullable/missing fields caused `KeyError`/`TypeError`; list pagination and ambiguous matches were unchecked. | Parse JSON only, fail on command failure/null/malformed/duplicate JSON, bound subprocess timeouts and pagination, validate required fields/types, source, ID, notes/title and channel. Errors do not echo request bodies or authentication diagnostics. |
| `release_assets`, `download_asset` | Embedded asset lists were assumed complete; duplicate, incomplete (`starter`), missing-field or truncated assets were unchecked. | Use a paginated assets endpoint, validate IDs/names/state/size and uniqueness; verify downloaded size and optional server digest. Incomplete uploads are rejected without deletion. |
| `recover` | A draft containing a checksum but no APK could trigger a fresh, potentially different build. | Rebuild only for absent releases or empty drafts. Reuse the existing APK; reject published releases without APKs and nonempty drafts without APKs. Partial APK-first uploads resume safely. |
| `verify_apk`: certificate parsing | Numbered and V2 formats were already supported, but repeated identical certificates failed list comparison; malformed extra digest lines could be silently ignored. | Parse every certificate SHA-256 line, validate full digests, compare the set of identities to the unchanged full configured pin. Tests cover both prefixes, repeated schemes, extra signers, malformed digests and public-key-only output. |
| `verify_apk`: tool selection / badging | Tool selection required only apksigner, accepted preview directories, and raised an opaque error for an empty SDK. Package parsing required a fixed field order. | Select a complete stable apksigner/aapt pair numerically, report missing tools clearly, and read named package attributes independent of ordering. Package, version and debuggable checks remain enforced. |
| `release.yml`: controller test execution | Tests ran after switching to the approved app tag, although the gate was copied from the main-controlled workflow checkout. Thus new gate code could run without its matching tests. | Run controller tests before tag checkout; retain tagged app JVM tests, lint and debug build afterward. |
| `release.yml`: SDK / signing caches | `ubuntu-latest` SDK contents can change. Gradle caching was globally enabled, including release build-cache reuse and setup-gradle cache persistence around signing. | Explicitly provision platform 36 and build-tools 36.0.0 in both jobs; disable release Gradle persistence, configuration cache and build cache. Validation cache is read-only. |
| `release.yml`: properties restoration | Regex removal did not implement Java Properties escaping/continuations. A trailing continuation could consume the appended storeFile; UTF-8 output disagreed with Java's Latin-1 InputStream loader for non-ASCII values. | Preserve properties syntax, append an overriding storeFile after two newlines, and encode non-ASCII text as Java Unicode escapes, including surrogate pairs. Synthetic CRLF, escaped-key, trailing-continuation and Unicode fixtures execute the actual workflow Python. |
| `prepare` | Missing/invalid certificate variable was discovered only after validation builds. | Validate the existing full-pin format and approved prefix during prepare, before builds. No pin, secret or variable was changed. |
| `check_mirror`, workflow publication order | The raw-CDN manifest used by verify may be stale. Authoritative mirror conflicts were previously discovered only after private publication. | Clone/fetch and inspect actual mirror main before private publication; reject rollback, same-code divergence and conflicting versioned APK bytes. Refresh and repeat checks during mirror publication. |
| `check_mirror`, `publish_mirror` | `clone --branch main` failed on an empty mirror; a second call in the same work directory also failed at clone. | Handle an empty remote with an orphan main; reuse/fetch the working clone. Existing main without latest.json remains supported. Nonempty repos lacking main are rejected. Capture the base directly rather than inferring it from HEAD ancestry. |
| `fetch`, `anonymous` | One stale manifest, propagation 404, truncated download or transient connection failure aborted anonymous verification immediately. | Send unauthenticated cache-busting requests with no-cache headers; retry missing/stale manifests, differing APK bytes and transport/truncation failures with bounded backoff. Persistent divergence still fails. |

All fixes have offline regression coverage in `tools/test_release_gate.py`.
Additional policy tests covered concurrent mirror changes, immutable versioned
bytes, exact same-code retries, checksum/metadata tampering, and rollback rejection.

## Reviewed and retained

- Dispatch-only trigger; main-controlled workflow; valid job-level contexts;
  least-privilege read/write job permissions; global non-cancelling publication
  concurrency; validated source output; tag ancestry and remote recheck before
  publication; APK retention; always-run removal of temporary signing/auth files.
- Git outputs are plumbing contracts (object IDs, exit status, ls-tree paths),
  checked explicitly. No keytool, curl, jq or workflow-run-response parsing exists
  in this path. gh human output and HTTP-header parsing have been removed.
- Gradle already uses the Actions-provided properties path, checks required keys
  and keystore existence, guards APK/AAB production, allows keyless lint, and has
  no debug-signing fallback. No Gradle source change was necessary.
- At the time, the mirror credential was scoped to the publication step and an ephemeral askpass
  script; credential helpers were disabled for mirror Git operations. Push was
  non-forced, with a fresh base check and manifest guard. APK plus latest.json were
  committed together. Anonymous verification checked the exact verified APK hash.
- At the time the version was 0.1.15/code 15, tag remains v0.1.15, and the approved full-pin
  comparison was unchanged. No keys, variables, secrets or application code changed.

## Verification and limits

- Focused release tests, then full tool discovery: **55 tests pass**. Tests use
  synthetic certificate fingerprints/data, a fake REST lifecycle and temporary
  local bare Git repositories; no GitHub mutation or credential was used.
- Against the saved original gate: 39 added behavior tests ran; **34 test methods
  fail/error**, including the untagged-draft regression. Five already-correct
  behaviors remain passing. All three workflow regression methods also fail
  against the original workflow (including fixture subtest failures).
- Workflow YAML parsed; all **21 shell blocks** passed `bash -n`; embedded Python
  syntax checked. This is not an actionlint/server-side workflow validation.
- This supplied checkout has no usable Git metadata, Java executable or Android
  SDK. Branch status, Gradle tests/lint/builds, real signing tools, live draft
  metadata and token permissions could not be verified here. Saved originals
  were used for comparisons. No source-repository push, PR, tag, dispatch or
  release was performed; Git pushes in tests target only disposable local fixtures.
- GitHub/CDN availability and independent writers remain external conditions.
  The former cross-repository publication was non-atomic. The recovery procedure
  reused the existing exact APK after a public delivery failure. Conflicting
  metadata, incomplete assets and permission failures stopped the run.

## Primary-source checks

- [GitHub releases REST documentation](https://docs.github.com/en/rest/releases/releases):
  GET-by-tag is for published releases; listing includes drafts for push-authorized
  callers; creation/update operate on explicit release metadata/IDs.
- [GitHub CLI release lookup implementation](https://github.com/cli/cli/blob/trunk/pkg/cmd/release/shared/fetch.go)
  and [creation implementation](https://github.com/cli/cli/blob/trunk/pkg/cmd/release/create/create.go):
  CLI uses a separate pending-draft lookup; its behavior must not be inferred from
  REST GET-by-tag. The precise incident behavior is supplied user evidence, not
  independently reproduced against the live repository.
- [Release assets API](https://docs.github.com/en/rest/releases/assets) and
  [gh api manual](https://cli.github.com/manual/gh_api): ID-addressed uploads,
  paginated asset lists, upload states and raw/JSON request bodies.
- [AOSP apksigner source](https://android.googlesource.com/platform/tools/apksig.git/+/refs/heads/android12-qpr1-release/src/apksigner/java/com/android/apksigner/ApkSignerTool.java):
  certificate and public-key digests are distinct; full certificate identity is
  required. The incident's newer V2 output is retained as a synthetic fixture.
