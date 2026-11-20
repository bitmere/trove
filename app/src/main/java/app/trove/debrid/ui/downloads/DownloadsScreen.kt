package app.trove.debrid.ui.downloads

import app.trove.debrid.R
import androidx.compose.ui.res.stringResource

import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Clear
import androidx.compose.ui.res.painterResource
import androidx.compose.material.icons.filled.Check
import app.trove.debrid.core.DownloadSort
import androidx.compose.material.icons.filled.AccountCircle
import app.trove.debrid.ui.theme.SpaceGrotesk
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.trove.debrid.ui.theme.TroveSpace
import app.trove.debrid.ui.theme.TroveSize
import app.trove.debrid.core.DebridDownload
import app.trove.debrid.core.DownloadKind
import app.trove.debrid.core.DownloadStatus
import app.trove.debrid.ui.CaptureTick
import app.trove.debrid.ui.FloatingActions
import app.trove.debrid.ui.SearchBackdrop
import app.trove.debrid.ui.backdropSource
import app.trove.debrid.ui.rememberBackdropBlurEffect
import app.trove.debrid.ui.rememberBackdropBlurEnabled
import kotlinx.coroutines.launch

/** Download library with compact rows and a focused playback sheet. */
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun DownloadsScreen(
    viewModel: DownloadsViewModel,
    onOpenSettings: () -> Unit,
    onAdd: () -> Unit,
    onPlayUrl: suspend (String, String?, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val pagerState = rememberPagerState(initialPage = state.tab.ordinal) { DownloadKind.entries.size }
    val scope = rememberCoroutineScope()
    // Keep saveable list states above the pager: lazy page disposal and data
    // updates must not recreate a tab's position.
    val torrentListState = rememberLazyListState()
    val webListState = rememberLazyListState()
    val usenetListState = rememberLazyListState()
    val listStates = remember(torrentListState, webListState, usenetListState) {
        mapOf(
            DownloadKind.TORRENT to torrentListState,
            DownloadKind.WEB to webListState,
            DownloadKind.USENET to usenetListState,
        )
    }
    val selectedTab = DownloadKind.entries[pagerState.currentPage]
    val listState = listStates.getValue(selectedTab)
    // The pager owns selection for both gestures and animated tab clicks.
    // Do not drive it back from state.tab: that would interrupt a swipe.
    LaunchedEffect(pagerState, viewModel) {
        snapshotFlow { pagerState.currentPage }.collect { page ->
            viewModel.setTab(DownloadKind.entries[page])
        }
    }
    LaunchedEffect(selectedTab, listState, viewModel) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.mapNotNull { it.key as? String }.toSet() }
            .collect { viewModel.updateVisibleRows(selectedTab, it) }
    }
    val searchFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    // Observe offsets only once a fling settles, not on every scroll frame.
    val settledPosition by remember(listState, pagerState) {
        derivedStateOf {
            if (listState.isScrollInProgress || pagerState.isScrollInProgress) null
            else listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
        }
    }

    // One transient snapshot only while stationary; scrolling uses the scrim.
    val blurEnabled = rememberBackdropBlurEnabled()
    val blurEffect = rememberBackdropBlurEffect()
    val captureLayer = rememberGraphicsLayer()
    val captureTick = remember { CaptureTick() }
    var contentPosInRoot by remember { mutableStateOf(Offset.Zero) }
    var searchPosInRoot by remember { mutableStateOf(Offset.Zero) }

    // Memoized so tab/query filtering and counting do not redo list
    // work on every recomposition.
    // Counts are loaded-so-far per tab; a "+" marks a tab with more
    // pages still to load (Step 2). Unvisited tabs have no count yet.
    val counts = remember(state.pagedByTab) {
        state.pagedByTab.mapValues { it.value.loaded.size }
    }
    val hasMorePages = remember(state.pagedByTab) {
        state.pagedByTab.mapValues { it.value.visited && !it.value.endReached }
    }
    val visibleByTab = remember(state.downloads, state.query, state.sort, state.preferWords, state.visibleByTab) {
        DownloadKind.entries.associateWith { viewModel.visibleDownloads(state, it) }
    }
    val visible = visibleByTab.getValue(selectedTab)
    val hasVisibleDownloads = visible.isNotEmpty()
    val showGoToTop by remember(listState, hasVisibleDownloads) {
        derivedStateOf {
            shouldShowGoToTop(hasVisibleDownloads, listState.firstVisibleItemIndex,
                listState.firstVisibleItemScrollOffset)
        }
    }

    // Step 2b — scroll-anchor + process-death restoration. Only small
    // non-secret UI state is saved: the query, the anchor row (kind +
    // id + visible index + the sort/query it was captured under), and
    // the opaque account identity (provider service name). The sort
    // already persists in Settings preferences, and the tab survives
    // through the pager's own saved state. No keys or credentials.
    var savedQuery by rememberSaveable { mutableStateOf("") }
    var savedAnchorKind by rememberSaveable { mutableStateOf<String?>(null) }
    var savedAnchorId by rememberSaveable { mutableStateOf<String?>(null) }
    var savedAnchorIndex by rememberSaveable { mutableStateOf(0) }
    var savedAnchorSort by rememberSaveable { mutableStateOf<String?>(null) }
    var savedAnchorQuery by rememberSaveable { mutableStateOf("") }
    var savedAnchorAccount by rememberSaveable { mutableStateOf<String?>(null) }
    // Restore first: this runs before the sync effect below, so a
    // restored query is not overwritten by the fresh VM's blank one.
    LaunchedEffect(Unit) {
        if (savedQuery.isNotBlank() && state.query.isBlank()) {
            viewModel.setQuery(savedQuery)
        }
    }
    val anchorAccount = viewModel.currentAccountIdentity()
    var offeredSavedAnchor by remember { mutableStateOf(false) }
    LaunchedEffect(anchorAccount) {
        if (anchorAccount == null || offeredSavedAnchor) return@LaunchedEffect
        offeredSavedAnchor = true
        val kind = savedAnchorKind?.let { runCatching { DownloadKind.valueOf(it) }.getOrNull() }
        val sort = savedAnchorSort?.let { runCatching { DownloadSort.valueOf(it) }.getOrNull() }
        val id = savedAnchorId
        if (kind != null && sort != null && id != null) {
            viewModel.setPendingAnchor(
                ScrollAnchor(kind, id, savedAnchorIndex, sort, savedAnchorQuery),
                savedAnchorAccount,
            )
        }
    }
    LaunchedEffect(state.query) { savedQuery = state.query }
    // Capture the anchor only once a fling settles, not on every frame.
    LaunchedEffect(settledPosition) {
        val position = settledPosition ?: return@LaunchedEffect
        if (visible.isEmpty()) return@LaunchedEffect
        viewModel.captureAnchor(selectedTab, position.first)?.let { anchor ->
            savedAnchorKind = anchor.kind.name
            savedAnchorId = anchor.id
            savedAnchorIndex = anchor.index
            savedAnchorSort = anchor.sort.name
            savedAnchorQuery = anchor.query
            savedAnchorAccount = viewModel.currentAccountIdentity()
        }
    }

    // Content, scroll completion and navigation invalidate the snapshot.
    // A fresh composition after navigation also gets a fresh identity and layer.
    val captureContent = remember(
        visible, selectedTab, state.query, state.loading,
        state.signedOut, state.refreshing, state.accountLabel, settledPosition,
    ) { Any() }
    val listPositions = remember { mutableStateMapOf<DownloadKind, Offset>() }
    val listPosInRoot = listPositions[selectedTab] ?: Offset.Zero
    val searchHeight = with(androidx.compose.ui.platform.LocalDensity.current) { 48.dp.toPx() }
    val hasBackdropContent = settledPosition != null && !state.refreshing && !state.loading && !state.signedOut && visible.isNotEmpty() &&
        listState.layoutInfo.visibleItemsInfo.any { item ->
            val top = listPosInRoot.y + item.offset
            searchPosInRoot.y < top + item.size && searchPosInRoot.y + searchHeight > top
        }

    val detailsSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var dismissingForPlayback by remember { mutableStateOf(false) }
    val noPlayerMessage = stringResource(R.string.no_player)
    val playerFailureMessage = stringResource(R.string.player_failed)
    LaunchedEffect(state.playUrl) {
        state.playUrl?.let {
            try {
                if (state.filePicker != null) {
                    dismissingForPlayback = true
                    detailsSheetState.hide()
                    viewModel.dismissFilePicker()
                    // Let Compose remove the modal window before the system chooser opens.
                    androidx.compose.runtime.withFrameNanos { }
                }
                onPlayUrl(it, state.playFileName, state.askEveryTime)
            } catch (_: android.content.ActivityNotFoundException) {
                viewModel.playbackError(noPlayerMessage)
            } catch (_: SecurityException) {
                viewModel.playbackError(playerFailureMessage)
            } finally {
                dismissingForPlayback = false
                viewModel.consumePlayUrl()
            }
        }
    }
    val settingsLabel = stringResource(R.string.settings)
    LaunchedEffect(state.error) {
        state.error?.let {
            val result = snackbarHostState.showSnackbar(
                message = it,
                actionLabel = if (state.authenticationRequired) settingsLabel else null,
            )
            if (result == SnackbarResult.ActionPerformed) onOpenSettings()
            viewModel.clearError()
        }
    }

    val addedMessage = state.addedConfirmation?.let { kind ->
        stringResource(R.string.download_added_to, kindWord(kind))
    }
    LaunchedEffect(addedMessage) {
        if (addedMessage != null) {
            snackbarHostState.showSnackbar(addedMessage)
            viewModel.consumeAddedConfirmation()
        }
    }

    Scaffold(
        modifier = modifier.safeDrawingPadding(),
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { insets ->
    Box(Modifier.fillMaxSize().padding(insets)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .onGloballyPositioned { contentPosInRoot = it.localToRoot(Offset.Zero) }
                .then(
                    if (blurEnabled && hasBackdropContent) Modifier.backdropSource(captureLayer, 0, captureTick, captureContent)
                    else Modifier,
                ),
        ) {
            TopAppBar(
                title = {
                    Text(stringResource(R.string.app_name), fontFamily = SpaceGrotesk,
                        style = MaterialTheme.typography.titleMedium)
                },
                actions = {
                    state.accountLabel?.let {
                            SuggestionChip(onClick = onOpenSettings, label = { Text(it) },
                                colors = SuggestionChipDefaults.suggestionChipColors(
                                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                    labelColor = MaterialTheme.colorScheme.onSecondaryContainer), border = null)
                        }
                    FilledTonalIconButton(onClick = onOpenSettings, modifier = Modifier.padding(end = TroveSpace.compact)) {
                        Icon(Icons.Filled.AccountCircle, contentDescription = stringResource(R.string.account_settings),
                            tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(TroveSpace.section))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                windowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
            )
            Subtabs(counts, hasMorePages, pagerState) { kind ->
                scope.launch { pagerState.animateScrollToPage(kind.ordinal) }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = TroveSpace.medium, vertical = TroveSpace.small),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SearchBar(
                    inputField = {
                        SearchBarDefaults.InputField(
                            query = state.query,
                            onQueryChange = viewModel::setQuery,
                            onSearch = { keyboard?.hide() },
                            expanded = false,
                            onExpandedChange = {},
                            placeholder = { Text(stringResource(R.string.search_downloads), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                            trailingIcon = {
                                if (state.query.isNotEmpty()) IconButton(onClick = { viewModel.setQuery("") }) {
                                    Icon(Icons.Filled.Clear, contentDescription = stringResource(R.string.clear_search))
                                }
                            },
                            modifier = Modifier.focusRequester(searchFocus),
                        )
                    },
                    expanded = false,
                    onExpandedChange = {},
                    colors = SearchBarDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                    modifier = Modifier.weight(1f),
                ) {}
                if (state.query.isNotBlank() || state.sort != DownloadSort.NEWEST) {
                    TextButton(onClick = {
                        viewModel.resetSearch()
                        scope.launch { listState.animateScrollToItem(0) }
                    }) {
                        // Reset clears both the query and the sort.
                        Text(stringResource(R.string.reset))
                    }
                }
                DownloadSortMenu(state.sort, viewModel::setSort)
            }


            HorizontalPager(
                state = pagerState,
                key = { DownloadKind.entries[it] },
                modifier = Modifier.fillMaxSize(),
            ) { page ->
                val tab = DownloadKind.entries[page]
                val pageDownloads = visibleByTab.getValue(tab)
                val tabState = state.pagedByTab[tab] ?: PagedTabState()
                val tabListState = listStates.getValue(tab)
                // Viewport appends (Step 2): when the last visible row
                // is within 10 of the end and more pages exist, load
                // the next page. A failed page blocks re-triggering
                // until Retry; a loading page blocks until it lands.
                LaunchedEffect(tabListState, pageDownloads.size, tabState.endReached, tabState.pageLoading, tabState.pageError) {
                    snapshotFlow { tabListState.layoutInfo.visibleItemsInfo.lastOrNull()?.index }.collect { lastIndex ->
                        if (lastIndex != null && pageDownloads.isNotEmpty() &&
                            lastIndex >= pageDownloads.size - 10 &&
                            !tabState.endReached && !tabState.pageLoading && tabState.pageError == null
                        ) {
                            viewModel.loadNextPage(tab)
                        }
                    }
                }
                // Step 2b: restore the saved scroll anchor once its row
                // is in the loaded set; if the tab finishes loading
                // without it, the anchor is dropped and the list stays
                // at the top.
                val pendingAnchor = state.pendingAnchor
                LaunchedEffect(pendingAnchor, pageDownloads, state.sort, state.query, tabState.endReached, tabState.pageLoading) {
                    val anchor = pendingAnchor ?: return@LaunchedEffect
                    if (anchor.kind != tab) return@LaunchedEffect
                    val index = viewModel.anchorRowIndex(anchor)
                    if (index != null) {
                        tabListState.scrollToItem(index)
                        viewModel.consumePendingAnchor()
                    } else if (tabState.endReached && !tabState.pageLoading) {
                        viewModel.consumePendingAnchor()
                    }
                }
                when {
                    state.loading -> LibraryLoading()

                    state.signedOut -> SignedOutState(onOpenSettings)

                    else -> {
                        PullToRefreshBox(
                            isRefreshing = state.refreshing,
                            onRefresh = { viewModel.refreshFromUser() },
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            if (pageDownloads.isEmpty()) {
                                // An unvisited tab has not loaded page 0
                                // yet: show a spinner, not the empty
                                // state, until its first page lands.
                                if (tabState.pageLoading || !tabState.visited) {
                                    LibraryLoading()
                                } else {
                                    LibraryEmptyState(tab, state.query, state.loadError, onAdd,
                                        { viewModel.setQuery("") }, { viewModel.refreshFromUser(fresh = false) })
                                }
                            } else {
                                LazyColumn(
                                    state = listStates.getValue(tab),
                                    contentPadding = PaddingValues(start = TroveSpace.medium, end = TroveSpace.medium, top = TroveSpace.small, bottom = TroveSize.downloadsBottomPadding),
                                    modifier = Modifier.fillMaxSize().onGloballyPositioned {
                                        listPositions[tab] = it.localToRoot(Offset.Zero)
                                    },
                                ) {
                                    items(pageDownloads, key = { "${it.kind}:${it.id}" }) { download ->
                                        DownloadRow(
                                            download = download,
                                            metadata = state.metadata[download.kind to download.id],
                                            refreshing = state.refreshing,
                                            resolving = state.resolvingDownload == (download.kind to download.id),
                                            playEnabled = state.resolvingDownload == null && state.playUrl == null,
                                            onPlay = { if (viewModel.isCurrentDownload(download)) viewModel.play(download) },
                                            onOpen = { if (viewModel.isCurrentDownload(download)) viewModel.openDownload(download) },
                                            onLongPress = { if (viewModel.isCurrentDownload(download)) viewModel.requestDelete(download) },
                                        )
                                    }
                                    // A failed page never clears loaded
                                    // rows: Retry re-requests only it.
                                    val pageError = tabState.pageError
                                    if (pageError != null) {
                                        item(key = "page-error") {
                                            PageErrorRow(pageError) { viewModel.retryPage(tab) }
                                        }
                                    } else if (tabState.pageLoading) {
                                        item(key = "page-loading") {
                                            LoadingMoreRow()
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        FloatingActions(
            onAdd = onAdd,
            onSearch = {
                searchFocus.requestFocus()
                keyboard?.show()
            },
            searchBackdrop = if (blurEnabled && hasBackdropContent) {
                SearchBackdrop(
                    layer = captureLayer,
                    offset = searchPosInRoot - contentPosInRoot,
                    blurEffect = blurEffect,
                    isCurrent = { !captureTick.needsCapture(0, captureContent) },
                )
            } else {
                null
            },
            goToTopVisible = showGoToTop,
            onGoToTop = { scope.launch { listState.animateScrollToItem(0) } },
            onSearchPositioned = { searchPosInRoot = it },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = TroveSpace.page, bottom = TroveSpace.page),
        )
    }
    }

    state.filePicker?.let { download ->
        val key = download.kind to download.id
        val metadata = state.metadata[key]
        val candidates = state.candidates[key].orEmpty()
        val hasOverride = key in state.metadataOverrides
        val resolving = state.resolvingDownload != null
        val playPending = state.playUrl != null
        val resolvingFileId = state.resolvingFileId.takeIf { state.resolvingDownload == key }
        val error = state.error
        val details = remember(metadata, candidates, hasOverride, resolving, playPending, resolvingFileId, error) {
            DownloadDetailsState(metadata, candidates, hasOverride, resolving, playPending, resolvingFileId, error)
        }
        DownloadDetailsSheet(
            download = download, state = details, sheetState = detailsSheetState,
            dismissing = dismissingForPlayback, showClutterFiles = state.showClutterFiles,
            onClose = {
                if (resolving && download.files.isEmpty()) {
                    viewModel.dismissFilePicker()
                } else {
                    scope.launch { detailsSheetState.hide(); viewModel.dismissFilePicker() }
                }
            },
            onPlay = { if (viewModel.isCurrentDownload(download)) viewModel.playFile(download, it) },
            onPlayWith = { if (viewModel.isCurrentDownload(download)) viewModel.playFile(download, it, askEveryTime = true) },
            onPickCandidate = { if (viewModel.isCurrentDownload(download)) viewModel.pickCandidate(download, it) },
            onClearOverride = { if (viewModel.isCurrentDownload(download)) viewModel.clearMetadataOverride(download) },
            onDelete = {
                scope.launch {
                    detailsSheetState.hide()
                    if (viewModel.isCurrentDownload(download)) viewModel.requestDelete(download)
                }
            },
        )
    }

    state.deleteConfirm?.let { download ->
        AlertDialog(
            onDismissRequest = viewModel::dismissDelete,
            title = { Text(stringResource(R.string.delete_title)) },
            text = { Text(stringResource(R.string.delete_body, download.name)) },
            confirmButton = {
                TextButton(onClick = viewModel::confirmDelete) {
                    Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissDelete) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Subtabs(
    counts: Map<DownloadKind, Int>,
    hasMorePages: Map<DownloadKind, Boolean>,
    pagerState: PagerState,
    onSelect: (DownloadKind) -> Unit,
) {
    val selected = DownloadKind.entries[pagerState.currentPage]
    // Keep the stock indicator. The 0.1.10 phone result ruled out the
    // removed custom indicator as the cause of the blank launch window.
    PrimaryScrollableTabRow(
        selectedTabIndex = selected.ordinal,
        edgePadding = TroveSpace.compact,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        DownloadKind.entries.forEach { kind ->
            val label = when (kind) {
                DownloadKind.TORRENT -> stringResource(R.string.tab_torrents)
                DownloadKind.WEB -> stringResource(R.string.kind_web)
                DownloadKind.USENET -> stringResource(R.string.kind_usenet)
            }
            Tab(selected = kind == selected, onClick = { onSelect(kind) },
                text = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1, softWrap = false)
                        CountPill(counts[kind] ?: 0, selected = kind == selected,
                            hasMore = hasMorePages[kind] == true)
                    }
                })
        }
    }
}

@Composable
private fun LibraryLoading() {
    val loading = stringResource(R.string.loading_library)
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(
            modifier = Modifier.semantics {
                liveRegion = LiveRegionMode.Polite
                stateDescription = loading
            },
        )
    }
}

/** A failed page keeps its loaded rows: this row offers a Retry for
 *  just that page. */
@Composable
private fun PageErrorRow(message: String, onRetry: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = TroveSpace.small),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(TroveSpace.small),
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onRetry) {
            Text(stringResource(R.string.try_again))
        }
    }
}

@Composable
private fun LoadingMoreRow() {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = TroveSpace.small),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(TroveSpace.small, Alignment.CenterHorizontally),
    ) {
        CircularProgressIndicator(
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(TroveSpace.section),
        )
        Text(
            text = stringResource(R.string.loading_more),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SignedOutState(onOpenSettings: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(TroveSpace.section),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.connect_library), style = MaterialTheme.typography.headlineMedium)
        androidx.compose.foundation.layout.Spacer(Modifier.height(TroveSpace.medium))
        Text(
            text = stringResource(R.string.connect_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = onOpenSettings) {
            Text(stringResource(R.string.open_settings), color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
internal fun statusWord(status: DownloadStatus): String = when (status) {
    DownloadStatus.QUEUED -> stringResource(R.string.queued)
    DownloadStatus.DOWNLOADING -> stringResource(R.string.downloading)
    DownloadStatus.READY -> stringResource(R.string.ready)
    DownloadStatus.PAUSED -> stringResource(R.string.paused)
    DownloadStatus.ERROR -> stringResource(R.string.error)
    DownloadStatus.UNKNOWN -> stringResource(R.string.unknown)
}


@Composable
private fun DownloadSortMenu(selected: DownloadSort, onSelect: (DownloadSort) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(painterResource(R.drawable.ic_sort), contentDescription = stringResource(R.string.sort_downloads, sortLabel(selected)))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DownloadSort.entries.forEach { sort ->
                DropdownMenuItem(
                    text = { Text(sortLabel(sort)) },
                    onClick = { onSelect(sort); expanded = false },
                    trailingIcon = if (sort == selected) {
                        { Icon(Icons.Filled.Check, contentDescription = stringResource(R.string.sort_selected)) }
                    } else null,
                )
            }
        }
    }
}

@Composable
private fun sortLabel(sort: DownloadSort): String = stringResource(when (sort) {
    DownloadSort.NEWEST -> R.string.sort_newest
    DownloadSort.OLDEST -> R.string.sort_oldest
    DownloadSort.NAME_ASC -> R.string.sort_name_asc
    DownloadSort.NAME_DESC -> R.string.sort_name_desc
    DownloadSort.LARGEST -> R.string.sort_largest
    DownloadSort.SMALLEST -> R.string.sort_smallest
    DownloadSort.PROGRESS -> R.string.sort_progress
})
