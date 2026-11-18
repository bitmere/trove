package app.trove.debrid

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import app.trove.debrid.core.DownloadKind
import app.trove.debrid.ui.add.AddSheet
import app.trove.debrid.ui.add.AddViewModel
import app.trove.debrid.ui.theme.TroveTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

/** A transparent share host: only the existing add sheet, with no library underneath. */
class ShareActivity : ComponentActivity() {
    companion object {
        private val additions = MutableSharedFlow<DownloadKind>(extraBufferCapacity = 1)
        internal val completedAdds = additions.asSharedFlow()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = AppContainer(applicationContext)
        val model = ViewModelProvider(this, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                AddViewModel(container.repository) as T
        })[AddViewModel::class.java]
        // The Activity ViewModel survives rotation; do not overwrite an edited or submitted input.
        val restoreInput = model.state.value.let { it.inputText.isNotEmpty() || it.adding || it.added }
        var share by mutableStateOf<PendingShare?>(null)
        // Read the local theme before the first composition to avoid a default-theme flash.
        val repository = container.repository
        repository.loadStartupTheme()
        setContent {
            val theme by repository.theme.collectAsState()
            TroveTheme(preference = theme) {
                share?.let { input ->
                    AddSheet(
                        viewModel = model,
                        initialText = input.text.takeUnless { restoreInput },
                        initialFile = input.bytes?.takeUnless { restoreInput }?.let { Triple(it, input.fileName ?: "file", input.mimeType) },
                        onDismiss = { finish() },
                        onAdded = { kind ->
                            additions.tryEmit(kind)
                            Toast.makeText(this, R.string.download_added, Toast.LENGTH_SHORT).show()
                            finish()
                        },
                    )
                }
            }
        }
        lifecycleScope.launch {
            val input = try { receiveShare(intent) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { PendingShare(error = getString(R.string.file_read_failed)) }
            input.error?.let(model::inputError)
            share = input
        }
    }

    private suspend fun receiveShare(intent: Intent): PendingShare {
        val stream = if (android.os.Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
        }
        val uri = when (intent.action) {
            Intent.ACTION_SEND -> stream
            Intent.ACTION_VIEW -> intent.data
            else -> null
        }
        return when {
            uri?.scheme in setOf("http", "https") -> PendingShare(text = uri.toString())
            uri?.scheme == "magnet" -> PendingShare(text = uri.toString())
            uri != null -> readSharedFile(contentResolver, uri).let { it.copy(mimeType = it.mimeType ?: intent.type) }
            intent.action == Intent.ACTION_SEND -> PendingShare(text = intent.getStringExtra(Intent.EXTRA_TEXT))
            else -> PendingShare()
        }
    }
}
