package app.trove.debrid

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import app.trove.debrid.core.DebridService
import app.trove.debrid.core.update.LatestRelease
import app.trove.debrid.core.update.UpdateChannel
import app.trove.debrid.ui.settings.SettingsScreen
import app.trove.debrid.ui.settings.SettingsViewModel
import app.trove.debrid.ui.settings.UpdateViewModel
import app.trove.debrid.ui.theme.TroveTheme
import app.trove.debrid.update.InstallOutcome
import app.trove.debrid.update.UpdateBackend
import app.trove.debrid.update.UpdateCheckResult
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en-rUS-w400dp-h800dp")
class ProvidersVisualListTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun listShowsSixInitialsAndAccountStatus() {
        openProviders()
        val initials = mapOf(
            DebridService.TORBOX to "T",
            DebridService.REAL_DEBRID to "R",
            DebridService.ALLDEBRID to "A",
            DebridService.PREMIUMIZE to "P",
            DebridService.OFFCLOUD to "O",
            DebridService.DEBRID_LINK to "D",
        )
        initials.forEach { (service, initial) ->
            val signedIn = service in setOf(DebridService.TORBOX, DebridService.REAL_DEBRID)
            val status = if (signedIn) "Signed in" else "Not signed in"
            row(service).assertHasClickAction().assert(hasText(status, substring = signedIn))
            rule.onNodeWithContentDescription("Provider initial $initial", useUnmergedTree = true)
                .assert(hasAnyDescendant(hasText(initial)))
        }
        row(DebridService.TORBOX).assert(hasText("Signed in · TorBox (Essential)")).assert(hasText("Active"))
        row(DebridService.REAL_DEBRID).assert(hasText("Signed in · Real-Debrid (Premium)"))
        rule.onAllNodesWithText("Test & save").assertCountEquals(0)
    }

    @Test fun detailSetsActiveAndBothBackActionsReturnToTheList() {
        val realDebrid = DebridService.REAL_DEBRID
        val base = openProviders()

        row(realDebrid).performScrollTo().performClick()
        rule.onNodeWithText(rule.activity.getString(R.string.rd_replace_token)).assertExists()
        rule.onNodeWithText("Set active").performClick()

        rule.onNodeWithText("Active provider").assertExists()
        assertEquals(realDebrid, base.activeService())
        rule.onNodeWithContentDescription("Back to settings").performClick()
        row(realDebrid).assert(hasText("Active"))

        row(DebridService.OFFCLOUD).performScrollTo().performClick()
        rule.onNodeWithText(rule.activity.getString(R.string.oc_key_help))
            .assertExists()
        rule.onAllNodesWithText("Set active").assertCountEquals(0)
        rule.runOnIdle { rule.activity.onBackPressedDispatcher.onBackPressed() }
        row(DebridService.OFFCLOUD).assert(hasText("Not signed in"))
    }

    private fun row(service: DebridService) = rule.onNodeWithTag("provider_row_${service.name}")

    private fun openProviders(): FakeRepository {
        val base = FakeRepository().apply { savedKey = "test-key" }
        runBlocking {
            base.saveTestCredential(DebridService.REAL_DEBRID, "test-token").getOrThrow()
            base.setActiveService(DebridService.TORBOX)
        }
        val updates = UpdateViewModel(object : UpdateBackend {
            override val installedName = "test"
            override fun canInstall() = false
            override fun openSettings() = false
            override suspend fun check(channel: UpdateChannel): UpdateCheckResult = error("Unexpected update check")
            override suspend fun download(release: LatestRelease, progress: (Long, Long?) -> Unit): File =
                error("Unexpected update download")
            override suspend fun install(file: File, release: LatestRelease, operation: String): InstallOutcome =
                error("Unexpected update install")
        })
        val settings = SettingsViewModel(base)
        rule.setContent { TroveTheme { SettingsScreen(settings, updates, onBack = {}) } }
        rule.onNodeWithTag("settings_group_providers").performClick()
        return base
    }
}
