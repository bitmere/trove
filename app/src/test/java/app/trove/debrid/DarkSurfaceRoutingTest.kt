package app.trove.debrid

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DarkSurfaceRoutingTest {
    private val root = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
        .first { File(it, "app/src/main").isDirectory }

    @Test
    fun `screen surfaces cannot bypass theme with light palette tokens`() {
        val ui = File(root, "app/src/main/java/app/trove/debrid/ui")
        ui.walkTopDown().filter { it.extension == "kt" && it.name != "Theme.kt" }.forEach {
            val source = it.readText()
            assertFalse("${it.name} bypasses the active color scheme", source.contains("TroveColors."))
            assertFalse("${it.name} draws a white glass tint", source.contains("Color.White.copy"))
            assertFalse("${it.name} has a white background", Regex("(?:background|containerColor|trackColor)\\s*[=(]\\s*Color\\.White").containsMatchIn(source))
        }
    }

    @Test
    fun `both window themes use night-aware background and system bar resources`() {
        for (folder in listOf("values", "values-v27")) {
            val source = File(root, "app/src/main/res/$folder/themes.xml").readText()
            assertFalse(source.contains("@android:color/white"))
            assertTrue(source.contains("@color/window_background"))
            assertTrue(source.contains("@bool/light_system_bars"))
        }
        val night = File(root, "app/src/main/res/values-night/window.xml").readText()
        assertTrue(night.contains("#101511"))
        assertTrue(night.contains(">false<"))
    }
}
