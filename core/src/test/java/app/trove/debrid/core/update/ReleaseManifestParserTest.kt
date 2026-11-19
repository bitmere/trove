package app.trove.debrid.core.update

import org.junit.Assert.*
import org.junit.Test

class ReleaseManifestParserTest {
    private val hash = "a".repeat(64)
    private val hashLine = "SHA-256: $hash"

    private fun fixture(
        tag: String = "v0.2.0",
        code: String = "20",
        beta: Boolean = false,
        hashLines: String = "",
        sidecar: Boolean = true,
    ): String {
        val apk = "Trove-$tag.apk"
        val url = "${UpdatePolicy.RELEASES}/download/$tag/$apk"
        val checksumAsset = """,{"name":"$apk.sha256","state":"uploaded","size":${67 + apk.length},"browser_download_url":"$url.sha256"}"""
        return """{"tag_name":"$tag","name":"Trove $tag","body":"Notes\n\n${hashLines}Android-Version-Code: $code\n",
            "draft":false,"prerelease":$beta,"html_url":"${UpdatePolicy.RELEASES}/tag/$tag","assets":[
            {"name":"source.zip","state":"uploaded","size":1,"browser_download_url":"https://unused.invalid"},
            {"name":"$apk","state":"uploaded","size":3,"browser_download_url":"$url"}${if (sidecar) checksumAsset else ""}]}"""
    }

    @Test fun readsStrictReleaseAndStripsTrailer() {
        val release = ReleaseManifestParser.parse(fixture(), UpdateChannel.STABLE).single()
        assertEquals(20L, release.versionCode)
        assertEquals(3L, release.size)
        assertEquals("Notes", release.notes)
    }

    @Test fun parsesNotesHashAndOptionalSidecarIndependently() {
        for (hasHash in listOf(false, true)) {
            for (hasSidecar in listOf(false, true)) {
                val body = fixture(hashLines = if (hasHash) "$hashLine\\n" else "", sidecar = hasSidecar)
                val release = ReleaseManifestParser.parse(body, UpdateChannel.STABLE).single()
                assertEquals(if (hasHash) hash else "", release.sha256)
                assertEquals(if (hasSidecar) "${release.apkUrl}.sha256" else "", release.checksumUrl)
                assertEquals("Notes", release.notes)
            }
        }
    }

    @Test fun rejectsMalformedNotesHashesEvenWithSidecar() {
        for (line in listOf(hashLine.dropLast(1), "SHA-256: ${hash.uppercase()}", "$hashLine ",
            "SHA-256:  $hash", "SHA-256:", "$hashLine\\n$hashLine", "$hashLine\\nMore notes", " $hashLine")) {
            val result = runCatching {
                ReleaseManifestParser.parse(fixture(hashLines = "$line\\n"), UpdateChannel.STABLE)
            }
            assertTrue(line, result.exceptionOrNull() is IllegalArgumentException)
            assertFalse(line, result.exceptionOrNull() is InvalidReleaseAssets)
        }
    }

    @Test fun rejectsWrongSidecarSizeEvenWithNotesHash() {
        val size = 67 + "Trove-v0.2.0.apk".length
        val body = fixture(hashLines = "$hashLine\\n").replace("\"size\":$size", "\"size\":${size - 1}")
        val result = runCatching { ReleaseManifestParser.parse(body, UpdateChannel.STABLE) }
        assertTrue(result.exceptionOrNull() is InvalidReleaseAssets)
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
        val manifest = fixture()
        val apk = manifest.substringAfter("https://unused.invalid\"},").substringBeforeLast("},") + "}"
        val duplicateSidecar = manifest.removeSuffix("]}") + "," + manifest.substringAfterLast("},")
        for (body in listOf(manifest.replace("\"assets\":[", "\"assets\":[$apk,"),
            manifest.replace("\"Trove-v0.2.0.apk\"", "\"missing.apk\""),
            duplicateSidecar, manifest.replace("\"size\":3", "\"size\":0"),
            fixture().replace("\"size\":3", "\"size\":999999999"),
            fixture().replace("/download/v0.2.0/", "/download/v0.3.0/"))) {
            assertTrue(runCatching { ReleaseManifestParser.parse(body, UpdateChannel.STABLE) }.exceptionOrNull() is InvalidReleaseAssets)
        }
    }
}
