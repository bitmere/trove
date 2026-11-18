package app.trove.debrid

import android.content.Intent
import android.content.ActivityNotFoundException
import android.content.ComponentName
import app.trove.debrid.core.PlaybackTarget
import app.trove.debrid.core.playbackTarget
import kotlinx.coroutines.CancellationException
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.net.toUri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import app.trove.debrid.ui.add.AddSheet
import app.trove.debrid.ui.add.AddViewModel
import app.trove.debrid.ui.downloads.DownloadsScreen
import app.trove.debrid.ui.downloads.DownloadsViewModel
import app.trove.debrid.ui.settings.SettingsScreen
import app.trove.debrid.ui.settings.UpdateViewModel
import app.trove.debrid.ui.settings.SettingsViewModel
import app.trove.debrid.ui.theme.TroveTheme

class MainActivity : ComponentActivity() {

    private val container by lazy { AppContainer(applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            TroveTheme {
                TroveApp(
                    container = container,
                    onPlayUrl = ::playUrl,
                )
            }
        }
    }

    /** Only the chosen ACTION_VIEW handler receives the bearer link.
     * Nuvio currently declares addon/auth deep links, not HTTP video playback. */
    private suspend fun playUrl(url: String, fileName: String?, askEveryTime: Boolean) {
        val target = try {
            val saved = if (askEveryTime) null else container.repository.defaultPlayer()
            playbackTarget(
                saved,
                if (saved == null) emptyList() else container.playerDiscovery.players(playbackMimeType(fileName)),
                askEveryTime,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Failure to read preferences/discover handlers must still allow playback.
            PlaybackTarget.Chooser
        }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(url.toUri(), playbackMimeType(fileName))
            addCategory(Intent.CATEGORY_DEFAULT)
            // Native players need not declare CATEGORY_BROWSABLE. Adding it
            // filters them out of the chooser even when they support video URLs.
        }
        if (target is PlaybackTarget.Direct) {
            intent.component = ComponentName(target.component.packageName, target.component.activityName)
            try {
                startActivity(intent)
                return
            } catch (_: ActivityNotFoundException) {
                // The app may have been removed since discovery. Keep the saved choice.
            } catch (_: SecurityException) {
                // A player can revoke access between discovery and launch.
            }
            intent.component = null
        }
        // Chooser failures propagate to DownloadsScreen's generic, URL-free feedback.
        startActivity(Intent.createChooser(intent, "Play with"))
    }
}

private class SimpleFactory<V : ViewModel>(private val create: () -> V) :
    ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = create() as T
}

@Composable
private fun TroveApp(
    container: AppContainer,
    onPlayUrl: suspend (String, String?, Boolean) -> Unit,
) {
    var showAdd by remember { mutableStateOf(false) }

    // Hoisted here (Activity scope) so the NavHost destinations share
    // the same instances: returning from Settings must find the same
    // Downloads model — same tab, query and scroll position — not a
    // fresh one.
    val downloadsViewModel: DownloadsViewModel = viewModel(
        factory = SimpleFactory { DownloadsViewModel(container.repository) },
    )
    val addViewModel: AddViewModel = viewModel(
        factory = SimpleFactory { AddViewModel(container.repository) },
    )
    val settingsViewModel: SettingsViewModel = viewModel(
        factory = SimpleFactory { SettingsViewModel(container.repository, container.playerDiscovery) },
    )

    val updatePreferences = androidx.compose.ui.platform.LocalContext.current.applicationContext
        .getSharedPreferences("trove_updates", android.content.Context.MODE_PRIVATE)
    val updateViewModel: UpdateViewModel = viewModel(
        factory = SimpleFactory { UpdateViewModel(container.updateBackend, preferences = updatePreferences) },
    )

    val navController = rememberNavController()

    // Refresh Downloads only when the account actually changed in
    // Settings (key saved or signed out) — never merely for viewing it.
    val accountChanged by settingsViewModel.accountChanged.collectAsState()
    androidx.compose.runtime.LaunchedEffect(accountChanged) {
        if (accountChanged) {
            downloadsViewModel.refresh()
            settingsViewModel.consumeAccountChanged()
        }
    }

    androidx.compose.runtime.LaunchedEffect(downloadsViewModel) {
        ShareActivity.completedAdds.collect { kind -> downloadsViewModel.onAdded(kind) }
    }

    // Two destinations, one back stack. System Back (gesture or key)
    // pops Settings back to Downloads; the toolbar arrow does the
    // same thing. No destination is ever torn down and rebuilt on
    // navigation, so Downloads keeps its list position.
    NavHost(
        navController = navController,
        startDestination = "downloads",
        enterTransition = {
            androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(200)) +
                androidx.compose.animation.slideInHorizontally(
                    androidx.compose.animation.core.tween(200),
                ) { it / 4 }
        },
        exitTransition = {
            androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(200)) +
                androidx.compose.animation.slideOutHorizontally(
                    androidx.compose.animation.core.tween(200),
                ) { -it / 4 }
        },
        popEnterTransition = {
            androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(200)) +
                androidx.compose.animation.slideInHorizontally(
                    androidx.compose.animation.core.tween(200),
                ) { -it / 4 }
        },
        popExitTransition = {
            androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(200)) +
                androidx.compose.animation.slideOutHorizontally(
                    androidx.compose.animation.core.tween(200),
                ) { it / 4 }
        },
    ) {
        composable("downloads") {
            DownloadsScreen(
                viewModel = downloadsViewModel,
                onOpenSettings = {
                    navController.navigate("settings") { launchSingleTop = true }
                },
                onAdd = {
                    addViewModel.reset()
                    showAdd = true
                },
                onPlayUrl = onPlayUrl,
            )
        }
        composable("settings") {
            SettingsScreen(
                viewModel = settingsViewModel,
                updateViewModel = updateViewModel,
                onBack = { navController.popBackStack() },
            )
        }
    }

    if (showAdd) {
        AddSheet(
            viewModel = addViewModel,
            initialText = null,
            initialFile = null,
            onDismiss = { showAdd = false },
            onAdded = {
                showAdd = false
                downloadsViewModel.onAdded(it)
            },
        )
    }
}
