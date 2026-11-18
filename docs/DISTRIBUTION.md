# Trove distribution

Signed APKs and their checksum files are published at
[Trove GitHub Releases](https://github.com/bitmere/trove/releases).

In Settings, tap **Check for updates**. Stable is the default and uses the
latest stable GitHub Release. Beta includes stable releases and prereleases
and selects the highest Android versionCode. The channel is saved on the
device, independently of provider accounts. Changing it clears any offer
and the next check is manual. Returning to Stable can leave an installed
beta ahead of the latest stable release until the stable versionCode advances.

Trove checks the GitHub Release asset size, SHA-256, package, version and
signing identity before requesting Android installation. Android asks for
confirmation and Trove can relaunch after a successful update.

## Clean installation

The bitmere signing key requires a clean install when moving from an older
signing key. Uninstall Trove in Android Settings and install the signed APK
from the release page. Uninstalling removes local settings and the saved
API key. Enter the key again after installing.

## Release workflow

The manually dispatched **Release** workflow runs from main and verifies
that the approved tag matches versionName and points to an ancestor of main.
Review the `prerelease` input before dispatch. It defaults to `true`.
Choose `false` for the first stable binary so the Stable channel discovers it.
Version codes increase across both channels.

Configure the bitmere `TROVE_KEYSTORE_BASE64` and
`TROVE_KEYSTORE_PROPERTIES` signing secrets and the
`TROVE_RELEASE_CERT_SHA256` variable. The exact certificate SHA-256 is
`4253cb4104f8ccf9bc74e02c38b4830438af11d0ba1be907da0c657dfc1b12a8`
with DN `CN=Trove, O=Trove, C=IN`. GitHub Actions signs the optimized APK.
Validation runs without signing credentials.

The non-empty changelog entry becomes the release notes, followed by the
clean-install guidance and one terminal `Android-Version-Code: <positive decimal>`
line. Reserve that line for the workflow. Each release contains
`Trove-v<versionName>.apk` and its `.sha256` sibling. The sidecar contains
64 lowercase hex characters, two spaces, the exact APK basename, and a
terminal LF.

The gate reconciles existing drafts and published releases by ID. It verifies
the approved bytes before publishing, sets approved stable releases as latest,
and verifies the public assets and signer anonymously. Published reruns
verify existing bytes. Actions artifacts are short-lived verification evidence.
