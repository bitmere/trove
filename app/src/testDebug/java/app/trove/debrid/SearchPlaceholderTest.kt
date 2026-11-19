package app.trove.debrid

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import app.trove.debrid.core.DownloadSort
import app.trove.debrid.ui.downloads.DownloadsScreen
import app.trove.debrid.ui.downloads.DownloadsViewModel
import app.trove.debrid.ui.theme.TroveTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Regression test for the 0.1.26 search placeholder wrap (user report with
 * screenshot): changing the sort makes Reset appear, which steals ~107dp
 * from the search field, and the placeholder — a bare Text with no line
 * limit — wrapped to three lines and grew the bar from 56dp to ~102dp.
 *
 * The bar is asserted through the placeholder itself: one line of
 * bodyLarge is ~24dp tall, three lines ~72dp, so a 32dp ceiling separates
 * the fixed layout from the wrapped one with margin on both sides.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en-rUS-w360dp-h800dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SearchPlaceholderTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun placeholderStaysOnOneLineWhenResetIsVisible() {
        val repo = FakeRepository(
            provider = FakeProvider(downloads = mutableListOf(sampleDownload(fileCount = 1))),
        )
        val vm = DownloadsViewModel(repo)
        rule.setContent {
            TroveTheme {
                Box(Modifier.size(360.dp, 800.dp)) {
                    DownloadsScreen(vm, onOpenSettings = {}, onAdd = {}, onPlayUrl = { _, _, _ -> })
                }
            }
        }
        rule.waitForIdle()
        // A non-default sort makes the search dirty, so Reset appears and
        // the search field shrinks — the exact state in the user report.
        rule.runOnIdle { vm.setSort(DownloadSort.OLDEST) }
        rule.waitForIdle()
        rule.onNodeWithText(rule.activity.getString(R.string.reset_search)).assertIsDisplayed()

        val placeholder = rule.onNodeWithText(
            rule.activity.getString(R.string.search_downloads),
            useUnmergedTree = true,
        )
        placeholder.assertIsDisplayed()
        val height = with(rule.density) {
            placeholder.fetchSemanticsNode().boundsInRoot.height.toDp().value
        }
        assertTrue(
            "search placeholder wrapped (height ${height}dp); it must stay on one line with Reset visible",
            height < 32f,
        )
    }
}
