package app.trove.debrid

import android.content.Intent
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.ClipboardManager
import android.content.SharedPreferences
import app.trove.debrid.core.PlaybackTarget
import app.trove.debrid.core.playbackTarget
import kotlinx.coroutines.CancellationException
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.net.toUri
import androidx.compose.runtime.Composable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    companion object {
        const val EXTRA_OPEN_ADD = "app.trove.debrid.OPEN_ADD"
    }

    private val container by lazy { AppContainer(applicationContext) }
    private var openAddRequested by mutableStateOf(false)
    private var clipboardOffer by mutableStateOf<String?>(null)
    private var clipboardCheckPending = false
    private var checkingClipboard = false
    private var resumed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handleAddShortcut(intent)
        // Read the local theme before the first composition to avoid a default-theme flash.
        val repository = container.repository
        repository.loadStartupTheme()
        val updatePreferences = applicationContext.getSharedPreferences("trove_updates", MODE_PRIVATE)
        setContent {
            val theme by repository.theme.collectAsState()
            TroveTheme(preference = theme) {
                TroveApp(
                    container = container,
                    updatePreferences = updatePreferences,
                    onPlayUrl = ::playUrl,
                    openAddRequested = openAddRequested,
                    onAddRequestHandled = { openAddRequested = false },
                    clipboardOffer = clipboardOffer,
                    onDismissClipboardOffer = { clipboardOffer = null },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleAddShortcut(intent)
    }

    private fun handleAddShortcut(intent: Intent) {
        if (requestsAddSheet(intent.action, intent.getBooleanExtra(EXTRA_OPEN_ADD, false))) {
            openAddRequested = true
        }
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        // Both cold launches and singleTop intent deliveries go through onResume.
        clipboardCheckPending = true
        checkClipboardWhenFocused()
    }

    override fun onPause() {
        resumed = false
        clipboardCheckPending = false
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) checkClipboardWhenFocused()
    }

    private fun checkClipboardWhenFocused() {
        if (!clipboardCheckPending || !hasWindowFocus() || checkingClipboard) return
        clipboardCheckPending = false
        if (clipboardOffer != null) return

        checkingClipboard = true
        lifecycleScope.launch {
            try {
                val repository = container.repository
                if (!repository.loadClipboardOffersEnabled() || !resumed) return@launch
                if (!hasWindowFocus()) {
                    clipboardCheckPending = true
                    return@launch
                }
                val text = withContext(Dispatchers.IO) {
                    // Android 12+ may show a system toast on clipboard reads. This is expected.
                    val clip = getSystemService(ClipboardManager::class.java)?.primaryClip
                    if (clip == null || clip.itemCount == 0) null else clip.getItemAt(0).text?.toString()
                }
                if (resumed && !text.isNullOrBlank() && repository.claimClipboardOffer(text)) {
                    clipboardOffer = text
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Clipboard and preference failures leave this optional offer hidden.
            } finally {
                checkingClipboard = false
                checkClipboardWhenFocused()
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

internal fun requestsAddSheet(action: String?, openAdd: Boolean): Boolean =
    action == Intent.ACTION_MAIN && openAdd

private class SimpleFactory<V : ViewModel>(private val create: () -> V) :
    ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = create() as T
}

@Composable
private fun TroveApp(
    container: AppContainer,
    updatePreferences: SharedPreferences,
    onPlayUrl: suspend (String, String?, Boolean) -> Unit,
    openAddRequested: Boolean,
    onAddRequestHandled: () -> Unit,
    clipboardOffer: String?,
    onDismissClipboardOffer: () -> Unit,
) {
    var showAdd by remember { mutableStateOf(false) }
    var addInitialText by remember { mutableStateOf<String?>(null) }

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
    fun openAdd(initialText: String?) {
        addViewModel.reset()
        addInitialText = initialText
        showAdd = true
    }

    androidx.compose.runtime.LaunchedEffect(openAddRequested) {
        if (openAddRequested) {
            if (!showAdd) openAdd(null)
            onAddRequestHandled()
        }
    }

    val settingsViewModel: SettingsViewModel = viewModel(
        factory = SimpleFactory { SettingsViewModel(container.repository, container.playerDiscovery) },
    )

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
                onAdd = { openAdd(null) },
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

    if (clipboardOffer != null && !showAdd) {
        AlertDialog(
            onDismissRequest = onDismissClipboardOffer,
            title = { Text(stringResource(R.string.clipboard_offer_title)) },
            text = { Text(stringResource(R.string.clipboard_offer_message)) },
            confirmButton = {
                TextButton(onClick = {
                    openAdd(clipboardOffer)
                    onDismissClipboardOffer()
                }) {
                    Text(stringResource(R.string.add_download))
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissClipboardOffer) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    if (showAdd) {
        AddSheet(
            viewModel = addViewModel,
            initialText = addInitialText,
            initialFile = null,
            onDismiss = {
                showAdd = false
                addInitialText = null
            },
            onAdded = {
                showAdd = false
                addInitialText = null
                downloadsViewModel.onAdded(it)
            },
        )
    }
}
