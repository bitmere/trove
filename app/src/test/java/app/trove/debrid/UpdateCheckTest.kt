package app.trove.debrid

import app.trove.debrid.core.HttpRequest
import app.trove.debrid.core.HttpResponse
import app.trove.debrid.core.HttpTransport
import app.trove.debrid.core.update.UpdateChannel
import app.trove.debrid.update.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class UpdateCheckTest {
    private val hash = "a".repeat(64)
    private val stable = UpdateChannel.STABLE
    private val beta = UpdateChannel.BETA
    private fun fixture(version: String, code: Long, prerelease: Boolean): String {
        val tag = "v$version"
        val name = "Trove-$tag.apk"
        val releases = "https://github.com/bitmere/trove/releases"
        val url = "$releases/download/$tag/$name"
        return """{"tag_name":"$tag","name":"Trove $tag","body":"Notes\nAndroid-Version-Code: $code",
            "draft":false,"prerelease":$prerelease,"html_url":"$releases/tag/$tag","assets":[
            {"name":"$name","state":"uploaded","size":3,"browser_download_url":"$url"},
            {"name":"$name.sha256","state":"uploaded","size":${67 + name.length},"browser_download_url":"$url.sha256"}]}"""
    }

    private fun checker(badChecksum: Boolean = false, response: (HttpRequest) -> HttpResponse): UpdateCheck {
        val transport = object : HttpTransport {
            override suspend fun execute(request: HttpRequest): HttpResponse {
                assertEquals(UPDATE_NO_CACHE_HEADERS, request.headers)
                return response(request)
            }
        }
        return UpdateCheck(transport, ApkDownload(UpdateStream { request ->
            assertEquals(UPDATE_NO_CACHE_HEADERS, request.headers)
            assertTrue(request.url.startsWith("https://github.com/bitmere/trove/releases/download/"))
            val basename = request.url.substringAfterLast('/').removeSuffix(".sha256")
            val line = if (badChecksum) "$hash $basename\n" else "$hash  $basename\n"
            object : UpdateConnection {
                override val status = 200
                override val length = null
                override val location = null
                override val input = line.byteInputStream()
                override fun close() = input.close()
            }
        }))
    }

    @Test fun stableUsesLatestAndVerifiesChecksumBeforeOffer() = runTest {
        val check = checker { request ->
            assertEquals(UPDATE_LATEST_URL, request.url)
            HttpResponse(200, fixture("0.2.0", 20, false))
        }
        val result = check.check(19, stable) as UpdateCheckResult.Available
        assertEquals(hash, result.release.sha256)
        assertEquals(UpdateCheckResult.UpToDate, check.check(20, stable))
    }

    @Test fun betaUsesHighestCodeAcrossPagesAndChannels() = runTest {
        val check = checker { request ->
            when (request.url.substringAfter('?')) {
                "per_page=100" -> HttpResponse(200, "[${fixture("1.0.0", 20, false)}]",
                    mapOf("Link" to "<https://untrusted.invalid/>; rel=\"next\""))
                "per_page=100&page=2" -> HttpResponse(200, "[${fixture("0.2.0", 30, true)}]")
                else -> error("Unexpected page")
            }
        }
        assertEquals(30L, (check.check(1, beta) as UpdateCheckResult.Available).release.versionCode)
    }

    @Test fun distinguishesEmptyRateLimitMetadataAndAssets() = runTest {
        assertEquals(UpdateCheckResult.NoReleases, checker { HttpResponse(200, "[]") }.check(1, beta))
        assertEquals(UpdateCheckResult.Error(UpdateIssue.RATE_LIMIT), checker { HttpResponse(403, "") }.check(1, stable))
        assertEquals(UpdateCheckResult.Error(UpdateIssue.UNAVAILABLE), checker { HttpResponse(404, "") }.check(1, stable))
        assertEquals(UpdateCheckResult.Error(UpdateIssue.MANIFEST), checker { HttpResponse(200, "{}") }.check(1, stable))
        val valid = fixture("0.2.0", 20, false)
        assertEquals(UpdateCheckResult.Error(UpdateIssue.MANIFEST),
            checker { HttpResponse(200, "[$valid,${fixture("0.3.0", 20, true)}]") }.check(1, beta))
        assertEquals(UpdateCheckResult.Error(UpdateIssue.ASSETS),
            checker { HttpResponse(200, valid.replace("\"size\":3", "\"size\":0")) }.check(1, stable))
        assertEquals(UpdateCheckResult.Error(UpdateIssue.ASSETS),
            checker(badChecksum = true) { HttpResponse(200, valid) }.check(1, stable))
    }

    @Test fun propagatesCancellation() = runTest {
        assertTrue(runCatching { checker { throw CancellationException() }.check(1, stable) }
            .exceptionOrNull() is CancellationException)
    }
}
