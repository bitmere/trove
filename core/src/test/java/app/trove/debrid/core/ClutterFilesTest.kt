package app.trove.debrid.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClutterFilesTest {
    @Test fun `hides release info and downloaded from text`() {
        assertClutter("group.NFO", "Downloaded from site.txt", "Downloaded._-FROM.site.TXT")
        assertVisible("group.nfo.mkv", "Downloaded from site.pdf", "Readme.txt")
    }

    @Test fun `hides sample prefixes and release tokens while preserving movie titles`() {
        assertClutter(
            "sample.mkv", "sample-sample", "Sample - Film.mp4",
            "Film.SAMPLE.mkv", "Film-sample-clip.avi", "Film_sample_clip.mp4",
        )
        assertVisible("Film.mkv", "Film.mp4", "The Sample.mkv", "The Sample 2024.mkv")
    }

    @Test fun `hides cover images and keeps other media`() {
        assertClutter(
            "cover.jpg", "film-folder.JPEG", "film.poster.png", "backdrop.gif",
            "film-thumb.webp", "FANART.bmp",
        )
        assertVisible("My Cover Story.mkv", "scene.jpg", "film-fanart.png")
    }

    @Test fun `classifies only the basename on either path style`() {
        assertClutter("Release/COVER.jpg", "Release\\Sample.mp4")
        assertVisible("Sample/Film.mkv", "cover art\\scene.jpg", "Downloaded-from/Readme.txt")
    }

    private fun assertClutter(vararg names: String) {
        names.forEach { assertTrue(it, isClutterFile(it)) }
    }

    private fun assertVisible(vararg names: String) {
        names.forEach { assertFalse(it, isClutterFile(it)) }
    }
}
