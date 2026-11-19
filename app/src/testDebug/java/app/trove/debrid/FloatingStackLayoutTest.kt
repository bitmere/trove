package app.trove.debrid

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import app.trove.debrid.ui.FloatingActions
import app.trove.debrid.ui.theme.TroveSpace
import app.trove.debrid.ui.theme.TroveTheme
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en-rUS-w400dp-h800dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FloatingStackLayoutTest {
    @get:Rule val rule = createComposeRule()

    @Test fun stackSharesAnAxisAndKeepsBothGaps() {
        rule.setContent {
            TroveTheme {
                Box(
                    Modifier.size(400.dp, 800.dp)
                        .background(MaterialTheme.colorScheme.surface)
                        .testTag("floating-stack-preview"),
                ) {
                    Column(Modifier.matchParentSize().padding(TroveSpace.medium)) {
                        repeat(20) { index ->
                            Text(
                                "Sample download ${index + 1} · Library preview · Ready",
                                modifier = Modifier.fillMaxWidth().padding(TroveSpace.small),
                            )
                        }
                    }
                    FloatingActions(
                        onAdd = {},
                        onSearch = {},
                        goToTopVisible = true,
                        onGoToTop = {},
                        modifier = Modifier.align(Alignment.BottomEnd)
                            .padding(end = TroveSpace.page, bottom = TroveSpace.page),
                    )
                }
            }
        }

        val top = rule.onNodeWithContentDescription("Go to top").fetchSemanticsNode().boundsInRoot
        val search = rule.onNodeWithContentDescription("Search").fetchSemanticsNode().boundsInRoot
        val add = rule.onNodeWithContentDescription("Add download").fetchSemanticsNode().boundsInRoot
        val centers = listOf(top.center.x, search.center.x, add.center.x)
        assertTrue(centers.max() - centers.min() <= 1f)
        with(rule.density) {
            assertEquals(16f, (search.top - top.bottom).toDp().value, 1f)
            assertEquals(12f, (add.top - search.bottom).toDp().value, 1f)
        }

        val appDirectory = File("app").takeIf { it.isDirectory } ?: File(".")
        val output = File(appDirectory, "build/test-screenshots/floating-stack.png")
        output.delete()
        // Geometry is the gate when Robolectric cannot capture a rendered window.
        // captureToImage() can fail with ComposeTimeoutException (a direct
        // Throwable, not a RuntimeException) when the native graphics runtime
        // does not settle; the screenshot is best-effort only.
        rule.mainClock.advanceTimeBy(1000)
        val bitmap = try {
            rule.onNodeWithTag("floating-stack-preview").captureToImage().asAndroidBitmap()
        } catch (e: RuntimeException) {
            println("Floating stack screenshot unavailable: ${e.message}")
            return
        } catch (e: ComposeTimeoutException) {
            println("Floating stack screenshot timed out: ${e.message}")
            return
        }
        output.parentFile?.mkdirs()
        output.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    }
}
