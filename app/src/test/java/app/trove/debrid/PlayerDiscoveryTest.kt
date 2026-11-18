package app.trove.debrid

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class PlayerDiscoveryTest {
    private fun root() = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
        .first { File(it, "app/src/main/AndroidManifest.xml").isFile }

    @Test fun `production discovery seam receives only the synthetic URL for every MIME`() = runTest {
        val queries = mutableListOf<VideoPlayerQuery>()
        val discovery = SyntheticPlayerDiscovery { queries += it; emptyList() }
        discovery.players("video/mp4")
        discovery.players("video/x-matroska")
        assertEquals(listOf("video/mp4", "video/x-matroska"), queries.map { it.mimeType })
        assertEquals(listOf("https://example.invalid/sample.mp4", "https://example.invalid/sample.mp4"), queries.map { it.url })
    }

    @Test fun `Android query uses synthetic discovery seam and manifest grants visibility`() {
        val adapter = File(root(), "app/src/main/java/app/trove/debrid/AndroidPlayerDiscovery.kt").readText()
        assertTrue(adapter.contains("SyntheticPlayerDiscovery"))
        assertTrue(adapter.contains("setDataAndType(query.url.toUri(), query.mimeType)"))
        assertTrue(adapter.contains("queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)"))
        assertFalse(adapter.contains("requestdl"))
        val document = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(File(root(), "app/src/main/AndroidManifest.xml"))
        val queries = document.getElementsByTagName("queries")
        assertEquals(1, queries.length)
        val query = queries.item(0) as org.w3c.dom.Element
        val ns = "http://schemas.android.com/apk/res/android"
        val action = query.getElementsByTagName("action").item(0) as org.w3c.dom.Element
        val data = query.getElementsByTagName("data").item(0) as org.w3c.dom.Element
        assertEquals("android.intent.action.VIEW", action.getAttributeNS(ns, "name"))
        assertEquals("video/*", data.getAttributeNS(ns, "mimeType"))
        assertEquals("https", data.getAttributeNS(ns, "scheme"))
    }

    @Test fun `direct launch failures remove component and fall back without clearing preference`() {
        val source = File(root(), "app/src/main/java/app/trove/debrid/MainActivity.kt").readText()
        val playback = source.substringAfter("private suspend fun playUrl").substringBefore("private class SimpleFactory")
        assertTrue(playback.contains("catch (_: ActivityNotFoundException)"))
        assertTrue(playback.contains("catch (_: SecurityException)"))
        assertTrue(playback.indexOf("intent.component = null") < playback.indexOf("Intent.createChooser"))
        assertFalse(playback.contains("setDefaultPlayer"))
        assertFalse(playback.contains("queryIntentActivities"))
        for (forbidden in listOf("ACTION_SEND", "EXTRA_TEXT", "putExtra", "Log.", "Timber", "println")) {
            assertFalse("Playback must not expose the bearer link", playback.contains(forbidden))
        }
    }
}
