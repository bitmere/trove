package app.trove.debrid.ui.downloads

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.trove.debrid.core.DebridDownload
import app.trove.debrid.core.DebridError
import app.trove.debrid.core.DebridProvider
import app.trove.debrid.core.DownloadKind
import app.trove.debrid.core.DownloadStatus
import app.trove.debrid.core.DownloadSort
import app.trove.debrid.core.sortDownloads
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import app.trove.debrid.data.TroveRepository
import app.trove.debrid.data.defaultPreferWords
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import java.net.URI

data class DownloadsUiState(
    val metadata: Map<Pair<DownloadKind, String>, app.trove.debrid.core.MediaMetadata> = emptyMap(),
    val loading: Boolean = true,
    /** A refresh of an already-loaded list: rows stay visible and
     *  only the pull indicator shows. Distinct from [loading], the
     *  initial full-screen spinner. */
    val refreshing: Boolean = false,
    val signedOut: Boolean = false,
    val downloads: List<DebridDownload> = emptyList(),
    /** "Essential" etc., from the connected account; null when signed out. */
    val accountLabel: String? = null,
    val tab: DownloadKind = DownloadKind.TORRENT,
    val query: String = "",
    val sort: DownloadSort = DownloadSort.NEWEST,
    /** Release words that rank matching downloads first; loaded from Settings. */
    val preferWords: List<String> = defaultPreferWords,
    /** When true the details sheet shows clutter files (.nfo, samples, covers). */
    val showClutterFiles: Boolean = false,
    val error: String? = null,
    val loadError: String? = null,
    val authenticationRequired: Boolean = false,
    /** One-shot: a presigned stream URL for the Activity to open. */
    val playUrl: String? = null,
    val playFileName: String? = null,
    val askEveryTime: Boolean = false,
    val resolvingDownload: Pair<DownloadKind, String>? = null,
    val resolvingFileId: String? = null,
    /** Download details, with a file choice for ready downloads. */
    val filePicker: DebridDownload? = null,
    val addedConfirmation: DownloadKind? = null,
    val deleteConfirm: DebridDownload? = null,
) {
    override fun toString(): String = "DownloadsUiState([redacted])"
}

