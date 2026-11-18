package app.trove.debrid.ui.downloads

import app.trove.debrid.R
import androidx.compose.ui.res.stringResource

import androidx.compose.animation.animateContentSize
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
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import app.trove.debrid.ui.theme.TroveSpace
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

/** Download library with tonal cards and a focused playback sheet. */
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
    val counts = remember(state.downloads) {
        state.downloads.groupBy { it.kind }.mapValues { it.value.size }
    }
    val visibleByTab = remember(state.downloads, state.query, state.sort) {
        DownloadKind.entries.associateWith { viewModel.visibleDownloads(state, it) }
    }
    val visible = visibleByTab.getValue(selectedTab)

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
                    Column {
                        Text(stringResource(R.string.app_name), fontFamily = SpaceGrotesk,
                            style = MaterialTheme.typography.headlineLarge)
                        Text(stringResource(R.string.library_subtitle), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
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
            Subtabs(counts, pagerState) { kind ->
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
                            placeholder = { Text(stringResource(R.string.search_downloads)) },
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
                DownloadSortMenu(state.sort, viewModel::setSort)
            }


            HorizontalPager(
                state = pagerState,
                key = { DownloadKind.entries[it] },
                modifier = Modifier.fillMaxSize(),
            ) { page ->
                val tab = DownloadKind.entries[page]
                val pageDownloads = visibleByTab.getValue(tab)
                when {
                    state.loading -> Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) { CircularProgressIndicator(color = MaterialTheme.colorScheme.primary) }

                    state.signedOut -> SignedOutState(onOpenSettings)

                    else -> {
                        PullToRefreshBox(
                            isRefreshing = state.refreshing,
                            onRefresh = viewModel::refresh,
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            if (pageDownloads.isEmpty()) {
                                LibraryEmptyState(tab, state.query, state.loadError, onAdd,
                                    { viewModel.setQuery("") }, viewModel::refresh)
                            } else {
                                LazyColumn(
                                    state = listStates.getValue(tab),
                                    contentPadding = PaddingValues(start = TroveSpace.medium, end = TroveSpace.medium, top = TroveSpace.small, bottom = 200.dp),
                                    verticalArrangement = Arrangement.spacedBy(TroveSpace.compact),
                                    modifier = Modifier.fillMaxSize().onGloballyPositioned {
                                        listPositions[tab] = it.localToRoot(Offset.Zero)
                                    },
                                ) {
                                    items(pageDownloads, key = { "${it.kind}:${it.id}" }) { download ->
                                        DownloadRow(
                                            download = download,
                                            metadata = state.metadata[download.kind to download.id],
                                            resolving = state.resolvingDownload == (download.kind to download.id),
                                            playEnabled = state.resolvingDownload == null && state.playUrl == null,
                                            onPlay = { if (viewModel.isCurrentDownload(download)) viewModel.play(download) },
                                            onOpen = { if (viewModel.isCurrentDownload(download)) viewModel.openDownload(download) },
                                            onLongPress = { if (viewModel.isCurrentDownload(download)) viewModel.requestDelete(download) },
                                        )
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
            onSearchPositioned = { searchPosInRoot = it },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = TroveSpace.page, bottom = TroveSpace.page),
        )

    }
    }

    state.filePicker?.let { download ->
        DownloadDetailsSheet(
            download = download, state = state, sheetState = detailsSheetState,
            dismissing = dismissingForPlayback,
            onClose = {
                if (state.resolvingDownload != null && download.files.isEmpty()) {
                    viewModel.dismissFilePicker()
                } else {
                    scope.launch { detailsSheetState.hide(); viewModel.dismissFilePicker() }
                }
            },
            onPlay = { if (viewModel.isCurrentDownload(download)) viewModel.playFile(download, it) },
            onPlayWith = { if (viewModel.isCurrentDownload(download)) viewModel.playFile(download, it, askEveryTime = true) },
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
                        CountPill(counts[kind] ?: 0, selected = kind == selected)
                    }
                })
        }
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
