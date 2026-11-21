package app.trove.debrid

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import app.trove.debrid.core.*
import app.trove.debrid.ui.downloads.CandidateRow
import app.trove.debrid.ui.downloads.DownloadDetailsSheet
import app.trove.debrid.ui.downloads.DownloadDetailsState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalMaterial3Api::class)
class DownloadDetailsSheetTest {
    @get:Rule val rule = createComposeRule()

    @Test fun manualFieldAndTitleRemainVisibleWhenTyping() {
        val download = DebridDownload("synthetic", DebridService.TORBOX,
            "Example.2026.mkv", 100L, DownloadStatus.READY, 1f,
            emptyList(), DownloadKind.WEB)
        rule.setContent {
            MaterialTheme {
                DownloadDetailsSheet(download,
                    DownloadDetailsState(null, emptyList(), false, false, false, null, null,
                        tmdbConfigured = true),
                    rememberModalBottomSheetState(skipPartiallyExpanded = true),
                    dismissing = false, onClose = {}, onPlay = {}, onPlayWith = {}, onDelete = {})
            }
        }
        rule.onNodeWithText("Example", useUnmergedTree = true).assertIsDisplayed()
        val field = rule.onNodeWithTag("manual_tmdb_link_field")
        field.performScrollTo().assertIsDisplayed().performClick().performTextInput("the odyssey")
        field.assertIsDisplayed().assertTextContains("the odyssey")
        // The header remains reachable in the same list while the field is focused.
        rule.onNodeWithText("Example", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
    }

    @Test fun candidateShowsPlaceholderTitleFactsAndSynopsisAndRemainsTappable() {
        var picks = 0
        rule.setContent {
            MaterialTheme {
                CandidateRow(MediaCandidate(null, "The Odyssey", 2026, "movie", tmdbId = 1,
                    overview = "A voyage home."), enabled = true, onClick = { picks++ })
            }
        }
        rule.onNodeWithTag("candidate_poster", useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithText("The Odyssey", useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithText("2026 · Movie · TMDB", useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithText("A voyage home.", useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithText("The Odyssey").assertHasClickAction().performClick()
        rule.runOnIdle { assertEquals(1, picks) }
    }

    @Test fun candidateWithoutSynopsisRetainsPlaceholderAndDisabledSelection() {
        rule.setContent {
            MaterialTheme {
                CandidateRow(MediaCandidate("tt1", "The Odyssey", null, "series"),
                    enabled = false, onClick = { error("Disabled candidate selected") })
            }
        }
        rule.onNodeWithTag("candidate_poster", useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithText("The Odyssey").assertIsNotEnabled()
        rule.onNodeWithText("Series", useUnmergedTree = true).assertIsDisplayed()
    }
}
