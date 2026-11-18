package app.trove.debrid.update

import app.trove.debrid.core.HttpRequest
import app.trove.debrid.core.HttpTransport
import app.trove.debrid.core.update.LatestRelease
import app.trove.debrid.core.update.UpdateChannel
import app.trove.debrid.core.update.InvalidReleaseAssets
import app.trove.debrid.core.update.ReleaseManifestParser
import app.trove.debrid.core.update.UpdatePolicy
import kotlinx.coroutines.CancellationException
import java.io.IOException
import java.net.URI

private const val API_RELEASES = "https://api.github.com/repos/bitmere/trove/releases"
const val UPDATE_LATEST_URL = "$API_RELEASES/latest"

val UPDATE_NO_CACHE_HEADERS = mapOf("Cache-Control" to "no-cache", "Pragma" to "no-cache")

enum class UpdateIssue { NETWORK, UNAVAILABLE, RATE_LIMIT, ASSETS, MANIFEST, DOWNLOAD, EMPTY, TRUNCATED, DIGEST_MISMATCH, VERIFICATION, OWNERSHIP, PACKAGE, SIGNER, INSTALL, SETTINGS, PREVIOUS_SESSION }
class UpdateException(val issue: UpdateIssue, val diagnostics: UpdateDiagnostics? = null) : IOException(issue.name)

sealed interface UpdateCheckResult {
    data class Available(val release: LatestRelease) : UpdateCheckResult
    data object NoReleases : UpdateCheckResult
    data object UpToDate : UpdateCheckResult
    data class Error(val issue: UpdateIssue) : UpdateCheckResult
}

/** Uses the dedicated public transport. Caller supplies an IO dispatcher. */
class UpdateCheck(private val transport: HttpTransport, private val assets: ApkDownload) {
    suspend fun check(installedCode: Long, channel: UpdateChannel): UpdateCheckResult = try {
        val releases = fetch(channel)
        val release = releases.maxByOrNull { it.versionCode }
        when {
            release == null -> UpdateCheckResult.NoReleases
            !UpdatePolicy.isNewer(release.versionCode, installedCode) -> UpdateCheckResult.UpToDate
            else -> UpdateCheckResult.Available(release.copy(sha256 = assets.checksum(release)))
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: UpdateException) {
        UpdateCheckResult.Error(e.issue)
    } catch (_: IOException) {
        UpdateCheckResult.Error(UpdateIssue.NETWORK)
    }

    private suspend fun fetch(channel: UpdateChannel): List<LatestRelease> {
        val releases = mutableListOf<LatestRelease>()
        for (page in 1..5) {
            val url = when {
                channel == UpdateChannel.STABLE -> UPDATE_LATEST_URL
                page == 1 -> "$API_RELEASES?per_page=100"
                else -> "$API_RELEASES?per_page=100&page=$page"
            }
            val response = transport.execute(HttpRequest("GET", url, UPDATE_NO_CACHE_HEADERS))
            when (response.status) {
                200 -> Unit
                403, 429 -> throw UpdateException(UpdateIssue.RATE_LIMIT)
                404 -> throw UpdateException(UpdateIssue.UNAVAILABLE)
                else -> throw UpdateException(UpdateIssue.NETWORK)
            }
            try {
                releases.addAll(ReleaseManifestParser.parse(response.body, channel))
            } catch (_: InvalidReleaseAssets) {
                throw UpdateException(UpdateIssue.ASSETS)
            } catch (_: Exception) {
                throw UpdateException(UpdateIssue.MANIFEST)
            }
            if (channel == UpdateChannel.STABLE || response.headers.entries.none {
                    it.key.equals("Link", true) && it.value.contains("rel=\"next\"")
                }) {
                if (releases.distinctBy { it.versionCode }.size != releases.size ||
                    releases.distinctBy { it.tag }.size != releases.size
                ) {
                    throw UpdateException(UpdateIssue.MANIFEST)
                }
                return releases
            }
        }
        throw UpdateException(UpdateIssue.UNAVAILABLE)
    }
}

internal fun redirectUrl(from: String, location: String?): String {
    val target = location?.let { URI(from).resolve(it).toString() } ?: throw IOException("Missing redirect")
    if (!UpdatePolicy.isHttps(target)) throw IOException("Unsafe redirect")
    return target
}
