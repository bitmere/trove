package app.trove.debrid.ui.downloads

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import app.trove.debrid.R
import app.trove.debrid.ui.theme.TroveTheme
import org.junit.Rule
import org.junit.Test

class SearchSyncStatusTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun statusAppearsOnlyForAnActiveSearchAndDisappearsWhenCompleteOrCleared() {
        val syncing = mutableStateOf(false)
        val query = mutableStateOf("Movie")
        val label = rule.activity.getString(R.string.searching_all_downloads)
        rule.setContent {
            TroveTheme { SearchSyncStatus(syncing.value, query.value) }
        }
        rule.onNodeWithText(label).assertDoesNotExist()
        rule.runOnIdle { syncing.value = true }
        rule.onNodeWithText(label).assertIsDisplayed()
        rule.runOnIdle { query.value = " " }
        rule.onNodeWithText(label).assertDoesNotExist()
        rule.runOnIdle { query.value = "Movie" }
        rule.onNodeWithText(label).assertIsDisplayed()
        rule.runOnIdle { syncing.value = false }
        rule.onNodeWithText(label).assertDoesNotExist()
    }
}
