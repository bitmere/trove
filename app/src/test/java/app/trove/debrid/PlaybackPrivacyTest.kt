package app.trove.debrid

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Architectural guard for the Android boundary; runs without an Android runtime. */
class PlaybackPrivacyTest {
    @Test
    fun `playback boundary only opens a video viewer and never shares or logs the link`() {
        val root = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
            .first { File(it, "app/src/main/java/app/trove/debrid/MainActivity.kt").isFile }
        val source = File(root, "app/src/main/java/app/trove/debrid/MainActivity.kt").readText()
        val playback = source.substringAfter("private fun playUrl(url: String, fileName: String?)")
            .substringBefore("private class SimpleFactory")
        assertTrue(playback.contains("Intent(Intent.ACTION_VIEW)"))
        assertTrue(playback.contains("setDataAndType(url.toUri(), playbackMimeType(fileName))"))
        assertTrue(playback.contains("addCategory(Intent.CATEGORY_DEFAULT)"))
        assertTrue(playback.contains("Intent.createChooser(intent, \"Play with\")"))
        for (forbidden in listOf("ACTION_SEND", "EXTRA_TEXT", "putExtra", "Log.", "Timber", "println")) {
            assertFalse("Playback must not send the bearer link to $forbidden", playback.contains(forbidden))
        }
    }
}
