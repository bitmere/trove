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
import app.trove.debrid.core.LibrarySnapshot
import app.trove.debrid.core.MediaCandidate
import app.trove.debrid.core.MediaMetadata
import app.trove.debrid.data.DownloadPage
import app.trove.debrid.data.MetadataOverride
import app.trove.debrid.data.TroveRepository
import app.trove.debrid.data.defaultPreferWords
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
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

private fun freshPagedStates(): Map<DownloadKind, PagedTabState> =
    DownloadKind.entries.associateWith { PagedTabState() }

data class DownloadsUiState(
    val metadata: Map<Pair<DownloadKind, String>, MediaMetadata> = emptyMap(),
    /** Picker candidates for the open download only, keyed by (kind, id). */
    val candidates: Map<Pair<DownloadKind, String>, List<MediaCandidate>> = emptyMap(),
    /** Stored picks for downloads whose metadata came from an override. */
    val metadataOverrides: Map<Pair<DownloadKind, String>, MetadataOverride> = emptyMap(),
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
) : ViewModel() {

    companion object {
        /** Rows per viewport append (Step 2). */
        const val PAGE_SIZE = 100
    }

    private val _state = MutableStateFlow(DownloadsUiState())
    val state: StateFlow<DownloadsUiState> = _state.asStateFlow()

    private var refreshJob: Job? = null
    private var refreshVersion = 0
    private var hydrationJob: Job? = null
    private val hydrationQueue = LinkedHashMap<DownloadRef, Pair<DebridDownload, Boolean>>()
    private var visibleRowKeys = emptySet<String>()
    private var libraryProvider: DebridProvider? = null
    // A restored prefix can be shorter than its saved server offset.
    // Revalidate page 0 before appending so no uncached rows are skipped.
    private val cachedTabs = mutableSetOf<DownloadKind>()
    private var snapshotJob: Job? = null
    private val snapshotWrites = Mutex()
    private var fileLookupJob: Job? = null
    private var playbackJob: Job? = null
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
            val savedSort = repository.loadDownloadSort()
            val preferWords = repository.loadPreferWords()
            val showClutterFiles = repository.loadShowClutterFiles()
            val previous = _state.value
            _state.update {
                it.copy(sort = if (sortChanged) it.sort else savedSort,
                    preferWords = preferWords, showClutterFiles = showClutterFiles)
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
     *  to 0, end-reached cleared) and reloads page 0 of the visible
     *  tab only; other tabs reload page 0 on first visit. Loaded rows
     *  stay visible until page 0 replaces them. */
    fun refresh(showIndicator: Boolean = true, fresh: Boolean) =
        refreshLibrary(showIndicator, fresh, restoreSnapshot = false)

    fun refreshFromUser(fresh: Boolean = true) =
        refreshLibrary(showIndicator = true, fresh = fresh, restoreSnapshot = false, bypassMetadataMiss = true)

    private fun refreshLibrary(
        showIndicator: Boolean,
        fresh: Boolean,
        restoreSnapshot: Boolean,
        bypassMetadataMiss: Boolean = false,
    ) {
        refreshJob?.cancel()
        cachedTabs.clear()
        cancelPageJobs()
        fullSyncJob?.cancel()
        fullSyncTab = null
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
                val snapshot = if (restoreSnapshot && provider != null) readLibrarySnapshot() else null
                if (version != refreshVersion) return@launch
                val restored = provider != null && snapshot != null && restoreLibrary(provider, snapshot)
                val accountLabel = connection.account?.label?.substringAfter('(')?.trimEnd(')')
                if (libraryProvider !== provider) {
                    libraryProvider = null
                    fileLookupJob?.cancel()
                    playbackJob?.cancel()
                    _state.update {
                        it.copy(downloads = emptyList(), metadata = emptyMap(), candidates = emptyMap(),
                            metadataOverrides = emptyMap(), accountLabel = null,
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
                            metadataOverrides = emptyMap(), accountLabel = null, loadError = null,
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
                    version = version, replace = true, bypassMetadataMiss = bypassMetadataMiss)
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
        _state.update { it.copy(downloads = rows, pagedByTab = tabs, loading = false, refreshing = true) }
        schedulePreparation(debounced = false)
        return true
    }

    private fun scheduleSnapshotSave(provider: DebridProvider) {
        snapshotJob?.cancel()
        val tabs = _state.value.pagedByTab
        snapshotJob = viewModelScope.launch {
            delay(500)
            val snapshot = LibrarySnapshot(
                provider.service,
                System.currentTimeMillis(),
                tabs.filterValues { it.visited || it.loaded.isNotEmpty() }.mapValues { (_, tab) ->
                    LibrarySnapshot.Tab(
                        tab.loaded.take(LibrarySnapshot.MAX_ROWS_PER_TAB),
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

    fun onAdded(kind: DownloadKind) {
        _state.update { it.copy(addedConfirmation = kind) }
        refresh(showIndicator = false, fresh = true)
    }

    fun consumeAddedConfirmation() {
        _state.update { it.copy(addedConfirmation = null) }
    }

    fun setTab(tab: DownloadKind) {
        if (tab != _state.value.tab) {
            cancelHydration()
            visibleRowKeys = emptySet()
            _state.update { it.copy(tab = tab) }
            if (tab !in cachedTabs) {
                enqueueHydration(_state.value.pagedByTab[tab]?.loaded.orEmpty(), refreshVersion)
            }
        }
        ensureTabLoaded(tab)
    }

    internal fun updateVisibleRows(tab: DownloadKind, keys: Set<String>) {
        if (tab == _state.value.tab) visibleRowKeys = keys
    }

    fun setQuery(query: String) {
        _state.update { it.copy(query = query) }
        schedulePreparation(debounced = query.isNotEmpty())
        if (query.isNotBlank()) {
            // Search matches the whole tab, not just loaded rows: finish
            // the tab's sync before matching (Step 2).
            ensureFullSync(_state.value.tab)
        } else {
            fullSyncJob?.cancel()
            fullSyncTab = null
        }
    }

    /** A sort change resets pagination to page 0 for every tab and
     *  reloads page 0 of the visible tab. In-flight pages belong to
     *  the old sort, so the refresh version is bumped to drop them. */
    fun setSort(sort: DownloadSort) {
        sortChanged = true
        cancelPageJobs()
        fullSyncJob?.cancel()
        fullSyncTab = null
        ++refreshVersion
        cachedTabs.clear()
        _state.update { it.copy(sort = sort, pendingAnchor = null) }
        resetPagination(keepRows = true, currentTab = _state.value.tab)
        schedulePreparation(debounced = false)
        val provider = libraryProvider
        if (provider != null) {
            launchPage(provider, _state.value.tab, offset = 0, bypassCache = false,
                version = refreshVersion, replace = true)
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
        if (current.pageLoading) return
        if (tab in cachedTabs) {
            launchPage(provider, tab, offset = 0, bypassCache = false,
                version = refreshVersion, replace = true)
            return
        }
        if (current.endReached) return
        launchPage(provider, tab, offset = current.nextOffset, bypassCache = false,
            version = refreshVersion, replace = false)
    }

    /** Re-requests only the failed page of [tab]. */
    fun retryPage(tab: DownloadKind = _state.value.tab) = loadNextPage(tab)

    /** Item 29: only the visible tab loads eagerly. Other tabs load
     *  page 0 on first visit; a visited tab with an active query
     *  finishes its sync instead, so search sees the whole tab. */
    private fun ensureTabLoaded(tab: DownloadKind) {
        val provider = libraryProvider ?: return
        val current = _state.value.pagedByTab[tab] ?: PagedTabState()
        if (tab in cachedTabs && current.pageLoading) return
        if (current.visited && tab !in cachedTabs) {
            if (_state.value.query.isNotBlank()) ensureFullSync(tab)
            return
        }
        launchPage(provider, tab, offset = 0, bypassCache = false,
            version = refreshVersion, replace = true)
    }

    /** Loads the rest of [tab], page by page, until the end or a page
     *  error. Used for search: the tab must be complete before it is
     *  matched. A page error stops the sync and stays visible as the
     *  tab's page error with Retry. */
    private fun ensureFullSync(tab: DownloadKind) {
        val provider = libraryProvider ?: return
        if (fullSyncJob?.isActive == true && fullSyncTab == tab) return
        fullSyncJob?.cancel()
        fullSyncTab = tab
        val version = refreshVersion
        fullSyncJob = viewModelScope.launch {
            while (version == refreshVersion) {
                val current = _state.value.pagedByTab[tab] ?: return@launch
                if (tab in cachedTabs || current.endReached || current.pageError != null) return@launch
                if (current.pageLoading) {
                    delay(50)
                    continue
                }
                val outcome = fetchPage(provider, tab, current.nextOffset,
                    bypassCache = false, version = version, replace = false)
                if (outcome != PageOutcome.LOADED) return@launch
            }
        }
    }

    private fun launchPage(
        provider: DebridProvider,
        tab: DownloadKind,
        offset: Int,
        bypassCache: Boolean,
        version: Int,
        replace: Boolean,
        bypassMetadataMiss: Boolean = false,
    ) {
        pageJobs[tab]?.cancel()
        _state.update { state ->
            val current = state.pagedByTab[tab] ?: PagedTabState()
            state.copy(pagedByTab = state.pagedByTab +
                (tab to current.copy(pageLoading = true, pageError = null, visited = true)))
        }
        pageJobs[tab] = viewModelScope.launch {
            fetchPage(provider, tab, offset, bypassCache, version, replace, bypassMetadataMiss)
        }
    }

    /** Fetches one page, publishes it, and queues metadata hydration for its
     *  new rows. Returns STALE when a newer refresh / sort change /
     *  provider switch has superseded this page: nothing is
     *  published. A failed page never clears loaded rows. */
    private suspend fun fetchPage(
        provider: DebridProvider,
        tab: DownloadKind,
        offset: Int,
        bypassCache: Boolean,
        version: Int,
        replace: Boolean,
        bypassMetadataMiss: Boolean = false,
    ): PageOutcome {
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
            if (version != refreshVersion) return PageOutcome.STALE
            _state.update { state ->
                val current = state.pagedByTab[tab] ?: PagedTabState()
                state.copy(
                    loading = false,
                    refreshing = if (offset == 0 && tab == state.tab) false else state.refreshing,
                    pagedByTab = state.pagedByTab + (tab to current.copy(
                        pageLoading = false,
                        pageError = userMessage(e, "Couldn't load downloads. Try again or check Settings."),
                    )),
                    // A failed page 0 is the list failing: keep the
                    // global load error (and rows, if any) as today.
                    // A failed append only gets the per-page error row.
                    loadError = if (offset == 0) {
                        userMessage(e, "Couldn't load downloads. Try again or check Settings.")
                    } else state.loadError,
                    authenticationRequired = e is DebridError.AuthInvalid,
                )
            }
            return PageOutcome.FAILED
        }
        if (version != refreshVersion || libraryProvider !== provider) return PageOutcome.STALE
        val oldTab = _state.value.pagedByTab[tab] ?: PagedTabState()
        val merged = if (replace) {
            page.rows
        } else {
            val byKey = LinkedHashMap<Pair<DownloadKind, String>, DebridDownload>()
            for (row in oldTab.loaded) byKey[row.kind to row.id] = row
            for (row in page.rows) byKey.putIfAbsent(row.kind to row.id, row)
            byKey.values.toList()
        }
        _state.update { state ->
            val tabState = state.pagedByTab[tab] ?: PagedTabState()
            val byTab = state.pagedByTab + (tab to tabState.copy(
                loaded = merged,
                nextOffset = offset + page.fetchedCount,
                endReached = page.endReached,
                pageLoading = false,
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
                refreshing = if (offset == 0 && tab == state.tab) false else state.refreshing,
                loadError = if (offset == 0) null else state.loadError,
                metadata = state.metadata.filterKeys(keep),
                candidates = state.candidates.filterKeys(keep),
                metadataOverrides = state.metadataOverrides.filterKeys(keep),
            )
        }
        cachedTabs.remove(tab)
        scheduleSnapshotSave(provider)
        schedulePreparation(debounced = false)
        if (offset == 0 && _state.value.query.isNotBlank()) ensureFullSync(tab)
        if (tab == _state.value.tab) {
            val previousIds = if (replace) emptySet() else oldTab.loaded.mapTo(HashSet()) { it.id }
            enqueueHydration(page.rows.filterNot { it.id in previousIds }, version, bypassMetadataMiss)
        }
        return PageOutcome.LOADED
    }

    /** Resets every tab's cursor to page 0. Loaded rows are kept
     *  visible until their tab's page 0 replaces them; unvisited tabs
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
    }

    private fun enqueueHydration(rows: List<DebridDownload>, version: Int, bypassMiss: Boolean = false) {
        val metadata = _state.value.metadata
        for (download in rows) {
            if ((download.kind to download.id) in metadata) continue
            val ref = DownloadRef(download.kind, download.id)
            val queued = hydrationQueue[ref]
            if (queued == null || queued.first != download || (bypassMiss && !queued.second)) {
                hydrationQueue[ref] = download to bypassMiss
            }
        }
        if (hydrationQueue.isEmpty() || hydrationJob?.isActive == true) return
        hydrationJob = viewModelScope.launch { hydrateRows(version) }
    }

    private suspend fun hydrateRows(version: Int) {
        // Layout may not have reported the viewport yet on the first page.
        // Seed its first batch in display order when there is no viewport.
        val initialKeys = if (visibleRowKeys.isEmpty() && hydrationQueue.size > 4) {
            val state = _state.value
            val rows = hydrationQueue.values.map { it.first }
            withContext(backgroundDispatcher) {
                prepareVisible(rows, state.query, state.sort, state.preferWords)[state.tab]
                    .orEmpty().take(4).mapTo(HashSet()) { "${it.kind}:${it.id}" }
            }
        } else emptySet()

        val pending = LinkedHashMap<Pair<DownloadKind, String>, MediaMetadata>()
        val pendingOverrides = LinkedHashMap<Pair<DownloadKind, String>, MetadataOverride>()
        var lastFlush = System.currentTimeMillis()

        fun flushMetadata() {
            if (pending.isEmpty()) return
            _state.update { state ->
                val newMetadata = pending.filterKeys { it !in state.metadata }
                state.copy(
                    metadata = state.metadata + newMetadata,
                    metadataOverrides = state.metadataOverrides + pendingOverrides.filterKeys { it in newMetadata },
                )
            }
            pending.clear()
            pendingOverrides.clear()
            lastFlush = System.currentTimeMillis()
        }

        var initialBatch = true
        while (version == refreshVersion && hydrationQueue.isNotEmpty()) {
            val visible = if (initialBatch) visibleRowKeys.ifEmpty { initialKeys } else visibleRowKeys
            initialBatch = false
            val batchSize = minOf(4, 10 - pending.size)
            val prioritized = hydrationQueue.values.asSequence()
                .filter { (row, _) -> "${row.kind}:${row.id}" in visible }
                .take(batchSize).toList()
            val batch = prioritized.ifEmpty { hydrationQueue.values.take(batchSize) }
            val results = coroutineScope {
                batch.map { (download, bypassMiss) ->
                    async {
                        val key = download.kind to download.id
                        if (key in _state.value.metadata) return@async null
                        val storedOverride = try { repository.loadMetadataOverride(DownloadRef(download.kind, download.id)) }
                            catch (e: CancellationException) { throw e }
                            catch (_: Exception) { null }
                        val metadata = try {
                            storedOverride?.let { repository.resolveMetadataById(it.imdbId, it.type) }
                                ?: repository.resolveMetadataForRelease(download.name, bypassMiss)
                        } catch (e: CancellationException) { throw e }
                          catch (_: Exception) { null }
                        if (metadata == null) null else Triple(key, metadata, storedOverride)
                    }
                }.awaitAll().filterNotNull()
            }
            currentCoroutineContext().ensureActive()
            if (version != refreshVersion) return

            // A new page can replace a row while its previous name is resolving.
            val currentRows = batch.filter { request ->
                val row = request.first
                hydrationQueue.remove(DownloadRef(row.kind, row.id), request)
            }.mapTo(HashSet()) { (row, _) -> row.kind to row.id }
            for ((key, metadata, storedOverride) in results) {
                if (key !in currentRows) continue
                pending[key] = metadata
                if (storedOverride != null) pendingOverrides[key] = storedOverride
            }
            val viewportFilled = visibleRowKeys.isNotEmpty() && prioritized.isNotEmpty() &&
                hydrationQueue.values.none { (row, _) -> "${row.kind}:${row.id}" in visibleRowKeys }
            if (viewportFilled || pending.size >= 10 || System.currentTimeMillis() - lastFlush >= 200) {
                flushMetadata()
            }
        }
        flushMetadata()
    }

    /** Each pager page filters its own kind, including while it is offscreen. */
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
        viewModelScope.launch {
            if (key in _state.value.metadata) return@launch
            val storedOverride = try { repository.loadMetadataOverride(DownloadRef(download.kind, download.id)) }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { null }
            if (storedOverride != null) {
                val metadata = try { repository.resolveMetadataById(storedOverride.imdbId, storedOverride.type) }
                    catch (e: CancellationException) { throw e }
                    catch (_: Exception) { null }
                if (metadata != null) {
                    _state.update {
                        it.copy(metadata = it.metadata + (key to metadata),
                            metadataOverrides = it.metadataOverrides + (key to storedOverride))
                    }
                    return@launch
                }
            }
            val candidates = try {
                repository.resolveMetadataCandidates(app.trove.debrid.core.parseMediaName(download.name))
            } catch (e: CancellationException) { throw e }
              catch (_: Exception) { emptyList() }
            if (candidates.isNotEmpty()) {
                _state.update { it.copy(candidates = it.candidates + (key to candidates)) }
            }
        }
    }

    /** Stores the user's pick for this download only, then shows the
     *  picked title. The pick is keyed by (kind, id). */
    fun pickCandidate(download: DebridDownload, candidate: MediaCandidate) {
        val key = download.kind to download.id
        if (_state.value.filePicker?.let { it.kind to it.id } != key) return
        viewModelScope.launch {
            val pick = MetadataOverride(candidate.imdbId, candidate.type, candidate.title)
            try { repository.saveMetadataOverride(DownloadRef(download.kind, download.id), pick) }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { return@launch }
            hydrationQueue.remove(DownloadRef(download.kind, download.id))
            val metadata = try { repository.resolveMetadataById(candidate.imdbId, candidate.type) }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { null }
            _state.update {
                it.copy(
                    metadataOverrides = it.metadataOverrides + (key to pick),
                    metadata = if (metadata != null) it.metadata + (key to metadata) else it.metadata,
                )
            }
        }
    }

    /** Drops the stored pick and re-resolves normally, so the parsed
     *  title — and the picker, when the match still fails — comes back. */
    fun clearMetadataOverride(download: DebridDownload) {
        val key = download.kind to download.id
        if (_state.value.filePicker?.let { it.kind to it.id } != key) return
        viewModelScope.launch {
            try { repository.clearMetadataOverride(DownloadRef(download.kind, download.id)) }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { return@launch }
            hydrationQueue.remove(DownloadRef(download.kind, download.id))
            _state.update { it.copy(metadataOverrides = it.metadataOverrides - key, metadata = it.metadata - key) }
            val metadata = try { repository.resolveMetadataForRelease(download.name) }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { null }
            if (metadata != null) {
                _state.update { it.copy(metadata = it.metadata + (key to metadata)) }
            } else {
                loadCandidatesFor(download)
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
    val comparator = compareByDescending<DownloadSortKeys> { it.preference }.then(sortComparator)

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