class DownloadsViewModel(
    private val repository: TroveRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(DownloadsUiState())
    val state: StateFlow<DownloadsUiState> = _state.asStateFlow()

    private var refreshJob: Job? = null
    private var refreshVersion = 0
    private var libraryProvider: DebridProvider? = null
    private var fileLookupJob: Job? = null
    private var playbackJob: Job? = null

    private var sortChanged = false
    private val sortWrites = Mutex()

    init {
        viewModelScope.launch {
            val savedSort = repository.loadDownloadSort()
            val preferWords = repository.loadPreferWords()
            val showClutterFiles = repository.loadShowClutterFiles()
            if (!sortChanged) _state.update { it.copy(sort = savedSort) }
            _state.update { it.copy(preferWords = preferWords, showClutterFiles = showClutterFiles) }
        }
        refresh()
    }

    fun refresh() = refresh(showIndicator = true, fresh = false)

    /** `fresh` bypasses the provider's server-side list cache and is
     *  only for user-initiated refreshes (pull-to-refresh, after Add):
     *  every fresh load costs uncached calls against the provider's
     *  rate limit. It is required (no default) on this overload so the
     *  no-arg entry above stays the only overload that adapts to
     *  `() -> Unit` for `viewModel::refresh` references. */
    fun refresh(showIndicator: Boolean = true, fresh: Boolean) {
        refreshJob?.cancel()
        val version = ++refreshVersion
        // Set feedback synchronously, then do all account/Keystore reads in
        // the repository's suspending IO path, after construction can return.
        val initial = _state.value.downloads.isEmpty() && _state.value.loading
        _state.update { it.copy(loading = initial, refreshing = !initial && showIndicator, error = null) }
        refreshJob = viewModelScope.launch {
            try {
                val connection = repository.loadConnection()
                if (version != refreshVersion) return@launch
                // Cheap prefs reads: keep prefer words and the clutter toggle
                // current after the user changes them in Settings.
                val preferWords = repository.loadPreferWords()
                val showClutterFiles = repository.loadShowClutterFiles()
                if (version != refreshVersion) return@launch
                _state.update { it.copy(preferWords = preferWords, showClutterFiles = showClutterFiles) }
                val provider = connection.provider
                val accountLabel = connection.account?.label?.substringAfter('(')?.trimEnd(')')
                if (libraryProvider !== provider) {
                    libraryProvider = null
                    fileLookupJob?.cancel()
                    playbackJob?.cancel()
                    _state.update {
                        it.copy(downloads = emptyList(), metadata = emptyMap(), accountLabel = null,
                            filePicker = null, deleteConfirm = null, playUrl = null, playFileName = null,
                            askEveryTime = false, resolvingDownload = null, resolvingFileId = null)
                    }
                }
                if (provider == null) {
                    _state.update {
                        it.copy(
                            loading = false, refreshing = false, signedOut = true,
                            downloads = emptyList(), metadata = emptyMap(), accountLabel = null, loadError = null,
                            authenticationRequired = false,
                            filePicker = null, playUrl = null, playFileName = null,
                        )
                    }
                    return@launch
                }
                val list = repository.listDownloads(provider, bypassCache = fresh).getOrThrow()
                if (version != refreshVersion) return@launch
                libraryProvider = provider
                val currentNames = list.associate { (it.kind to it.id) to it.name }
                _state.update {
                    val oldNames = it.downloads.associate { row -> (row.kind to row.id) to row.name }
                    it.copy(
                        loading = false, refreshing = false, signedOut = false,
                        downloads = list,
                        metadata = it.metadata.filterKeys { key ->
                            key in currentNames && currentNames[key] == oldNames[key]
                        }, loadError = null, authenticationRequired = false,
                        accountLabel = accountLabel,
                    )
                }
                // Rows are already visible. Sequential enrichment bounds network and memory.
                for (download in list) {
                    // Retained successes already match this row's name and identity.
                    if ((download.kind to download.id) in _state.value.metadata) continue
                    val query = app.trove.debrid.core.parseMediaName(download.name)
                    val metadata = try { repository.resolveMetadata(query) }
                        catch (e: CancellationException) { throw e }
                        catch (_: Exception) { null }
                        ?: continue
                    if (version != refreshVersion) return@launch
                    _state.update { it.copy(metadata = it.metadata + ((download.kind to download.id) to metadata)) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (version != refreshVersion) return@launch
                _state.update {
                    it.copy(
                        loading = false, refreshing = false, signedOut = false,
                        error = userMessage(e, "Couldn't load downloads. Try again or check Settings."),
                        loadError = userMessage(e, "Couldn't load downloads. Try again or check Settings."),
                        authenticationRequired = e is DebridError.AuthInvalid,
                    )
                }
            }
        }
    }

    fun onAdded(kind: DownloadKind) {
        _state.update { it.copy(addedConfirmation = kind) }
        refresh(showIndicator = false, fresh = true)
    }

    fun consumeAddedConfirmation() {
        _state.update { it.copy(addedConfirmation = null) }
    }

    fun setTab(tab: DownloadKind) {
        _state.update { it.copy(tab = tab) }
    }

    fun setQuery(query: String) {
        _state.update { it.copy(query = query) }
    }

    fun setSort(sort: DownloadSort) {
        sortChanged = true
        _state.update { it.copy(sort = sort) }
        viewModelScope.launch {
            sortWrites.withLock { repository.saveDownloadSort(sort) }
        }
    }

    /** Back to the default search: empty query, Newest first (persisted).
     *  The tab is navigation, not search state, so it is left alone. */
    fun resetSearch() {
        setQuery("")
        setSort(DownloadSort.NEWEST)
    }

    /** Each pager page filters its own kind, including while it is offscreen. */
    fun visibleDownloads(state: DownloadsUiState, tab: DownloadKind = state.tab): List<DebridDownload> {
        val byTab = state.downloads.filter { it.kind == tab }
        val q = state.query.trim()
        val filtered = if (q.isEmpty()) byTab else byTab.filter { it.name.contains(q, ignoreCase = true) }
        return sortDownloads(filtered, state.sort, state.preferWords)
    }

    fun openDownload(download: DebridDownload) {
        if (_state.value.resolvingDownload != null || _state.value.playUrl != null) return
        if (download.files.isNotEmpty()) {
            _state.update { it.copy(filePicker = download) }
        } else {
            loadFiles(download, autoPlay = false)
        }
    }

    /** A click or sheet animation can finish after the library has been replaced. */
    internal fun isCurrentDownload(download: DebridDownload): Boolean {
        val current = _state.value
        return current.filePicker === download || current.downloads.any { it === download }
    }

    private fun loadFiles(download: DebridDownload, autoPlay: Boolean) {
        _state.update { it.copy(resolvingDownload = download.kind to download.id,
            filePicker = if (autoPlay) it.filePicker else download, error = null) }
        val source = libraryProvider
        fileLookupJob = viewModelScope.launch {
            try {
                val provider = requireCurrentProvider(source)
                val detail = if (provider is app.trove.debrid.core.TorBoxProvider) {
                    provider.getDownload(download.id, download.kind)
                } else {
                    provider.getDownload(download.id)
                }.getOrThrow()
                ensureActive()
                if (autoPlay && detail.files.isNotEmpty()) {
                    _state.update { it.copy(resolvingDownload = null) }
                    play(detail)
                }
                else _state.update {
                    it.copy(resolvingDownload = null,
                        filePicker = if (autoPlay || it.filePicker != null) detail else null,
                        error = if (autoPlay) "No files are available yet. Refresh and try again." else null)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ensureActive()
                _state.update { it.copy(resolvingDownload = null, error = userMessage(e, "Couldn't load download files"),
                    authenticationRequired = e is DebridError.AuthInvalid) }
            }
        }
    }

    fun play(download: DebridDownload) {
        if (download.status != DownloadStatus.READY || _state.value.resolvingDownload != null || _state.value.playUrl != null) return
        when {
            download.files.isEmpty() -> loadFiles(download, autoPlay = true)
            download.files.size == 1 -> playFile(download, download.files.first().id)
            else -> _state.update { it.copy(filePicker = download) }
        }
    }

    fun playFile(download: DebridDownload, fileId: String, askEveryTime: Boolean = false) {
        if (download.status != DownloadStatus.READY || _state.value.resolvingDownload != null || _state.value.playUrl != null) return
        if (download.files.none { it.id == fileId }) return
        _state.update { it.copy(resolvingDownload = download.kind to download.id, resolvingFileId = fileId,
            error = null, playUrl = null, authenticationRequired = false) }
        val source = libraryProvider
        playbackJob = viewModelScope.launch {
            val result = try {
                val provider = requireCurrentProvider(source)
                repository.play(provider, download, fileId).mapCatching { stream ->
                    val uri = URI(stream.url)
                    require(uri.scheme in listOf("https", "http") && !uri.host.isNullOrBlank())
                    stream
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
            ensureActive()
            result.exceptionOrNull()?.let { if (it is CancellationException) throw it }
            result.fold(
                onSuccess = { _state.update { s -> s.copy(playUrl = it.url, askEveryTime = askEveryTime, playFileName = download.files.first { file -> file.id == fileId }.name,
                    resolvingDownload = null, resolvingFileId = null) } },
                onFailure = { e ->
                    _state.update {
                        it.copy(resolvingDownload = null, resolvingFileId = null, error = userMessage(e, "Couldn't start playback"),
                            authenticationRequired = e is DebridError.AuthInvalid)
                    }
                },
            )
        }
    }

    private fun userMessage(error: Throwable, fallback: String): String = when (error) {
        is DebridError.AuthInvalid -> "TorBox rejected your API key. Open Settings and re-enter it, then tap Test & save."
        is DebridError.Provider -> if (error.detail.isBlank()) fallback else error.message ?: fallback
        is DebridError -> error.message ?: fallback
        else -> fallback
    }

    fun playbackError(message: String) {
        _state.update { it.copy(error = message) }
    }

    fun dismissFilePicker() {
        val current = _state.value
        val loadingFiles = current.filePicker?.files?.isEmpty() == true &&
            current.resolvingDownload != null && current.resolvingFileId == null
        if (loadingFiles) fileLookupJob?.cancel()
        _state.update { it.copy(filePicker = null,
            resolvingDownload = if (loadingFiles) null else it.resolvingDownload) }
    }

    fun requestDelete(download: DebridDownload) {
        if (_state.value.resolvingDownload != null || _state.value.playUrl != null) return
        _state.update { it.copy(deleteConfirm = download, filePicker = null) }
    }

    fun confirmDelete() {
        val download = _state.value.deleteConfirm ?: return
        val source = libraryProvider
        _state.update { it.copy(deleteConfirm = null) }
        viewModelScope.launch {
            val result = try {
                repository.delete(requireCurrentProvider(source), download)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
            if (libraryProvider !== source) return@launch
            result.fold(
                onSuccess = { refresh() },
                onFailure = { e ->
                    _state.update {
                        it.copy(error = userMessage(e, "Couldn't delete"),
                            authenticationRequired = e is DebridError.AuthInvalid)
                    }
                },
            )
        }
    }

    private suspend fun requireCurrentProvider(source: DebridProvider?): DebridProvider {
        val current = repository.loadConnection().provider
        check(current != null && source === current && libraryProvider === current)
        return current
    }

    fun dismissDelete() {
        _state.update { it.copy(deleteConfirm = null) }
    }

    fun consumePlayUrl() {
        _state.update { it.copy(playUrl = null, playFileName = null, askEveryTime = false) }
    }

    fun clearError() {
        _state.update { it.copy(error = null, authenticationRequired = false) }
    }
}
