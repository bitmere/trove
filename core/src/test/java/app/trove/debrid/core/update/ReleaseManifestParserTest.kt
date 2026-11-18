package app.trove.debrid.core.update

import org.junit.Assert.*
import org.junit.Test

class ReleaseManifestParserTest {
    private fun fixture(tag: String = "v0.2.0", code: String = "20", beta: Boolean = false): String {
        val apk = "Trove-$tag.apk"
        val url = "${UpdatePolicy.RELEASES}/download/$tag/$apk"
        return """{"tag_name":"$tag","name":"Trove $tag","body":"Notes\n\nAndroid-Version-Code: $code\n",
            "draft":false,"prerelease":$beta,"html_url":"${UpdatePolicy.RELEASES}/tag/$tag","assets":[
            {"name":"source.zip","state":"uploaded","size":1,"browser_download_url":"https://unused.invalid"},
            {"name":"$apk","state":"uploaded","size":3,"browser_download_url":"$url"},
            {"name":"$apk.sha256","state":"uploaded","size":${67 + apk.length},"browser_download_url":"$url.sha256"}]}"""
    }

    @Test fun readsStrictReleaseAndStripsTrailer() {
        val release = ReleaseManifestParser.parse(fixture(), UpdateChannel.STABLE).single()
        assertEquals(20L, release.versionCode)
        assertEquals(3L, release.size)
        assertEquals("Notes", release.notes)
    }

    @Test fun channelRulesApplyEvenWhenFlagsAreInconsistent() {
        val beta = fixture("v0.2.0-beta.1", "23")
        assertTrue(runCatching { ReleaseManifestParser.parse(beta, UpdateChannel.STABLE) }.isFailure)
        val feed = "[${fixture(beta = true)},$beta,${fixture().replace("\"draft\":false", "\"draft\":true")}]"
        assertEquals(listOf(20L, 23L), ReleaseManifestParser.parse(feed, UpdateChannel.BETA).map { it.versionCode })
        assertTrue(ReleaseManifestParser.parse("[]", UpdateChannel.BETA).isEmpty())
    }

    @Test fun rejectsMalformedMetadata() {
        for (code in listOf("0", "-1", "01", "20.1", "9223372036854775808", "20\\nSecond line",
            "20\\nAndroid-Version-Code: 21")) {
            assertTrue(code, runCatching { ReleaseManifestParser.parse(fixture(code = code), UpdateChannel.STABLE) }.isFailure)
        }
        for (body in listOf(fixture() + "{}", fixture().replace("\"draft\":false", "\"draft\":false,\"draft\":false"))) {
            assertTrue(runCatching { ReleaseManifestParser.parse(body, UpdateChannel.STABLE) }.isFailure)
        }
    }

    @Test fun rejectsMissingDuplicateAndInvalidAssets() {
        val apk = fixture().substringAfter("https://unused.invalid\"},").substringBeforeLast("},") + "}"
        for (body in listOf(fixture().replace("\"assets\":[", "\"assets\":[$apk,"),
            fixture().replace(".apk.sha256", ".apk.SHA256"), fixture().replace("\"size\":3", "\"size\":0"),
            fixture().replace("\"size\":3", "\"size\":999999999"),
            fixture().replace("/download/v0.2.0/", "/download/v0.3.0/"))) {
            assertTrue(runCatching { ReleaseManifestParser.parse(body, UpdateChannel.STABLE) }.exceptionOrNull() is InvalidReleaseAssets)
        }
    }
}
