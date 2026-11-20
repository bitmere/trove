package app.trove.debrid

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.test.performSemanticsAction
import org.junit.Assert.assertEquals
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
// assertExists / assertDoesNotExist are members of SemanticsNodeInteraction
// in this Compose version — no androidx.compose.ui.test import needed.
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import app.trove.debrid.core.*
import app.trove.debrid.ui.downloads.DownloadCover
import app.trove.debrid.ui.downloads.DownloadRow
import org.junit.Rule
import org.junit.Test

class DownloadCoverTest {
    @get:Rule val rule = createComposeRule()
    private val download = DebridDownload("synthetic", DebridService.TORBOX,
        "Baththa.2026.1080p.WEB-Rip.HEVC-GROUP.mkv", 100L, DownloadStatus.READY, 1f,
        listOf(DebridFile("synthetic-file", "Baththa.mkv", 100L)), DownloadKind.WEB)

    @Test fun parsedHeaderContainsIdentityAndStatusSizeWithoutKindOrFormat() {
        rule.setContent { MaterialTheme { DownloadCover(download) } }
        rule.onNodeWithText("Baththa").assertExists()
        rule.onNodeWithText("2026 · 1080p").assertExists()
        rule.onNodeWithText("Ready · 100 B").assertExists()
        rule.onNodeWithText("Web", substring = true).assertDoesNotExist()
        rule.onNodeWithText("MKV").assertDoesNotExist()
    }

    @Test fun resolvedHeaderUsesMetadataTemplate() {
        rule.setContent { MaterialTheme {
            DownloadCover(download, metadata = MediaMetadata("Synthetic Resolved Title", 2026, "movie"))
        } }
        rule.onNodeWithText("Synthetic Resolved Title").assertExists()
        val layouts = mutableListOf<TextLayoutResult>()
        rule.onNodeWithText("Synthetic Resolved Title").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals(2, layouts.single().layoutInput.maxLines)
        assertEquals(TextOverflow.Ellipsis, layouts.single().layoutInput.overflow)
        rule.onNodeWithText("2026 · Movie · 1080p").assertExists()
        rule.onNodeWithText("Ready · 100 B").assertExists()
        rule.onNodeWithText("Web", substring = true).assertDoesNotExist()
        rule.onNodeWithText("MKV").assertDoesNotExist()
    }

    @Test fun readyRowShowsOneTitleAndAnIconPlayAction() {
        rule.setContent { MaterialTheme {
            DownloadRow(download, resolving = false, playEnabled = true,
                onPlay = {}, onOpen = {}, onLongPress = {})
        } }

        rule.onAllNodesWithText("Baththa", useUnmergedTree = true).assertCountEquals(1)
        rule.onNodeWithText(download.name, useUnmergedTree = true).assertDoesNotExist()
        rule.onNodeWithContentDescription("Play").assertHasClickAction()
        rule.onNodeWithText("Play", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun transferRowsKeepProgressAndErrorWithoutPlay() {
        val current = mutableStateOf(download.copy(status = DownloadStatus.DOWNLOADING, progress = 0.5f))
        rule.setContent { MaterialTheme {
            DownloadRow(current.value, resolving = false, playEnabled = true,
                onPlay = {}, onOpen = {}, onLongPress = {})
        } }

        rule.onNodeWithText("50%", useUnmergedTree = true).assertExists()
        rule.onNodeWithContentDescription("Play").assertDoesNotExist()
        rule.onNodeWithText("Downloading · 100 B", useUnmergedTree = true).assertExists()

        rule.runOnIdle { current.value = download.copy(status = DownloadStatus.ERROR) }

        rule.onNodeWithText("This download needs attention. Refresh to check its status.",
            useUnmergedTree = true).assertExists()
        rule.onNodeWithText("50%", useUnmergedTree = true).assertDoesNotExist()
        rule.onNodeWithContentDescription("Play").assertDoesNotExist()
    }

    @Test fun renamedDownloadUpdatesParsedTitle() {
        val current = mutableStateOf(download)
        rule.setContent { MaterialTheme { DownloadCover(current.value) } }
        rule.onNodeWithText("Baththa").assertExists()

        rule.runOnIdle {
            current.value = download.copy(name = "Arrival.2016.1080p.WEB-Rip.HEVC-GROUP.mkv")
        }

        rule.onNodeWithText("Arrival").assertExists()
        rule.onNodeWithText("Baththa").assertDoesNotExist()
    }
}
