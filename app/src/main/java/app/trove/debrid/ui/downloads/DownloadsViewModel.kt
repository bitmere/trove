package app.trove.debrid.ui.downloads

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.trove.debrid.core.DebridDownload
import app.trove.debrid.core.DebridError
import app.trove.debrid.core.DebridProvider
import app.trove.debrid.core.DownloadKind
import app.trove.debrid.core.DownloadRef
import app.trove.debrid.core.DownloadSort
import app.trove.debrid.core.DownloadStatus
import app.trove.debrid.core.ManualTmdbSearch
import app.trove.debrid.core.SimklEntry
import app.trove.debrid.core.LibrarySnapshot
import app.trove.debrid.core.MediaCandidate
import app.trove.debrid.core.MediaMetadata
import app.trove.debrid.core.MetadataLookup
import app.trove.debrid.core.EnrichmentProvenance
import app.trove.debrid.data.DownloadPage
import app.trove.debrid.data.MetadataOverride
import app.trove.debrid.data.TroveRepository
import app.trove.debrid.data.defaultPreferWords
import app.trove.debrid.ui.downloads.notifications.ActiveDownload
import app.trove.debrid.ui.downloads.notifications.DownloadNotificationController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.net.URI
import java.util.Locale

/** Viewport paging state for one tab (Step 2). `loaded` is the rows
 *  paged in so far, in server order; the visible list is the sorted /
 *  filtered view of these rows. `nextOffset` is the server offset of
 *  the next page, and a failed page keeps its offset so Retry
 *  re-requests exactly that page without losing loaded rows. */
data class PagedTabState(
    val loaded: List<DebridDownload> = emptyList(),
    val nextOffset: Int = 0,
    val endReached: Boolean = false,
    val pageLoading: Boolean = false,
    val pageError: String? = null,
    val visited: Boolean = false,
)

/** Scroll anchor for process-death restoration (Step 2b). Small,
 *  non-secret UI state only: the row's kind + id, its index in the
 *  visible list, and the sort + query it was captured under (the
 *  criteria version). Restored only once the anchor row is in the
 *  loaded set; otherwise the list starts at the top. */
data class ScrollAnchor(
    val kind: DownloadKind,
    val id: String,
    val index: Int,
    val sort: DownloadSort,
    val query: String,
)

private enum class PageOutcome { LOADED, FAILED, STALE }

/** Feedback for a hand-entered TMDB search in the picker. `FOUND`
 *  means the search returned candidates, which render above the
 *  manual row — without it the spinner simply disappears and the
 *  user sees nothing change. */
enum class ManualTmdbSearchStatus { SEARCHING, NO_MATCH, INVALID, FOUND }

private fun freshPagedStates(): Map<DownloadKind, PagedTabState> =
    DownloadKind.entries.associateWith { PagedTabState() }

data class DownloadsUiState(
    val metadata: Map<Pair<DownloadKind, String>, MediaMetadata> = emptyMap(),
    val enrichment: Map<Pair<DownloadKind, String>, EnrichmentProvenance> = emptyMap(),
    val pendingPosters: Map<Pair<DownloadKind, String>, String> = emptyMap(),
    val posterCooldowns: Map<Pair<DownloadKind, String>, Long> = emptyMap(),
    /** Picker candidates for the open download only, keyed by (kind, id). */
    val candidates: Map<Pair<DownloadKind, String>, List<MediaCandidate>> = emptyMap(),
    val resolvingOverrides: Set<Pair<DownloadKind, String>> = emptySet(),
    /** Manual TMDB search feedback for the open download, keyed by
     *  (kind, id). Cleared when a search finds candidates. */
    val manualTmdbSearch: Map<Pair<DownloadKind, String>, ManualTmdbSearchStatus> = emptyMap(),
    /** Stored picks for downloads whose metadata came from an override. */
    val metadataOverrides: Map<Pair<DownloadKind, String>, MetadataOverride> = emptyMap(),
    /** SIMKL watch status per download, keyed by (kind, id). Only
     *  rows with an enrichment id can carry one. */
    val simklStatuses: Map<Pair<DownloadKind, String>, SimklEntry> = emptyMap(),
    /** Whether a SIMKL account is signed in (badges can appear). */
    val simklSignedIn: Boolean = false,
    val loading: Boolean = true,
    /** A refresh of an already-loaded list: rows stay visible and
     *  only the pull indicator shows. Distinct from [loading], the
     *  initial full-screen spinner. */
    val refreshing: Boolean = false,
    val signedOut: Boolean = false,
    /** Loaded-so-far rows across tabs: the union of [pagedByTab]
     *  loaded rows. Search sees the whole tab only after its full
     *  sync (see [DownloadsViewModel.setQuery]). */
    val downloads: List<DebridDownload> = emptyList(),
    /** "Essential" etc., from the connected account; null when signed out. */
    val accountLabel: String? = null,
    val tab: DownloadKind = DownloadKind.TORRENT,
    val query: String = "",
    val searchSyncing: Boolean = false,
    val sort: DownloadSort = DownloadSort.NEWEST,
    /** Release words that rank matching downloads first; loaded from Settings. */
    val preferWords: List<String> = defaultPreferWords,
    /** When true the details sheet shows clutter files (.nfo, samples, covers). */
    val showClutterFiles: Boolean = false,
    /** Whether download progress notifications are enabled (Settings, off by default). */
    val notificationsEnabled: Boolean = false,
    /** Whether a TMDB key is configured, for the picker's no-key hint. */
    val tmdbConfigured: Boolean = false,
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
    val visibleByTab: Map<DownloadKind, List<DebridDownload>> = emptyMap(),
    /** Per-tab viewport paging state (Step 2). */
    val pagedByTab: Map<DownloadKind, PagedTabState> = freshPagedStates(),
    /** Pending scroll anchor (Step 2b), consumed once restored or
     *  once its tab is fully loaded without the anchor row. */
    val pendingAnchor: ScrollAnchor? = null,
) {
    override fun toString(): String = "DownloadsUiState([redacted])"
}

