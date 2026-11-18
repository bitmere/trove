package app.trove.debrid.ui.downloads

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.trove.debrid.R
import app.trove.debrid.ui.theme.TroveTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class BatchDeleteBarTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun countControlsDeleteAndButtonsInvokeTheirCallbacks() {
        val selectedCount = mutableIntStateOf(0)
        var deletes = 0
        var cancels = 0
        val resources = rule.activity.resources
        rule.setContent {
            TroveTheme {
                BatchDeleteBar(
                    selectedCount = selectedCount.intValue,
                    onDelete = { deletes++ },
                    onCancel = { cancels++ },
                )
            }
        }

        rule.onNodeWithText(resources.getQuantityString(R.plurals.delete_selected_count, 0, 0))
            .assertIsNotEnabled()
        rule.runOnIdle { selectedCount.intValue = 2 }
        rule.onNodeWithText(resources.getQuantityString(R.plurals.delete_selected_count, 2, 2))
            .performClick()
        rule.onNodeWithText(resources.getString(R.string.cancel)).performClick()
        rule.runOnIdle {
            assertEquals(1, deletes)
            assertEquals(1, cancels)
        }
    }
}
