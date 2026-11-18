package app.trove.debrid

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.test.performSemanticsAction
import org.junit.Assert.assertEquals
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import app.trove.debrid.core.*
import app.trove.debrid.ui.downloads.DownloadCover
import org.junit.Rule
import org.junit.Test

class DownloadCoverTest {
    @get:Rule val rule = createComposeRule()
    private val download = DebridDownload("synthetic", DebridService.TORBOX,
        "Baththa.2026.1080p.WEB-Rip.HEVC-GROUP.mkv", 100L, DownloadStatus.READY, 1f,
        listOf(DebridFile("synthetic-file", "Baththa.mkv", 100L)), DownloadKind.WEB)

    @Test fun parsedHeaderContainsIdentityKindAndStatusWithoutFormat() {
        rule.setContent { MaterialTheme { DownloadCover(download) } }
        rule.onNodeWithText("Baththa").assertExists()
        rule.onNodeWithText("2026").assertExists()
        rule.onNodeWithText("Web · Ready").assertExists()
        rule.onNodeWithText("MKV").assertDoesNotExist()
    }

    @Test fun resolvedHeaderUsesMetadataTemplate() {
        rule.setContent { MaterialTheme {
            DownloadCover(download, MediaMetadata("Synthetic Resolved Title", 2026, "movie"))
        } }
        rule.onNodeWithText("Synthetic Resolved Title").assertExists()
        val layouts = mutableListOf<TextLayoutResult>()
        rule.onNodeWithText("Synthetic Resolved Title").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals(2, layouts.single().layoutInput.maxLines)
        assertEquals(TextOverflow.Ellipsis, layouts.single().layoutInput.overflow)
        rule.onNodeWithText("2026 · Movie").assertExists()
        rule.onNodeWithText("Web · Ready").assertExists()
        rule.onNodeWithText("MKV").assertDoesNotExist()
    }
}
