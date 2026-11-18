package app.trove.debrid.ui.settings

import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import app.trove.debrid.core.update.LatestRelease
import app.trove.debrid.core.update.UpdateChannel
import app.trove.debrid.ui.theme.TroveTheme
import app.trove.debrid.update.UpdateIssue
import app.trove.debrid.update.AndroidUpdateBackend
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

/** DEVICE ONLY: written for phone Compose instrumentation; UNRUN in this checkout. */
class UpdateSectionTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val release = LatestRelease("0.2.0", 21, "https://github.com/bitmere/trove/releases/download/v0.2.0/Trove-v0.2.0.apk", "a".repeat(64), (1..200).joinToString("\n") { "Release note $it" }, 2048)
    private val file = File("fixture.apk")
    @Test fun channelChangesWithoutChecking() {
        val selected = mutableStateOf(UpdateChannel.STABLE)
        var checks = 0
        rule.setContent { TroveTheme {
            UpdateSection(UpdateUiState.Idle, "0.1.18", { checks++ }, {}, {}, {}, {}, {},
                selected.value, { selected.value = it })
        } }
        rule.onNodeWithText("Beta").performClick()
        rule.runOnIdle {
            assertEquals(UpdateChannel.BETA, selected.value)
            assertEquals(0, checks)
        }
    }
    @Test fun channelSurvivesViewModelRecreationWithoutStartingACheck() {
        val context = rule.activity.applicationContext
        val prefs = context.getSharedPreferences("trove_update_channel_fixture", android.content.Context.MODE_PRIVATE)
        val stores = listOf(ViewModelStore(), ViewModelStore())
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                UpdateViewModel(AndroidUpdateBackend(context), preferences = prefs) as T
        }
        rule.runOnUiThread {
            prefs.edit().clear().commit()
            try {
                val first = ViewModelProvider(stores.first(), factory)[UpdateViewModel::class.java]
                assertEquals(UpdateChannel.STABLE, first.channel.value)
                first.selectChannel(UpdateChannel.BETA)
                stores.first().clear()
                val restored = ViewModelProvider(stores.last(), factory)[UpdateViewModel::class.java]
                assertEquals(UpdateChannel.BETA, restored.channel.value)
                restored.onResume()
                assertEquals(UpdateUiState.Idle, restored.state.value)
            } finally {
                stores.forEach { it.clear() }
                prefs.edit().clear().commit()
            }
        }
    }
    @Test fun everyStateIsDistinguishableAndActionsAreAccessible() {
        val state = mutableStateOf<UpdateUiState>(UpdateUiState.Idle)
        var checks = 0
        var downloads = 0
        var installs = 0
        var cancels = 0
        var retries = 0
        var settings = 0
        rule.setContent { TroveTheme { Column(Modifier.verticalScroll(rememberScrollState())) {
            UpdateSection(state.value, "0.1.14", { checks++ }, { downloads++ }, { cancels++ }, { installs++ }, { retries++ }, { settings++ })
        } } }
        rule.onNodeWithText("Check for updates").assertHasClickAction().performClick()
        assertEquals(1, checks)
        fun show(value: UpdateUiState, text: String) {
            rule.runOnIdle { state.value = value }
            rule.onNodeWithText(text).assertExists()
        }
        show(UpdateUiState.Checking, "Checking for updates…")
        show(UpdateUiState.NoReleases, "No releases are available in this channel.")
        show(UpdateUiState.UpToDate, "Trove is up to date.")
        show(UpdateUiState.Available(release), "Trove 0.2.0 is available.")
        rule.onNodeWithText("Download update").assertHasClickAction().performClick(); assertEquals(1, downloads)
        show(UpdateUiState.Downloading(release, 1024, 2048), "Downloading update · 1 KB")
        rule.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)).assertExists()
        rule.onNodeWithText("Cancel").performClick(); assertEquals(1, cancels)
        show(UpdateUiState.ReadyToInstall(release, file), "Trove 0.2.0 is verified and ready to install.")
        rule.onNodeWithText("Install").performClick(); assertEquals(1, installs)
        show(UpdateUiState.Installing, "Starting Android installer…")
        show(UpdateUiState.WaitingForConfirmation(release, file), "Waiting for Android installation confirmation. If you cancelled, you can install again.")
        show(UpdateUiState.Installed, "Update installed.")
        show(UpdateUiState.NeedsUnknownSources(release, file), "Allow Trove to install updates in Android Settings. When you return, choose Install to continue.")
        rule.onNodeWithText("Allow installs in Settings").performClick(); assertEquals(1, settings)
        // Simulate a permission return: the UI must offer Install without invoking it.
        show(UpdateUiState.ReadyToInstall(release, file), "Trove 0.2.0 is verified and ready to install.")
        assertEquals(1, installs)
        show(UpdateUiState.Error(UpdateIssue.INSTALL, release to file), "Android could not install the update. Try again to retry installation.")
        rule.onNodeWithText("Try again").performClick(); assertEquals(1, retries)
    }
    @Test fun notesScrollBackRestoresTriggerAndReopeningKeepsScroll() {
        rule.setContent { TroveTheme {
            UpdateSection(UpdateUiState.Available(release), "0.1.14", {}, {}, {}, {}, {}, {})
        } }
        rule.onNodeWithText("Release notes · 0.2.0").performClick()
        rule.onNodeWithTag("update_notes_scroll").assertIsDisplayed().performTouchInput { swipeUp() }
        rule.waitForIdle()
        val offset = rule.onNodeWithTag("update_notes_scroll").fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
        assertTrue(offset > 0)
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        rule.onNodeWithTag("update_notes_scroll").assertDoesNotExist()
        rule.onNodeWithText("Release notes · 0.2.0").assertIsFocused().performClick()
        val restored = rule.onNodeWithTag("update_notes_scroll").fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
        assertEquals(offset, restored, 1f)
        rule.onNodeWithText("Close").performClick()
        rule.onNodeWithText("Download update").assertIsDisplayed()
    }
    @Test fun signerMismatchExplainsCleanInstallAndNeverOffersUninstallAction() {
        rule.setContent { TroveTheme {
            UpdateSection(UpdateUiState.Error(UpdateIssue.SIGNER), "0.1.14", {}, {}, {}, {}, {}, {})
        } }
        rule.onNodeWithText("Clean-install instructions").assertHasClickAction().performClick()
        rule.onNodeWithText("Uninstalling removes local settings", substring = true).assertExists()
        rule.onNodeWithText("Close").performClick()
        rule.onNodeWithText("Clean-install instructions").assertIsFocused()
    }
    @Test fun cleanInstallHelpClosesWhenTheSignerErrorIsGone() {
        val state = mutableStateOf<UpdateUiState>(UpdateUiState.Error(UpdateIssue.SIGNER))
        rule.setContent { TroveTheme {
            UpdateSection(state.value, "0.1.14", {}, {}, {}, {}, {}, {})
        } }
        rule.onNodeWithText("Clean-install instructions").performClick()
        rule.runOnIdle { state.value = UpdateUiState.Idle }
        rule.onNodeWithText("Close").assertDoesNotExist()
        rule.onNodeWithText("Check for updates").assertIsDisplayed()
    }
}
