package app.trove.debrid.core.update

import java.net.URI

object UpdatePolicy {
    const val PACKAGE_NAME = "app.trove.debrid"
    const val MAX_MANIFEST_BYTES = 2 * 1024 * 1024
    const val MAX_APK_BYTES = 256L * 1024 * 1024
    fun isNewer(remote: Long, installed: Long): Boolean = remote > 0 && remote > installed
    fun isHash(hash: String): Boolean = hash.matches(Regex("[0-9a-f]{64}"))
    fun isHttps(url: String): Boolean = runCatching {
        val uri = URI(url)
        uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.rawUserInfo == null && uri.fragment == null
    }.getOrDefault(false)

    internal val tagPattern = Regex("v(?:0|[1-9][0-9]*)\\.(?:0|[1-9][0-9]*)\\.(?:0|[1-9][0-9]*)(?:-beta\\.[1-9][0-9]*)?")
    const val RELEASES = "https://github.com/bitmere/trove/releases"
    const val RELEASE_CERT = "4253cb4104f8ccf9bc74e02c38b4830438af11d0ba1be907da0c657dfc1b12a8"

    fun isAssetUrl(url: String, tag: String, name: String): Boolean {
        val apk = "Trove-$tag.apk"
        return tagPattern.matches(tag) && (name == apk || name == "$apk.sha256") &&
            url == "$RELEASES/download/$tag/$name"
    }

    fun isReleaseRedirect(url: String): Boolean = runCatching {
        val uri = URI(url)
        val cdn = "release-assets.githubusercontent.com"
        isHttps(url) && (uri.rawAuthority == cdn || uri.rawAuthority == "$cdn:443") &&
            uri.rawPath.length > 1
    }.getOrDefault(false)

    fun isEligible(packageName: String, archiveCode: Long, expectedCode: Long, installedCode: Long): Boolean =
        packageName == PACKAGE_NAME && archiveCode == expectedCode && isNewer(expectedCode, installedCode)
}