class DownloadsViewModel(
    private val repository: TroveRepository,
    private val backgroundDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val now: () -> Long = System::currentTimeMillis,
    private val elapsedNanos: () -> Long = System::nanoTime,
    private val notifications: DownloadNotificationController = DownloadNotificationController.NoOp,
) : ViewModel() {

    companion object {
        /** Rows per viewport append (Step 2). */
        const val PAGE_SIZE = 100
        // TorBox Get Torrent List documents information updates every 600 seconds.
        const val HEAD_REVALIDATION_INTERVAL_MS = 600_000L
        const val POLL_INTERVAL_MS = 60_000L
    }

    private val _state = MutableStateFlow(DownloadsUiState())
    val state: StateFlow<DownloadsUiState> = _state.asStateFlow()

    private data class PendingAdd(val download: DebridDownload, val misses: Int = 0)
    private val pendingAdds = mutableMapOf<Pair<DownloadKind, String>, PendingAdd>()

    private var pollJob: Job? = null
    private var pollingRateLimited = false
    /** True while the app is backgrounded or the user is on another screen
     *  (MainActivity.onPause / DownloadsScreen dispose). Polling keeps
     *  running in that state only when the notification toggle is on. */
    private var backgrounded = false
    /** Last active-download list sent to the notification controller;
     *  identical lists are not re-sent. */
    private var lastSyncedNotifications: List<ActiveDownload>? = null
    private var refreshJob: Job? = null
    private var refreshVersion = 0
    private var generationBypass = false
    private val lastHeadValidation = mutableMapOf<DownloadKind, Long>()
    private val failedPages = mutableMapOf<DownloadKind, Pair<Int, Boolean>>()
    private var missBypassVersion: Int? = null
    private var hydrationJob: Job? = null
    private var revalidationObservers: Job? = null
    private var metadataOrder = 0
    private val overrideEdits = mutableSetOf<DownloadRef>()
    private val hydrationQueue = LinkedHashMap<DownloadRef, Pair<DebridDownload, Boolean>>()
    private val hydratedAttempts = mutableMapOf<DownloadRef, Pair<DebridDownload, Boolean>>()
    private var visibleRowKeys = emptySet<String>()
    private var libraryProvider: DebridProvider? = null
    // A restored prefix can be shorter than its saved server offset.
    // Revalidate page 0 before appending so no uncached rows are skipped.
    private val cachedTabs = mutableSetOf<DownloadKind>()
    private var snapshotSavedAt: Long? = null
    // Fresh traversal evidence is separate from retained rows until the end.
    private val replacementRows = mutableMapOf<DownloadKind, LinkedHashMap<Pair<DownloadKind, String>, DebridDownload>>()
    private val failedHeads = mutableSetOf<DownloadKind>()
    private var snapshotJob: Job? = null
    private val snapshotWrites = Mutex()
    private var fileLookupJob: Job? = null
    private var metadataCandidateJob: Job? = null
    private var manualTmdbSearchJob: Job? = null
    private var playbackJob: Job? = null
    private var simklSyncJob: Job? = null
    private var pageJobs = mutableMapOf<DownloadKind, Job>()
    private var fullSyncJob: Job? = null
    private var fullSyncTab: DownloadKind? = null

    private var sortChanged = false
    private val sortWrites = Mutex()
    private var preparationJob: Job? = null
    private var searchDebouncePending = false
    private var prepared: PreparedVisible? = null

    init {
        viewModelScope.launch {
            repository.metadataOrderChanges.collect { order ->
                if (order != metadataOrder) {
                    metadataOrder = order
                    cancelHydration()
                    cancelRevalidationObservers()
                    _state.update {
                        it.copy(metadata = emptyMap(), enrichment = emptyMap(), pendingPosters = emptyMap(),
                            metadataOverrides = emptyMap(), posterCooldowns = emptyMap(),
                            simklStatuses = emptyMap(),
                            tmdbConfigured = repository.isTmdbConfigured())
                    }
                    val current = _state.value
                    enqueueHydration(current.pagedByTab[current.tab]?.loaded.orEmpty(), refreshVersion)
                }
            }
        }
        viewModelScope.launch {
            repository.loadSimklSnapshot()
            _state.update { it.copy(simklSignedIn = repository.isSimklSignedIn()) }
            refreshSimklStatuses()
            repository.simklSnapshotChanges.collect {
                _state.update { it.copy(simklSignedIn = repository.isSimklSignedIn()) }
                refreshSimklStatuses()
            }
        }
        viewModelScope.launch {
            val savedSort = repository.loadDownloadSort()
            val preferWords = repository.loadPreferWords()
            val showClutterFiles = repository.loadShowClutterFiles()
            val tmdbConfigured = repository.isTmdbConfigured()
            val previous = _state.value
            _state.update {
                it.copy(sort = if (sortChanged) it.sort else savedSort,
                    preferWords = preferWords, showClutterFiles = showClutterFiles,
                    tmdbConfigured = tmdbConfigured)
            }
            if (previous.sort != _state.value.sort || previous.preferWords != preferWords) {
                schedulePreparation(debounced = searchDebouncePending)
            }
        }
        refreshLibrary(showIndicator = true, fresh = false, restoreSnapshot = true)
    }

    fun refresh() = refresh(showIndicator = true, fresh = false)

    /** `fresh` bypasses the provider's server-side list cache and is
     *  only for user-initiated refreshes (pull-to-refresh, after Add):
     *  every fresh load costs uncached calls against the provider's
     *  rate limit. It is required (no default) on this overload so the
     *  no-arg entry above stays the only overload that adapts to
     *  `() -> Unit` for `viewModel::refresh` references.
     *
     *  A refresh resets pagination to page 0 for every tab (offsets
     *  to 0, end-reached cleared). Refresh is head-only: it fetches
     *  page 0 of the visible tab and keeps rows below the head. Sort
     *  changes still traverse the visible tab; other tabs reload on
     *  first visit. Loaded rows remain visible until traversal
     *  confirms deletions. */
    fun refresh(showIndicator: Boolean = true, fresh: Boolean) =
        refreshLibrary(showIndicator, fresh, restoreSnapshot = false)

    fun refreshFromUser(fresh: Boolean = true) =
        refreshLibrary(showIndicator = true, fresh = fresh, restoreSnapshot = false, bypassMetadataMiss = true)
            .also { requestSimklSync() }

    private fun refreshLibrary(
        showIndicator: Boolean,
        fresh: Boolean,
        restoreSnapshot: Boolean,
        bypassMetadataMiss: Boolean = false,
    ) {
        pollingRateLimited = false
        stopPolling()
        refreshJob?.cancel()
        cachedTabs.clear()
        cancelPageJobs()
        fullSyncJob?.cancel()
        fullSyncTab = null
        val version = ++refreshVersion
        generationBypass = fresh
        failedPages.clear()
        failedHeads.clear()
        replacementRows.clear()
        missBypassVersion = version.takeIf { bypassMetadataMiss }
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
                val notificationsEnabled = repository.loadDownloadProgressNotificationsEnabled()
                if (version != refreshVersion) return@launch
                _state.update { it.copy(preferWords = preferWords, showClutterFiles = showClutterFiles, notificationsEnabled = notificationsEnabled) }
                val provider = connection.provider
                val snapshot = if (restoreSnapshot && provider != null) readLibrarySnapshot() else null
                if (version != refreshVersion) return@launch
                val restored = provider != null && snapshot != null && restoreLibrary(provider, snapshot)
                val accountLabel = connection.account?.label?.substringAfter('(')?.trimEnd(')')
                if (libraryProvider !== provider) {
                    pendingAdds.clear()
                    lastHeadValidation.clear()
                    if (libraryProvider != null) missBypassVersion = null
                    cancelHydration()
                    cancelRevalidationObservers()
                    libraryProvider = null
                    fileLookupJob?.cancel()
                    playbackJob?.cancel()
                    _state.update {
                        it.copy(downloads = emptyList(), metadata = emptyMap(), candidates = emptyMap(),
                            manualTmdbSearch = emptyMap(),
                            enrichment = emptyMap(), pendingPosters = emptyMap(), posterCooldowns = emptyMap(),
                            metadataOverrides = emptyMap(), simklStatuses = emptyMap(), accountLabel = null,
                            visibleByTab = emptyMap(), pagedByTab = freshPagedStates(),
                            pendingAnchor = null,
                            filePicker = null, deleteConfirm = null, playUrl = null, playFileName = null,
                            askEveryTime = false, resolvingDownload = null, resolvingFileId = null)
                    }
                    prepared = null
                    if (provider != null) schedulePreparation(debounced = false)
                }
                if (provider == null) {
                    _state.update {
                        it.copy(
                            loading = false, refreshing = false, signedOut = true,
                            downloads = emptyList(), metadata = emptyMap(), candidates = emptyMap(),
                            manualTmdbSearch = emptyMap(),
                            enrichment = emptyMap(), pendingPosters = emptyMap(), posterCooldowns = emptyMap(),
                            metadataOverrides = emptyMap(), simklStatuses = emptyMap(), accountLabel = null, loadError = null,
                            authenticationRequired = false,
                            filePicker = null, playUrl = null, playFileName = null,
                            pagedByTab = freshPagedStates(), pendingAnchor = null,
                        )
                    }
                    schedulePreparation(debounced = false)
                    return@launch
                }
                libraryProvider = provider
                _state.update { it.copy(signedOut = false, accountLabel = accountLabel) }
                if (!restored) resetPagination(keepRows = true, currentTab = _state.value.tab)
                launchPage(provider, _state.value.tab, offset = 0, bypassCache = fresh,
                    version = version, replace = true, traverse = false, headOnly = true)
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
                schedulePreparation(debounced = false)
            }
        }
    }

    private suspend fun readLibrarySnapshot(): LibrarySnapshot? =
        try {
            repository.loadLibrarySnapshot()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }

    private fun restoreLibrary(provider: DebridProvider, snapshot: LibrarySnapshot): Boolean {
        if (snapshot.service != provider.service) return false
        val tabs = freshPagedStates() + snapshot.tabs.mapValues { (_, tab) ->
            PagedTabState(tab.rows, tab.nextOffset, tab.endReached, visited = true)
        }
        val rows = tabs.values.flatMap { it.loaded }
        if (rows.isEmpty()) return false
        // Set the provider before publishing so the refresh keeps these rows.
        libraryProvider = provider
        cachedTabs.addAll(snapshot.tabs.keys)
        snapshotSavedAt = snapshot.savedAt
        _state.update { it.copy(downloads = rows, pagedByTab = tabs, loading = false, refreshing = true) }
        schedulePreparation(debounced = false)
        reconcileHydration(_state.value.tab, refreshVersion)
        return true
    }

    private fun scheduleSnapshotSave(provider: DebridProvider) {
        snapshotJob?.cancel()
        val tabs = _state.value.pagedByTab
        snapshotJob = viewModelScope.launch {
            delay(500)
            val snapshot = LibrarySnapshot(
                provider.service,
                now(),
                tabs.filterValues { it.visited || it.loaded.isNotEmpty() }.mapValues { (_, tab) ->
                    LibrarySnapshot.Tab(
                        tab.loaded,
                        tab.endReached,
                        tab.nextOffset,
                    )
                },
            )
            snapshotWrites.withLock {
                try {
                    repository.saveLibrarySnapshot(snapshot)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // The live library is already published.
                }
            }
        }
    }

    fun onAdded(download: DebridDownload) {
        pendingAdds[download.kind to download.id] = PendingAdd(download)
        _state.update { state ->
            val tab = state.pagedByTab.getValue(download.kind)
            val byTab = state.pagedByTab + (download.kind to tab.copy(
                loaded = listOf(download) + tab.loaded.filterNot { it.id == download.id },
            ))
            state.copy(
                pagedByTab = byTab,
                downloads = byTab.values.flatMap { it.loaded },
                addedConfirmation = download.kind,
            )
        }
        schedulePreparation(debounced = false)
        // The optimistic row is active now: show its notification at once
        // instead of waiting for the head fetch to return.
        syncNotifications()
        refresh(showIndicator = false, fresh = true)
    }

    /** Re-reads the notification toggle after the user flips it in
     *  Settings. Takes effect immediately: off stops the service at once,
     *  on starts it at once when a download is active. */
    fun refreshNotificationPreference() {
        viewModelScope.launch {
            val enabled = try {
                repository.loadDownloadProgressNotificationsEnabled()
            } catch (_: Exception) {
                return@launch
            }
            _state.update { it.copy(notificationsEnabled = enabled) }
            if (enabled) maybeStartPolling()
            syncNotifications()
        }
    }

    override fun onCleared() {
        stopPolling()
        notifications.stop()
        super.onCleared()
    }

    fun consumeAddedConfirmation() {
        _state.update { it.copy(addedConfirmation = null) }
    }

    fun setTab(tab: DownloadKind) {
        stopPolling()
        if (tab != _state.value.tab) {
            cancelHydration()
            visibleRowKeys = emptySet()
            _state.update { it.copy(tab = tab) }
            reconcileHydration(tab, refreshVersion)
        }
        ensureTabLoaded(tab)
        maybeStartPolling()
    }

    internal fun updateVisibleRows(tab: DownloadKind, keys: Set<String>) {
        if (tab == _state.value.tab) visibleRowKeys = keys
    }

    fun setQuery(query: String) {
        _state.update { it.copy(query = query, searchSyncing = query.isNotBlank() && libraryProvider != null) }
        schedulePreparation(debounced = query.isNotEmpty())
        if (query.isNotBlank()) {
            // Match loaded rows first while the remaining pages sync in the background.
            ensureFullSync(_state.value.tab)
        } else {
            fullSyncJob?.cancel()
            fullSyncTab = null
        }
    }

    /** A sort change resets pagination for every tab and
     *  traverses the visible tab while retaining its loaded rows.
     *  In-flight pages belong to the old sort, so the refresh version
     *  is bumped to drop them. */
    fun setSort(sort: DownloadSort) {
        sortChanged = true
        cancelPageJobs()
        fullSyncJob?.cancel()
        fullSyncTab = null
        ++refreshVersion
        generationBypass = false
        failedPages.clear()
        failedHeads.clear()
        replacementRows.clear()
        cachedTabs.clear()
        _state.update { it.copy(sort = sort, pendingAnchor = null) }
        resetPagination(keepRows = true, currentTab = _state.value.tab)
        schedulePreparation(debounced = false)
        val provider = libraryProvider
        if (provider != null) {
            launchPage(provider, _state.value.tab, offset = 0, bypassCache = false,
                version = refreshVersion, replace = true, traverse = true)
        }
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

    // ------------------------------------------------------------------
    // Viewport paging (Step 2 + item 29)
    // ------------------------------------------------------------------

    /** Loads the next page of [tab] when more pages exist. A failed
     *  page keeps its offset, so [retryPage] re-requests only that
     *  page; loaded rows and scroll position are never cleared. */
    fun loadNextPage(tab: DownloadKind = _state.value.tab) {
        val provider = libraryProvider ?: return
        val current = _state.value.pagedByTab[tab] ?: PagedTabState()
        if (current.pageLoading || current.pageError != null) return
        if (tab in cachedTabs) {
            launchPage(provider, tab, offset = 0, bypassCache = generationBypass,
                version = refreshVersion, replace = true)
            return
        }
        if (current.endReached) return
        launchPage(provider, tab, offset = current.nextOffset, bypassCache = generationBypass,
            version = refreshVersion, replace = false)
    }

    /** Re-requests only the failed page of [tab]. */
    fun retryPage(tab: DownloadKind = _state.value.tab) {
        val provider = libraryProvider ?: return
        if (_state.value.pagedByTab[tab]?.pageLoading == true) return
        val failed = failedPages[tab] ?: return
        launchPage(provider, tab, failed.first, failed.first == 0 || failed.second,
            refreshVersion, replace = failed.first == 0,
            traverse = generationBypass || tab in replacementRows, headOnly = tab in failedHeads)
    }

    fun onForeground() {
        backgrounded = false
        pollingRateLimited = false
        revalidateHead(_state.value.tab)
        maybeStartPolling()
    }

    /** The app backgrounded (or the user left the Downloads screen).
     *  With the notification toggle off, polling stops: the 60 s loop
     *  must not keep fetching while nobody watches the list. With the
     *  toggle on, polling continues so the notification stays live
     *  (user decision 2026-11-23). Deliberately does NOT stop the
     *  notification service: the last posted values stay frozen until
     *  the foreground resumes. */
    fun onBackground() {
        backgrounded = true
        if (!_state.value.notificationsEnabled) stopPolling()
    }

    /** The Downloads screen (re-)entered composition. Idempotent:
     *  maybeStartPolling() no-ops when a loop is already running. */
    fun onDownloadsVisible() {
        backgrounded = false
        maybeStartPolling()
    }

    private fun pollingSuppressed(): Boolean {
        val state = _state.value
        return state.signedOut || state.loading || state.refreshing ||
            refreshJob?.isActive == true || fullSyncJob?.isActive == true ||
            state.pagedByTab.values.any { it.pageLoading }
    }

    private fun hasPollingRows(): Boolean =
        _state.value.pagedByTab[_state.value.tab]?.loaded?.any {
            it.status == DownloadStatus.DOWNLOADING || it.status == DownloadStatus.QUEUED
        } == true

    private fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    private fun maybeStartPolling() {
        if (backgrounded && !_state.value.notificationsEnabled) {
            // Backgrounded with the toggle off: no loop, but push the
            // current set so a stale notification still clears.
            syncNotifications()
            return
        }
        if (pollingRateLimited || pollJob?.isActive == true ||
            !hasPollingRows() || pollingSuppressed()) {
            // No poll loop: the active set may just have emptied (a download
            // finished), so push the current set — an empty one stops the
            // notification service at once instead of leaving it stale.
            syncNotifications()
            return
        }
        pollJob = viewModelScope.launch {
            while (currentCoroutineContext().isActive) {
                delay(POLL_INTERVAL_MS)
                if (backgrounded && !_state.value.notificationsEnabled) {
                    // Toggle flipped off while backgrounded: no further
                    // page calls until the foreground resumes.
                    stopPolling()
                    break
                }
                if (!hasPollingRows()) {
                    syncNotifications()
                    stopPolling()
                    break
                }
                if (pollingSuppressed()) continue
                val provider = libraryProvider ?: break
                val tab = _state.value.tab
                launchPage(provider, tab, offset = 0, bypassCache = true,
                    version = refreshVersion, replace = true, headOnly = true, polling = true)
                pageJobs[tab]?.join()
                syncNotifications()
                if (pollingRateLimited) break
            }
        }
        syncNotifications()
    }

    /** Pushes the current active downloads to the notification controller.
     *  The active set spans every loaded tab, not just the visible one:
     *  a download on another tab is still the user's download. Only
     *  DOWNLOADING rows, and QUEUED rows that are just-added optimistic
     *  rows, get notifications. Identical sets are not re-sent; the
     *  service itself decides whether a changed set is worth re-posting
     *  (progress moved >= 2% or the speed/ETA text changed). */
    private fun syncNotifications() {
        val state = _state.value
        val active = if (!state.notificationsEnabled) {
            emptyList()
        } else {
            state.pagedByTab.values.flatMap { it.loaded }
                .filter { row ->
                    row.status == DownloadStatus.DOWNLOADING ||
                        (row.status == DownloadStatus.QUEUED && row.kind to row.id in pendingAdds)
                }
                .distinctBy { it.kind to it.id }
                .map { it.toActiveDownload() }
        }
        if (active == lastSyncedNotifications) return
        lastSyncedNotifications = active
        if (active.isEmpty()) notifications.stop() else notifications.sync(active)
    }

    private fun DebridDownload.toActiveDownload(): ActiveDownload {
        val progress = downloadProgress(this)
        return ActiveDownload(
            kind = kind,
            id = id,
            name = name,
            queued = status == DownloadStatus.QUEUED,
            percent = progress?.let { (it.fraction * 100).toInt() } ?: 0,
            speedText = progress?.speed,
            etaText = progress?.eta,
        )
    }

    private fun revalidateHead(tab: DownloadKind) {
        val provider = libraryProvider ?: return
        val state = _state.value
        if (state.signedOut || state.loading || state.refreshing || refreshJob?.isActive == true ||
            fullSyncJob?.isActive == true || state.pagedByTab.values.any { it.pageLoading }) return
        val last = lastHeadValidation[tab]
        if (last != null && now() - last < HEAD_REVALIDATION_INTERVAL_MS) return
        launchPage(provider, tab, 0, true, refreshVersion, replace = true, headOnly = tab !in cachedTabs && tab !in replacementRows)
    }

    /** Item 29: only the visible tab loads eagerly. Other tabs load
     *  page 0 on first visit; a visited tab with an active query
     *  finishes its sync instead, so search sees the whole tab. */
    private fun ensureTabLoaded(tab: DownloadKind) {
        val provider = libraryProvider ?: return
        val current = _state.value.pagedByTab[tab] ?: PagedTabState()
        if (current.pageLoading || refreshJob?.isActive == true) return
        if (current.visited && tab !in cachedTabs) {
            if (_state.value.query.isNotBlank()) ensureFullSync(tab)
            else revalidateHead(tab)
            return
        }
        launchPage(provider, tab, offset = 0, bypassCache = false,
            version = refreshVersion, replace = true)
    }

    /** Loads the rest of [tab], page by page, until the end or a page
     *  error. Search matches loaded rows as each page arrives.
     *  A page error stops the sync and stays visible as the
     *  tab's page error with Retry. */
    private fun ensureFullSync(tab: DownloadKind) {
        val provider = libraryProvider ?: return
        if (fullSyncJob?.isActive == true && fullSyncTab == tab) return
        fullSyncJob?.cancel()
        fullSyncTab = tab
        val version = refreshVersion
        _state.update { it.copy(searchSyncing = it.query.isNotBlank()) }
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                while (version == refreshVersion) {
                    pageJobs[tab]?.join()
                    val current = _state.value.pagedByTab[tab] ?: return@launch
                    if (tab in cachedTabs || current.endReached || current.pageError != null) return@launch
                    delay(300)
                    if (version != refreshVersion || pageJobs[tab]?.isActive == true) continue
                    val latest = _state.value.pagedByTab.getValue(tab)
                    if (latest.endReached || latest.pageError != null) return@launch
                    launchPage(provider, tab, latest.nextOffset, generationBypass, version, false)
                    val pageJob = pageJobs[tab] ?: return@launch
                    try { pageJob.join() } finally {
                        if (!currentCoroutineContext().isActive) pageJob.cancel()
                    }
                }
            } finally {
                if (fullSyncJob === currentCoroutineContext()[Job]) {
                    _state.update { it.copy(searchSyncing = false) }
                }
            }
        }
        fullSyncJob = job
        // Cancellation before the coroutine starts does not enter its finally block.
        job.invokeOnCompletion {
            if (fullSyncJob === job) _state.update { it.copy(searchSyncing = false) }
        }
        job.start()
    }

    private fun launchPage(
        provider: DebridProvider,
        tab: DownloadKind,
        offset: Int,
        bypassCache: Boolean,
        version: Int,
        replace: Boolean,
        traverse: Boolean = false,
        headOnly: Boolean = false,
        polling: Boolean = false,
    ) {
        pageJobs[tab]?.cancel()
        // A replace without head reconciliation would truncate the tab to page 0
        // (the 0.1.40 missing-files regression). Full traversal is explicit.
        val headOnlyEffective = headOnly || (replace && !traverse)
        val traversePages = !headOnlyEffective && (traverse || tab in replacementRows)
        val bypass = bypassCache || (tab in cachedTabs && snapshotSavedAt?.let {
            now() - it >= HEAD_REVALIDATION_INTERVAL_MS
        } == true)
        _state.update { state ->
            val current = state.pagedByTab[tab] ?: PagedTabState()
            state.copy(pagedByTab = state.pagedByTab +
                (tab to current.copy(pageLoading = true, pageError = null, visited = true)))
        }
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            var published = false
            try {
                if (traversePages) _state.update { it.copy(refreshing = true) }
                var next = offset
                var replacePage = replace
                do {
                    val outcome = fetchPage(provider, tab, next, bypass, version, replacePage, headOnlyEffective, polling)
                    if (outcome != PageOutcome.LOADED) break
                    published = true
                    if (next == 0 && !headOnly && !traversePages && _state.value.query.isNotBlank()) ensureFullSync(tab)
                    val current = _state.value.pagedByTab.getValue(tab)
                    if (!traversePages || current.endReached) break
                    next = current.nextOffset
                    replacePage = false
                    // At most 200 calls/minute, below TorBox's 300/min endpoint limit.
                    delay(300)
                } while (version == refreshVersion)
            } finally {
                if (version == refreshVersion && pageJobs[tab] === currentCoroutineContext()[Job]) {
                    _state.update { state -> state.copy(
                        refreshing = if (traversePages || tab == state.tab) false else state.refreshing,
                        pagedByTab = state.pagedByTab + (tab to state.pagedByTab.getValue(tab).copy(pageLoading = false)),
                    ) }
                    if (published) {
                        maybeStartPolling()
                        refreshJob?.takeIf { it.isActive }?.invokeOnCompletion { maybeStartPolling() }
                        fullSyncJob?.takeIf { it.isActive }?.invokeOnCompletion { maybeStartPolling() }
                    }
                }
            }
        }
        pageJobs[tab] = job
        job.start()
    }

    /** Fetches one page, publishes it, and reconciles metadata for loaded
     *  rows. Returns STALE when a newer refresh / sort change /
     *  provider switch has superseded this page: nothing is
     *  published. A failed page never clears loaded rows. */
    private suspend fun fetchPage(
        provider: DebridProvider,
        tab: DownloadKind,
        offset: Int,
        bypassCache: Boolean,
        version: Int,
        replace: Boolean,
        headOnly: Boolean,
        polling: Boolean,
    ): PageOutcome {
        val owner = currentCoroutineContext()[Job]
        if (version != refreshVersion || pageJobs[tab] !== owner) return PageOutcome.STALE
        if (!polling) {
            if (headOnly) failedHeads.add(tab) else failedHeads.remove(tab)
            failedPages[tab] = offset to bypassCache
        }
        _state.update { state ->
            val current = state.pagedByTab[tab] ?: PagedTabState()
            state.copy(pagedByTab = state.pagedByTab +
                (tab to current.copy(pageLoading = true, pageError = null, visited = true)))
        }
        val page: DownloadPage = try {
            repository.listDownloadPage(provider, tab, offset, PAGE_SIZE, bypassCache).getOrThrow()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            if (version != refreshVersion || libraryProvider !== provider || pageJobs[tab] !== owner) return PageOutcome.STALE
            if (polling) {
                if (e is DebridError.RateLimited) pollingRateLimited = true
                return PageOutcome.FAILED
            }
            _state.update { state ->
                val current = state.pagedByTab[tab] ?: PagedTabState()
                state.copy(
                    loading = false,
                    error = userMessage(e, "Couldn't load downloads. Try again or check Settings."),
                    pagedByTab = state.pagedByTab + (tab to current.copy(
                        pageLoading = false,
                        pageError = userMessage(e, "Couldn't load downloads. Try again or check Settings."),
                    )),
                    // A failed page 0 is the list failing: keep the
                    // global load error (and rows, if any) as today.
                    // Appends also surface the snackbar and per-tab retry.
                    loadError = if (offset == 0) {
                        userMessage(e, "Couldn't load downloads. Try again or check Settings.")
                    } else state.loadError,
                    authenticationRequired = e is DebridError.AuthInvalid,
                )
            }
            reconcileHydration(tab, version, bypassMiss = version == missBypassVersion)
            return PageOutcome.FAILED
        }
        currentCoroutineContext().ensureActive()
        if (version != refreshVersion || libraryProvider !== provider || pageJobs[tab] !== owner) return PageOutcome.STALE
        val oldTab = _state.value.pagedByTab[tab] ?: PagedTabState()
        // Reconciling the head only makes sense when rows below the head
        // exist to retain. On a first load (or a tab with at most one
        // page) a plain replace must carry the page's own cursor;
        // reconciling would freeze nextOffset at 0 and endReached at
        // false, so appends and the search full sync could never advance.
        val reconcileHead = headOnly && tab !in cachedTabs && oldTab.loaded.size > PAGE_SIZE
        if (replace && !reconcileHead && tab !in cachedTabs && oldTab.loaded.isNotEmpty()) {
            replacementRows[tab] = LinkedHashMap()
        }
        val replacement = replacementRows[tab].takeUnless { reconcileHead }
        var merged = if (reconcileHead) {
            val byKey = LinkedHashMap<Pair<DownloadKind, String>, DebridDownload>()
            for (row in page.rows) byKey[row.kind to row.id] = row
            for (row in oldTab.loaded.drop(PAGE_SIZE)) byKey.putIfAbsent(row.kind to row.id, row)
            byKey.values.toList()
        } else if (replacement != null) {
            for (row in page.rows) replacement.putIfAbsent(row.kind to row.id, row)
            if (page.endReached) replacement.values.toList() else {
                val retained = LinkedHashMap(replacement)
                for (row in oldTab.loaded) retained.putIfAbsent(row.kind to row.id, row)
                retained.values.toList()
            }
        } else if (replace) {
            page.rows
        } else {
            val byKey = LinkedHashMap<Pair<DownloadKind, String>, DebridDownload>()
            for (row in oldTab.loaded) byKey[row.kind to row.id] = row
            for (row in page.rows) byKey.putIfAbsent(row.kind to row.id, row)
            byKey.values.toList()
        }
        var droppedPending = false
        if (replace && offset == 0) {
            val serverKeys = page.rows.mapTo(mutableSetOf()) { it.kind to it.id }
            for ((key, pending) in pendingAdds.toMap()) {
                if (key.first != tab) continue
                if (key in serverKeys) {
                    pendingAdds.remove(key)
                } else if (!polling && pending.misses >= 1) {
                    pendingAdds.remove(key)
                    droppedPending = true
                    merged = merged.filterNot { it.kind to it.id == key }
                } else {
                    // Phase 4 deviation: only non-poll heads count misses. TorBox's
                    // 600 s list lag must not exhaust both lives on 60 s poll ticks.
                    // Polls can still confirm a row via the serverKeys branch above.
                    if (!polling) pendingAdds[key] = pending.copy(misses = pending.misses + 1)
                    if (merged.none { it.kind to it.id == key }) {
                        merged = listOf(pending.download) + merged
                    }
                }
            }
        }
        _state.update { state ->
            val tabState = state.pagedByTab[tab] ?: PagedTabState()
            val byTab = state.pagedByTab + (tab to tabState.copy(
                loaded = merged,
                nextOffset = if (reconcileHead) oldTab.nextOffset.coerceAtMost(merged.size) else offset + page.fetchedCount,
                endReached = if (reconcileHead) oldTab.endReached else page.endReached,
                pageLoading = true,
                pageError = null,
                visited = true,
            ))
            val union = byTab.values.flatMap { it.loaded }
            // Retain metadata only for rows still loaded with the same
            // name. Rows on not-yet-loaded pages re-resolve when their
            // page arrives; that keeps a refresh from resurrecting
            // metadata for rows that are gone (stale-batch guard).
            val oldNames = state.downloads.associate { row -> (row.kind to row.id) to row.name }
            val currentNames = union.associate { row -> (row.kind to row.id) to row.name }
            val keep: (Pair<DownloadKind, String>) -> Boolean = { key ->
                key in currentNames && currentNames[key] == oldNames[key]
            }
            state.copy(
                pagedByTab = byTab,
                downloads = union,
                loading = false,
                loadError = if (offset == 0) null else state.loadError,
                metadata = state.metadata.filterKeys(keep),
                enrichment = state.enrichment.filterKeys(keep),
                pendingPosters = state.pendingPosters.filterKeys(keep),
                posterCooldowns = state.posterCooldowns.filterKeys(keep),
                candidates = state.candidates.filterKeys(keep),
                manualTmdbSearch = state.manualTmdbSearch.filterKeys(keep),
                metadataOverrides = state.metadataOverrides.filterKeys(keep),
                simklStatuses = state.simklStatuses.filterKeys(keep),
            )
        }
        if (droppedPending) {
            // The optimistic row is gone with no other trace: say so on
            // the existing error snackbar path instead of dropping it
            // silently. loadError/pageError stay untouched — the list
            // itself loaded fine.
            _state.update {
                it.copy(error = "Couldn't confirm the added download. Pull to refresh and try again.")
            }
        }
        if (offset == 0) lastHeadValidation[tab] = now()
        if (!reconcileHead && page.endReached) replacementRows.remove(tab)
        failedPages.remove(tab)
        failedHeads.remove(tab)
        cachedTabs.remove(tab)
        scheduleSnapshotSave(provider)
        schedulePreparation(debounced = false)
        val bypassMiss = version == missBypassVersion
        if (tab == _state.value.tab || bypassMiss) {
            reconcileHydration(tab, version, bypassMiss)
        }
        return PageOutcome.LOADED
    }

    /** Resets every tab's cursor to page 0. Loaded rows are kept
     *  visible until their tab's traversal reconciles them; unvisited tabs
     *  reload on first visit. */
    private fun resetPagination(keepRows: Boolean, currentTab: DownloadKind) {
        _state.update { state ->
            val byTab = DownloadKind.entries.associateWith { kind ->
                val old = state.pagedByTab[kind] ?: PagedTabState()
                PagedTabState(
                    loaded = if (keepRows) old.loaded else emptyList(),
                    nextOffset = 0,
                    endReached = false,
                    pageLoading = false,
                    pageError = null,
                    visited = kind == currentTab,
                )
            }
            state.copy(
                pagedByTab = byTab,
                downloads = if (keepRows) state.downloads else emptyList(),
            )
        }
    }

    private fun cancelPageJobs() {
        pageJobs.values.forEach { it.cancel() }
        pageJobs.clear()
        cancelHydration()
    }

    // ------------------------------------------------------------------
    // Scroll anchor (Step 2b)
    // ------------------------------------------------------------------

    /** Opaque identity of the account the anchor belongs to: the
     *  provider service name, never a key or label detail. */
    fun currentAccountIdentity(): String? = libraryProvider?.service?.name

    /** Captures the anchor for the visible row at [index] in [tab]'s
     *  visible list, or null when the index is out of range. */
    fun captureAnchor(tab: DownloadKind, index: Int): ScrollAnchor? {
        val rows = visibleDownloads(_state.value, tab)
        val row = rows.getOrNull(index) ?: return null
        val current = _state.value
        return ScrollAnchor(row.kind, row.id, index, current.sort, current.query)
    }

    /** Index of the anchor row in the currently loaded visible list,
     *  or null when the criteria changed or the row is not loaded yet. */
    fun anchorRowIndex(anchor: ScrollAnchor): Int? {
        val current = _state.value
        if (anchor.sort != current.sort || anchor.query != current.query) return null
        return visibleDownloads(current, anchor.kind)
            .indexOfFirst { it.id == anchor.id }.takeIf { it >= 0 }
    }

    /** Stages an anchor restored from saved state. It is ignored when
     *  it belongs to another account, so a sign-in change never
     *  restores a stale position. */
    fun setPendingAnchor(anchor: ScrollAnchor?, accountIdentity: String?) {
        if (anchor != null && accountIdentity != null &&
            currentAccountIdentity() != accountIdentity
        ) return
        _state.update { it.copy(pendingAnchor = anchor) }
    }

    fun consumePendingAnchor() {
        _state.update { it.copy(pendingAnchor = null) }
    }

    // ------------------------------------------------------------------
    // Metadata hydration for the selected tab
    // ------------------------------------------------------------------

    private fun cancelHydration() {
        hydrationJob?.cancel()
        hydrationJob = null
        hydrationQueue.clear()
        hydratedAttempts.clear()
    }

    private fun cancelRevalidationObservers() {
        revalidationObservers?.cancel()
        revalidationObservers = null
    }

    private fun reconcileHydration(tab: DownloadKind, version: Int, bypassMiss: Boolean = false) {
        enqueueHydration(
            _state.value.pagedByTab[tab]?.loaded.orEmpty(), version, bypassMiss, skipPendingPosters = true,
        )
    }

    private fun enqueueHydration(
        rows: List<DebridDownload>,
        version: Int,
        bypassMiss: Boolean = false,
        skipPendingPosters: Boolean = false,
    ) {
        val current = _state.value
        val refreshTime = if (bypassMiss) now() else 0
        for (download in rows) {
            val key = download.kind to download.id
            val poster = current.metadata[key]?.posterUrl
            if (poster == null && skipPendingPosters && key in current.pendingPosters) continue
            if (poster != null &&
                !(bypassMiss && (current.posterCooldowns[key] ?: 0) in 1..refreshTime)
            ) continue
            val ref = DownloadRef(download.kind, download.id)
            if (ref in overrideEdits) continue
            val queued = hydrationQueue[ref] ?: hydratedAttempts[ref]
            if (queued == null || queued.first != download || (bypassMiss && !queued.second)) {
                val request = download to bypassMiss
                hydrationQueue[ref] = request
                hydratedAttempts[ref] = request
            }
        }
        if (hydrationQueue.isEmpty() || hydrationJob?.isActive == true) return
        hydrationJob = viewModelScope.launch { hydrateRows(version) }
    }

    private suspend fun hydrateRows(version: Int) {
        // Layout may not have reported the viewport yet on the first page.
        // Seed its first batch in display order when there is no viewport.
        val initialKeys = if (visibleRowKeys.isEmpty()) {
            val state = _state.value
            val rows = hydrationQueue.values.map { it.first }
            val initialRows = if (rows.size <= 4) rows else withContext(backgroundDispatcher) {
                prepareVisible(rows, state.query, state.sort, state.preferWords)[state.tab].orEmpty().take(4)
            }
            initialRows.mapTo(HashSet()) { "${it.kind}:${it.id}" }
        } else emptySet()

        val pending = LinkedHashMap<DebridDownload, Pair<MetadataLookup, MetadataOverride?>>()
        val order = metadataOrder
        val worker = currentCoroutineContext()
        var lastFlush = elapsedNanos()
        fun flushOffscreen() {
            if (pending.isEmpty() || !worker.isActive || version != refreshVersion || order != metadataOrder) return
            val names = _state.value.downloads.associate { (it.kind to it.id) to it.name }
            val ready = pending.filter { (row, _) ->
                val ref = DownloadRef(row.kind, row.id)
                names[row.kind to row.id] == row.name && ref !in overrideEdits && ref !in hydrationQueue
            }
            _state.update { state ->
                ready.entries.fold(state) { updated, (row, lookup) ->
                    updated.withLookup(row.kind to row.id, lookup.first, lookup.second)
                }
            }
            ready.forEach { (row, lookup) -> observeRevalidation(row, lookup.first, order) }
            pending.clear()
            lastFlush = elapsedNanos()
        }

        var initialBatch = true
        while (version == refreshVersion && hydrationQueue.isNotEmpty()) {
            val estimatedViewport = if (initialBatch) initialKeys else emptySet()
            val visible = visibleRowKeys.ifEmpty { estimatedViewport }
            initialBatch = false
            val batch = hydrationQueue.values.asSequence()
                .filter { (row, _) -> "${row.kind}:${row.id}" in visible }
                .take(4).toList().ifEmpty { hydrationQueue.values.take(4) }
            coroutineScope {
                for (request in batch) launch {
                    val (download, bypassMiss) = request
                    val key = download.kind to download.id
                    val ref = DownloadRef(download.kind, download.id)
                    val viewportKey = "${download.kind}:${download.id}"
                    fun isPriority(): Boolean = viewportKey in visibleRowKeys.ifEmpty { estimatedViewport } ||
                        _state.value.filePicker?.let { it.kind to it.id } == key
                    fun current(): Boolean =
                        version == refreshVersion && order == metadataOrder &&
                            hydrationQueue[ref] === request &&
                            _state.value.downloads.any {
                                it.kind == download.kind && it.id == download.id && it.name == download.name
                            }

                    if (!current()) {
                        if (hydrationQueue[ref] === request) hydrationQueue.remove(ref)
                        return@launch
                    }
                    val storedOverride = try { repository.loadMetadataOverride(ref) }
                        catch (e: CancellationException) { throw e }
                        catch (_: Exception) { null }
                    if (!current()) {
                        if (hydrationQueue[ref] === request) hydrationQueue.remove(ref)
                        return@launch
                    }
                    var appliedOverride: MetadataOverride? = null
                    val result = lookupMetadata {
                        val override = storedOverride?.let { pick ->
                            lookupMetadata {
                                MetadataLookup.from(
                                    if (pick.tmdbId != null) repository.resolveMetadataByTmdbId(pick.tmdbId, pick.type)
                                    else pick.imdbId?.let { repository.resolveMetadataById(it, pick.type) },
                                )
                            }.metadata
                        }
                        if (override != null) {
                            appliedOverride = storedOverride
                            MetadataLookup.from(override)
                        } else {
                            repository.lookupMetadataForRelease(download.name, bypassMiss) { poster ->
                                // The resolver runs on IO. All row guards and updates run on Main.
                                withContext(Dispatchers.Main.immediate) {
                                    if (worker.isActive && current() && isPriority()) _state.update {
                                        it.copy(pendingPosters = it.pendingPosters + (key to poster))
                                    }
                                }
                            }
                        }
                    }
                    ensureActive()
                    val publish = current()
                    if (hydrationQueue[ref] === request) hydrationQueue.remove(ref)
                    if (publish) {
                        if (isPriority()) {
                            _state.update { it.withLookup(key, result, appliedOverride) }
                            observeRevalidation(download, result, order)
                        } else {
                            pending[download] = result to appliedOverride
                            if (pending.size >= 10 || elapsedNanos() - lastFlush >= 200_000_000L) flushOffscreen()
                        }
                    }
                }
            }
        }
        flushOffscreen()
        refreshSimklStatuses()
    }

    /** Recomputes row badges from the current metadata and the
     *  in-memory SIMKL index. Cheap (O(rows)); called whenever
     *  metadata lands or the snapshot changes. */
    private fun refreshSimklStatuses() {
        val current = _state.value
        if (!current.simklSignedIn) {
            if (current.simklStatuses.isNotEmpty()) _state.update { it.copy(simklStatuses = emptyMap()) }
            return
        }
        val statuses = current.metadata.entries.mapNotNull { (key, metadata) ->
            val tmdbId = current.metadataOverrides[key]?.tmdbId
            repository.simklEntryFor(metadata.imdbId, tmdbId)?.let { key to it }
        }.toMap()
        if (statuses != current.simklStatuses) _state.update { it.copy(simklStatuses = statuses) }
    }

    /** On-demand SIMKL sync (activities-gated, never on a timer).
     *  Fire-and-forget: a failed sync leaves the last snapshot and
     *  its badges in place; failures surface in Settings only. */
    fun requestSimklSync() {
        if (!repository.isSimklSignedIn()) return
        if (simklSyncJob?.isActive == true) return
        simklSyncJob = viewModelScope.launch {
            try {
                repository.syncSimkl()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Settings-only failure; the list keeps its badges.
            }
        }
    }

    private suspend fun lookupMetadata(block: suspend () -> MetadataLookup): MetadataLookup = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        MetadataLookup.Unavailable
    }

    private fun DownloadsUiState.withLookup(
        key: Pair<DownloadKind, String>,
        result: MetadataLookup,
        storedOverride: MetadataOverride? = null,
    ): DownloadsUiState = copy(
        metadata = result.metadata?.let { metadata + (key to it) } ?: metadata,
        enrichment = enrichment + (key to result.provenance),
        pendingPosters = pendingPosters - key,
        posterCooldowns = if (result.retryAfter > 0) posterCooldowns + (key to result.retryAfter)
            else posterCooldowns - key,
        metadataOverrides = storedOverride?.let { metadataOverrides + (key to it) } ?: metadataOverrides,
    )

    private fun observeRevalidation(row: DebridDownload, initial: MetadataLookup, order: Int) {
        val result = initial.revalidation ?: return
        val key = row.kind to row.id
        val ref = DownloadRef(row.kind, row.id)
        val parent = revalidationObservers ?: SupervisorJob(viewModelScope.coroutineContext[Job]).also {
            revalidationObservers = it
        }
        viewModelScope.launch(parent) {
            val refreshed = result.await() ?: return@launch
            val current = _state.value
            if (order == metadataOrder && current.metadata[key] == initial.metadata &&
                ref !in overrideEdits && ref !in hydrationQueue && current.hasMetadataRow(row)
            ) _state.update { it.withLookup(key, refreshed) }
        }
    }

    internal suspend fun onPosterFailure(row: DebridDownload, url: String) {
        val key = row.kind to row.id
        val order = metadataOrder
        val provider = libraryProvider
        val settled = state.first { it.pendingPosters[key] != url || it.metadata[key]?.posterUrl == url }
        val metadata = settled.metadata[key]?.takeIf { it.posterUrl == url } ?: return
        if (key in settled.metadataOverrides || !settled.hasMetadataRow(row)) return
        val replacement = try {
            repository.replaceFailedPoster(row.name, metadata)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } ?: return
        currentCoroutineContext().ensureActive()
        if (provider === libraryProvider && order == metadataOrder && DownloadRef(row.kind, row.id) !in overrideEdits
        ) _state.update {
            if (it.metadata[key] == metadata && it.hasMetadataRow(row)) it.copy(
                metadata = it.metadata + (key to replacement),
                enrichment = it.enrichment + (key to MetadataLookup.from(replacement).provenance),
            ) else it
        }
    }

    private fun DownloadsUiState.hasMetadataRow(row: DebridDownload): Boolean = downloads.any {
        it.kind == row.kind && it.id == row.id && it.name == row.name
    }

    fun visibleDownloads(state: DownloadsUiState, tab: DownloadKind = state.tab): List<DebridDownload> {
        val cached = prepared
        if (cached != null && cached.inputs.matchesLibrary(state) &&
            (cached.inputs.query == state.query || searchDebouncePending)
        ) {
            return cached.byTab[tab].orEmpty()
        }
        return prepareVisible(state.downloads, state.query, state.sort, state.preferWords)[tab].orEmpty()
    }

    private fun schedulePreparation(debounced: Boolean) {
        preparationJob?.cancel()
        searchDebouncePending = debounced
        preparationJob = viewModelScope.launch {
            if (debounced) {
                delay(250)
                searchDebouncePending = false
            }
            prepareAndPublish()
        }
    }

    private suspend fun prepareAndPublish() {
        val current = _state.value
        val inputs = VisibleInputs(current.downloads, current.query, current.sort, current.preferWords)
        val byTab = withContext(backgroundDispatcher) {
            prepareVisible(inputs.downloads, inputs.query, inputs.sort, inputs.preferWords)
        }
        val latest = _state.value
        if (!inputs.matchesLibrary(latest) || inputs.query != latest.query) return
        prepared = PreparedVisible(inputs, byTab)
        _state.update { it.copy(visibleByTab = byTab) }
    }

    fun openDownload(download: DebridDownload) {
        if (_state.value.resolvingDownload != null || _state.value.playUrl != null) return
        requestSimklSync()
        if (download.files.isNotEmpty()) {
            _state.update { it.copy(filePicker = download) }
            loadCandidatesFor(download)
        } else {
            loadFiles(download, autoPlay = false)
        }
    }

    /** Loads the picker for the open download only: a stored pick
     *  resolves first; otherwise the candidates for the parsed title.
     *  Runs only when the automatic match left no metadata. */
    private fun loadCandidatesFor(download: DebridDownload) {
        val key = download.kind to download.id
        val version = refreshVersion
        val order = metadataOrder
        metadataCandidateJob?.cancel()
        metadataCandidateJob = viewModelScope.launch {
            fun current() = version == refreshVersion && order == metadataOrder &&
                _state.value.filePicker?.let { it.kind == download.kind && it.id == download.id && it.name == download.name } == true
            if (!current() || key in _state.value.metadata) return@launch
            if (key !in _state.value.enrichment) enqueueHydration(listOf(download), version)
            val storedOverride = try { repository.loadMetadataOverride(DownloadRef(download.kind, download.id)) }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { null }
            if (storedOverride != null) {
                val metadata = try {
                    if (storedOverride.tmdbId != null) {
                        repository.resolveMetadataByTmdbId(storedOverride.tmdbId, storedOverride.type)
                    } else {
                        storedOverride.imdbId?.let { repository.resolveMetadataById(it, storedOverride.type) }
                    }
                }
                    catch (e: CancellationException) { throw e }
                    catch (_: Exception) { null }
                ensureActive()
                if (metadata != null && current() && key !in _state.value.metadata &&
                    DownloadRef(download.kind, download.id) !in overrideEdits && key !in _state.value.metadataOverrides
                ) {
                    _state.update { it.withLookup(key, MetadataLookup.from(metadata), storedOverride) }
                    refreshSimklStatuses()
                    return@launch
                }
            }
            val candidates = try {
                repository.resolveMetadataCandidates(app.trove.debrid.core.parseMediaName(download.name))
            } catch (e: CancellationException) { throw e }
              catch (_: Exception) { emptyList() }
            ensureActive()
            if (candidates.isNotEmpty() && current() && key !in _state.value.metadataOverrides) {
                _state.update {
                    it.copy(
                        candidates = it.candidates + (key to candidates),
                        manualTmdbSearch = it.manualTmdbSearch - key,
                    )
                }
            }
        }
    }

    /** Stores the user's pick for this download only, then shows the
     *  picked title. The pick is keyed by (kind, id). */
    fun pickCandidate(download: DebridDownload, candidate: MediaCandidate) {
        changeMetadataOverride(
            download,
            MetadataOverride(candidate.imdbId, candidate.type, candidate.title, candidate.tmdbId),
        )
    }

    fun clearMetadataOverride(download: DebridDownload) {
        changeMetadataOverride(download, null)
    }

    /** Hand-entered TMDB search for the picker: a title, a
     *  themoviedb.org link, or a bare TMDB id. Explicit user
     *  intent, so it runs even when the automatic candidates exist
     *  and replaces the shown list for this download only. A found
     *  search clears the feedback; no match or invalid input sets
     *  it. A failed search reads as no match, exactly like the
     *  automatic candidate load. */
    fun searchManualTmdbLink(download: DebridDownload, input: String) {
        val key = download.kind to download.id
        if (_state.value.filePicker?.let { it.kind to it.id } != key) return
        // A manual search owns the picker: a late automatic load must
        // not overwrite its results or clear its feedback. A
        // stored-override resolution cancelled here re-runs on the
        // next open.
        metadataCandidateJob?.cancel()
        manualTmdbSearchJob?.cancel()
        _state.update {
            it.copy(manualTmdbSearch = it.manualTmdbSearch + (key to ManualTmdbSearchStatus.SEARCHING))
        }
        val type = app.trove.debrid.core.parseMediaName(download.name).type
        manualTmdbSearchJob = viewModelScope.launch {
            val result = try {
                repository.searchManualTmdbLink(input, type)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                ManualTmdbSearch.NoMatch
            }
            ensureActive()
            if (_state.value.filePicker?.let { it.kind to it.id } != key) {
                // The picker closed while the search ran: leave no
                // stale SEARCHING behind for the next opening.
                _state.update { it.copy(manualTmdbSearch = it.manualTmdbSearch - key) }
                return@launch
            }
            when (result) {
                is ManualTmdbSearch.Found -> _state.update {
                    it.copy(
                        candidates = it.candidates + (key to result.candidates),
                        manualTmdbSearch = it.manualTmdbSearch + (key to ManualTmdbSearchStatus.FOUND),
                    )
                }
                ManualTmdbSearch.NoMatch -> _state.update {
                    it.copy(manualTmdbSearch = it.manualTmdbSearch + (key to ManualTmdbSearchStatus.NO_MATCH))
                }
                ManualTmdbSearch.Invalid -> _state.update {
                    it.copy(manualTmdbSearch = it.manualTmdbSearch + (key to ManualTmdbSearchStatus.INVALID))
                }
            }
        }
    }

    fun clearManualTmdbSearch(download: DebridDownload) {
        val key = download.kind to download.id
        _state.update { it.copy(manualTmdbSearch = it.manualTmdbSearch - key) }
    }

    private fun changeMetadataOverride(download: DebridDownload, pick: MetadataOverride?) {
        val key = download.kind to download.id
        val ref = DownloadRef(download.kind, download.id)
        if (_state.value.filePicker?.let { it.kind to it.id } != key || ref in overrideEdits) return
        val version = refreshVersion
        val order = metadataOrder
        metadataCandidateJob?.cancel()
        overrideEdits.add(ref)
        _state.update { it.copy(resolvingOverrides = it.resolvingOverrides + key) }
        hydrationQueue.remove(ref)
        viewModelScope.launch {
            var published: MetadataLookup? = null
            try {
                if (pick != null) repository.saveMetadataOverride(ref, pick)
                else repository.clearMetadataOverride(ref)
                fun current() = version == refreshVersion && order == metadataOrder &&
                    _state.value.downloads.any {
                        it.kind == download.kind && it.id == download.id && it.name == download.name
                    }
                if (!current()) return@launch
                _state.update {
                    it.copy(
                        metadataOverrides = if (pick == null) it.metadataOverrides - key else it.metadataOverrides + (key to pick),
                        metadata = it.metadata - key, enrichment = it.enrichment - key,
                        pendingPosters = it.pendingPosters - key, posterCooldowns = it.posterCooldowns - key,
                    )
                }
                val result = lookupMetadata {
                    if (pick != null) MetadataLookup.from(
                        if (pick.tmdbId != null) repository.resolveMetadataByTmdbId(pick.tmdbId, pick.type)
                        else pick.imdbId?.let { repository.resolveMetadataById(it, pick.type) },
                    )
                    else repository.lookupMetadataForRelease(download.name)
                }
                ensureActive()
                if (current()) {
                    _state.update { it.withLookup(key, result, pick) }
                    refreshSimklStatuses()
                    published = result
                }
                if (pick == null && result.metadata == null && current()) loadCandidatesFor(download)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Keep the previous match if the stored preference could not be changed.
            } finally {
                overrideEdits.remove(ref)
                _state.update { it.copy(resolvingOverrides = it.resolvingOverrides - key) }
                if (version != refreshVersion || order != metadataOrder) {
                    val current = _state.value
                    enqueueHydration(current.pagedByTab[current.tab]?.loaded.orEmpty(), refreshVersion)
                } else {
                    published?.let { observeRevalidation(download, it, order) }
                }
            }
        }
    }

    /** A click or sheet animation can finish after the library has been replaced.
     *  Sorts and refreshes republish fresh instances of the same downloads, so
     *  identity is structural, not referential: a tap captured before a sort
     *  must still reach the download it was aimed at. But a row from a
     *  replacement account must not count as current even when its (kind, id)
     *  collides with the old row's — the records differ, so `==` keeps the
     *  account-switch guard that `===` used to provide. */
    internal fun isCurrentDownload(download: DebridDownload): Boolean {
        val current = _state.value
        return current.filePicker == download ||
            current.downloads.any { it == download }
    }

    private fun loadFiles(download: DebridDownload, autoPlay: Boolean) {
        val key = download.kind to download.id
        _state.update { it.copy(resolvingDownload = key,
            filePicker = if (autoPlay) it.filePicker else download, error = null) }
        val source = libraryProvider
        fileLookupJob = viewModelScope.launch {
            // Auto-play hands the resolving state to playFile: the lookup's
            // own completion must not clear it from under the playback job.
            var handedOffToPlayback = false
            try {
                val provider = requireCurrentProvider(source)
                val detail = provider.getDownload(app.trove.debrid.core.DownloadRef(download.kind, download.id)).getOrThrow()
                ensureActive()
                if (autoPlay && detail.files.isNotEmpty()) {
                    _state.update { it.copy(resolvingDownload = null) }
                    handedOffToPlayback = true
                    play(detail)
                }
                else {
                    _state.update {
                        it.copy(resolvingDownload = null,
                            filePicker = if (autoPlay || it.filePicker != null) detail else null,
                            error = if (autoPlay) "No files are available yet. Refresh and try again." else null)
                    }
                    if (!autoPlay) loadCandidatesFor(detail)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ensureActive()
                _state.update { it.copy(resolvingDownload = null, error = userMessage(e, "Couldn't load download files"),
                    authenticationRequired = e is DebridError.AuthInvalid) }
            } finally {
                // A cancelled lookup rethrew before the clearing update, which
                // left resolvingDownload stuck and the Play button disabled.
                // Clear only this lookup's key: a newer lookup for another
                // download, or a handed-off playback, keeps its own state.
                if (!handedOffToPlayback) {
                    _state.update { s -> if (s.resolvingDownload == key) s.copy(resolvingDownload = null) else s }
                }
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
            val key = download.kind to download.id
            try {
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
            } finally {
                // A cancelled playback rethrew before the fold, which left
                // resolvingDownload stuck and the Play button disabled.
                // Clear only this playback's key and file: a newer playback
                // keeps its own state.
                _state.update { s ->
                    if (s.resolvingDownload == key && s.resolvingFileId == fileId) s.copy(resolvingDownload = null, resolvingFileId = null) else s
                }
            }
        }
    }

    private fun userMessage(error: Throwable, fallback: String): String = when (error) {
        is DebridError.AuthInvalid -> "TorBox rejected your API key. Open Settings and re-enter it, then tap Test & save."
        is DebridError.Provider -> error.detail.ifBlank { fallback }
        is DebridError.QuotaExceeded -> error.detail.ifBlank { fallback }
        is DebridError.NameRefused -> error.detail.ifBlank { fallback }
        is DebridError -> error.message ?: fallback
        else -> fallback
    }

    fun playbackError(message: String) {
        _state.update { it.copy(error = message) }
    }

    fun dismissFilePicker() {
        val current = _state.value
        val key = current.filePicker?.let { it.kind to it.id }
        val loadingFiles = current.filePicker?.files?.isEmpty() == true &&
            current.resolvingDownload != null && current.resolvingFileId == null
        if (loadingFiles) fileLookupJob?.cancel()
        // A search dismissed mid-flight must not leave a stale
        // SEARCHING behind: on reopen the field and Search would
        // stay disabled with nothing to clear them.
        if (key != null) {
            manualTmdbSearchJob?.cancel()
        }
        _state.update { it.copy(filePicker = null,
            manualTmdbSearch = if (key != null) it.manualTmdbSearch - key else it.manualTmdbSearch,
            resolvingDownload = if (loadingFiles) null else it.resolvingDownload) }
    }

    fun requestDelete(download: DebridDownload) {
        if (_state.value.resolvingDownload != null || _state.value.playUrl != null) return
        manualTmdbSearchJob?.cancel()
        val key = download.kind to download.id
        _state.update { it.copy(deleteConfirm = download, filePicker = null,
            manualTmdbSearch = it.manualTmdbSearch - key) }
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

private data class VisibleInputs(
    val downloads: List<DebridDownload>,
    val query: String,
    val sort: DownloadSort,
    val preferWords: List<String>,
) {
    fun matchesLibrary(state: DownloadsUiState): Boolean =
        downloads === state.downloads && sort == state.sort && preferWords == state.preferWords
}

private data class PreparedVisible(
    val inputs: VisibleInputs,
    val byTab: Map<DownloadKind, List<DebridDownload>>,
)

private data class DownloadSortKeys(
    val download: DebridDownload,
    val name: String,
    val date: Long?,
    val size: Long?,
    val progress: Float,
    val preference: Int,
)

private val releaseSeparators = Regex("""[._\-\s\p{Ps}\p{Pe}<>]+""")

private fun prepareVisible(
    downloads: List<DebridDownload>,
    query: String,
    sort: DownloadSort,
    preferWords: List<String>,
): Map<DownloadKind, List<DebridDownload>> {
    val lowerQuery = query.trim().lowercase(Locale.ROOT)
    val preferred = preferWords.asSequence().map { it.trim().lowercase(Locale.ROOT) }
        .filter { it.isNotEmpty() }.toSet()

    // Match core sortDownloads, including preference priority and stable ties.
    // DownloadsLibraryPerformanceTest pins the ordering.
    val sortComparator: Comparator<DownloadSortKeys> = when (sort) {
        DownloadSort.NEWEST -> compareBy<DownloadSortKeys, Long?>(nullsLast(reverseOrder())) { it.date }
        DownloadSort.OLDEST -> compareBy<DownloadSortKeys, Long?>(nullsLast(naturalOrder())) { it.date }
        DownloadSort.NAME_ASC -> compareBy { it.name }
        DownloadSort.NAME_DESC -> compareByDescending { it.name }
        DownloadSort.LARGEST -> compareBy<DownloadSortKeys, Long?>(nullsLast(reverseOrder())) { it.size }
        DownloadSort.SMALLEST -> compareBy<DownloadSortKeys, Long?>(nullsLast(naturalOrder())) { it.size }
        DownloadSort.PROGRESS -> compareByDescending { it.progress }
    }
    val preference = compareByDescending<DownloadSortKeys> { it.preference }
    val comparator = if (sort == DownloadSort.NEWEST || sort == DownloadSort.OLDEST) {
        sortComparator.then(preference)
    } else {
        preference.then(sortComparator)
    }

    return downloads.groupBy { it.kind }.mapValues { (_, rows) ->
        rows.mapNotNull { download ->
            val name = download.name.lowercase(Locale.ROOT)
            if (!name.contains(lowerQuery)) return@mapNotNull null
            val preference = if (preferred.isEmpty()) 0 else releaseSeparators.splitToSequence(name)
                .filter { it in preferred }.distinct().count()
            DownloadSortKeys(
                download = download,
                name = name,
                date = download.createdAtEpochMs,
                size = download.sizeBytes?.takeIf { it >= 0 },
                progress = download.progress.takeIf { it.isFinite() && it > 0f }?.coerceAtMost(1f) ?: 0f,
                preference = preference,
            )
        }.sortedWith(comparator).map { it.download }
    }
}
