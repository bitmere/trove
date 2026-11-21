package app.trove.debrid.ui.downloads

import app.trove.debrid.R
import androidx.compose.ui.res.stringResource

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import app.trove.debrid.core.*
import app.trove.debrid.ui.theme.TroveSpace
import java.util.Locale

internal data class DownloadDetailsState(
    val metadata: MediaMetadata?,
    val candidates: List<MediaCandidate>,
    val hasOverride: Boolean,
    val resolving: Boolean,
    val playPending: Boolean,
    val resolvingFileId: String?,
    val error: String?,
    val provenance: EnrichmentProvenance? = metadata?.provenance,
    val posterUrl: String? = metadata?.posterUrl,
    /** Whether a TMDB key is configured, for the picker's no-key hint. */
    val tmdbConfigured: Boolean = false,
    /** The signed-in user's SIMKL entry for this title, if any. */
    val simklEntry: SimklEntry? = null,
    /** Feedback for a hand-entered TMDB search, if one ran. */
    val manualTmdbSearch: ManualTmdbSearchStatus? = null,
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun DownloadDetailsSheet(
    download: DebridDownload,
    state: DownloadDetailsState,
    sheetState: SheetState,
    dismissing: Boolean,
    onClose: () -> Unit,
    onPlay: (String) -> Unit,
    onPlayWith: (String) -> Unit,
    onDelete: () -> Unit,
    showClutterFiles: Boolean = false,
    onPickCandidate: (MediaCandidate) -> Unit = {},
    onClearOverride: () -> Unit = {},
    onPosterFailure: suspend (String) -> Unit = {},
    /** Opens a SIMKL item page (the attribution link). */
    onSimklClick: (String) -> Unit = {},
    /** Hand-entered TMDB search from the picker's manual link row. */
    onManualTmdbSearch: (String) -> Unit = {},
) {
    val visibleFiles = remember(download.files, showClutterFiles) {
        if (showClutterFiles) download.files else download.files.filterNot { isClutterFile(it.name) }
    }
    val metadata = state.metadata
    val poster = key(download.kind, download.id, download.name) {
        rememberPoster(state.posterUrl, onPosterFailure)
    }
    val candidates = state.candidates
    val title = remember(download.name, metadata) {
        downloadIdentity(download, metadata).title
    }
    val episodes = metadata?.episodes.orEmpty()
    val matchedEpisode = remember(episodes, download.name) {
        if (episodes.isEmpty()) null else matchingEpisode(episodes, parseReleaseDetails(download.name).episode)
    }
    val currentSeason = matchedEpisode?.season ?: episodes.firstOrNull()?.season
    var showAllEpisodes by remember(download.kind, download.id) { mutableStateOf(false) }
    var manualTmdbInput by remember(download.kind, download.id) { mutableStateOf("") }
    val seasonEpisodes = remember(episodes, currentSeason) { episodes.filter { it.season == currentSeason } }
    val visibleEpisodes = if (showAllEpisodes) episodes else seasonEpisodes
    val busy = state.resolving || state.playPending || dismissing
    val canClose = !busy || (state.resolving && download.files.isEmpty() && !dismissing)
    val ready = download.status == DownloadStatus.READY && visibleFiles.isNotEmpty()
    ModalBottomSheet(
        onDismissRequest = { if (canClose) onClose() }, sheetState = sheetState,
        sheetGesturesEnabled = canClose,
        // Keep measured content independent of the sheet offset. A device trace
        // is still needed to confirm whether inset feedback caused the bounce.
        contentWindowInsets = { WindowInsets(0, 0, 0, 0) },
        dragHandle = {
            // Read the dialog's safe area without subtracting the top inset
            // Material marks consumed as the sheet moves.
            val top = WindowInsets.safeDrawing.asPaddingValues().calculateTopPadding()
            Box(Modifier.padding(top = top)) { BottomSheetDefaults.DragHandle() }
        },
    ) {
        val listInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)
        LazyColumn(
            modifier = Modifier.fillMaxWidth().padding(listInsets.asPaddingValues()),
            contentPadding = PaddingValues(start = TroveSpace.large, end = TroveSpace.large, bottom = TroveSpace.section),
            verticalArrangement = Arrangement.spacedBy(TroveSpace.medium),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(TroveSpace.compact)) {
                    val subtitle = stringResource(
                        R.string.details_metadata, kindWord(download.kind),
                        formatSize(download.sizeBytes), statusWord(download.status),
                    )
                    if (!poster.hasPoster) {
                        DetailsTitle(title, subtitle)
                    } else {
                        BoxWithConstraints(Modifier.fillMaxWidth()) {
                            val size = detailsPosterSize(maxWidth, LocalDensity.current.fontScale)
                            Row(horizontalArrangement = Arrangement.spacedBy(TroveSpace.medium)) {
                                MetadataPoster(poster, size)
                                Column(Modifier.weight(1f)) { DetailsTitle(title, subtitle) }
                            }
                        }
                    }
                    if (title != download.name) MetadataLine(download.name)
                    addedDate(download.createdAtEpochMs)?.let {
                        MetadataLine(stringResource(R.string.added_date, it))
                    }
                    MetadataLine(stringResource(R.string.files_count, download.files.size))
                    DownloadTransfer(download)
                    state.error?.let { app.trove.debrid.ui.Feedback(it, error = true) }
                    if (busy && download.files.isEmpty()) {
                        val preparing = stringResource(R.string.preparing)
                        LinearProgressIndicator(Modifier.fillMaxWidth().semantics {
                            liveRegion = LiveRegionMode.Polite
                            stateDescription = preparing
                        })
                    } else if (!ready) Text(
                        if (visibleFiles.isEmpty() && download.files.isNotEmpty()) stringResource(R.string.no_files)
                        else if (download.status != DownloadStatus.READY) stringResource(R.string.play_when_ready)
                        else stringResource(R.string.no_files),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (metadata != null) {
                val facts = listOfNotNull(
                    metadata.year?.toString(),
                    metadata.genres.takeIf { it.isNotEmpty() }?.joinToString(", "),
                    metadata.runtime?.takeIf { it.isNotBlank() },
                    metadata.imdbRating?.let { String.format(Locale.ROOT, "IMDb %.1f", it) },
                ).joinToString(" · ")
                val overview = metadata.overview?.takeIf { it.isNotBlank() }
                val director = metadata.director?.takeIf { it.isNotBlank() }
                if (overview != null || facts.isNotEmpty() || director != null || metadata.cast.isNotEmpty() || state.provenance != null) item {
                    Column(verticalArrangement = Arrangement.spacedBy(TroveSpace.compact)) {
                        overview?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                        if (facts.isNotEmpty()) MetadataLine(facts)
                        director?.let { MetadataLine(stringResource(R.string.directed_by, it), maxLines = 1) }
                        if (metadata.cast.isNotEmpty()) {
                            MetadataLine(stringResource(R.string.cast_list, metadata.cast.joinToString(", ")), maxLines = 2)
                        }
                        state.provenance?.let { MetadataLine(stringResource(it.label())) }
                    }
                }
            } else {
                state.provenance?.let { source -> item { MetadataLine(stringResource(source.label())) } }
            }
            state.simklEntry?.let { entry -> item {
                val status = stringResource(when (entry.status) {
                    SimklStatus.WATCHING -> R.string.simkl_status_watching
                    SimklStatus.PLAN_TO_WATCH -> R.string.simkl_status_plantowatch
                    SimklStatus.COMPLETED -> R.string.simkl_status_completed
                    SimklStatus.HOLD -> R.string.simkl_status_hold
                    SimklStatus.DROPPED -> R.string.simkl_status_dropped
                })
                val nextSeason = entry.nextSeason
                val nextEpisode = entry.nextEpisode
                val watched = entry.watchedEpisodes
                val total = entry.totalEpisodes
                val progress = when {
                    entry.type == SimklType.MOVIE -> null
                    nextSeason != null && nextEpisode != null && watched != null && total != null ->
                        stringResource(R.string.simkl_progress_next, nextSeason, nextEpisode, watched, total)
                    watched != null && total != null ->
                        stringResource(R.string.simkl_progress_counts, watched, total)
                    else -> null
                }
                val label = if (progress != null) stringResource(R.string.simkl_sheet_line, status, progress)
                    else stringResource(R.string.simkl_sheet_line_status, status)
                // Hold and dropped are sheet-only and shown muted.
                val muted = entry.status == SimklStatus.HOLD || entry.status == SimklStatus.DROPPED
                TextButton(onClick = { onSimklClick(entry.simklUrl) }) {
                    Text(label, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Start,
                        color = if (muted) MaterialTheme.colorScheme.onSurfaceVariant
                            else MaterialTheme.colorScheme.primary)
                }
            } }
            if (state.hasOverride) item {
                TextButton(onClick = onClearOverride) { Text(stringResource(R.string.clear_metadata_override)) }
            }
            if (metadata == null && candidates.isNotEmpty()) item {
                Column(verticalArrangement = Arrangement.spacedBy(TroveSpace.compact)) {
                    Text(stringResource(R.string.match_picker_title), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.match_picker_hint), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    candidates.forEach { candidate ->
                        val typeWord = stringResource(
                            if (candidate.type == "series") R.string.media_series else R.string.media_movie)
                        val label = buildString {
                            append(candidate.title)
                            candidate.year?.let { append(" ($it)") }
                            append(" · ")
                            append(typeWord)
                            if (candidate.tmdbId != null) {
                                append(" · ")
                                append(stringResource(R.string.match_picker_tmdb))
                            }
                        }
                        TextButton(onClick = { onPickCandidate(candidate) }, modifier = Modifier.fillMaxWidth()) {
                            Text(label, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Start)
                        }
                    }
                }
            }
            if (metadata == null && candidates.isEmpty() && !state.tmdbConfigured) item {
                Text(stringResource(R.string.match_picker_no_key_hint), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            // Hand-entered TMDB link: explicit user intent, so it is
            // offered even when Cinemeta already listed candidates.
            // Hidden without a TMDB key; the no-key hint covers that.
            if (metadata == null && state.tmdbConfigured) item {
                val searching = state.manualTmdbSearch == ManualTmdbSearchStatus.SEARCHING
                Column(verticalArrangement = Arrangement.spacedBy(TroveSpace.compact)) {
                    Text(stringResource(R.string.match_picker_manual_title), style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(
                        value = manualTmdbInput,
                        onValueChange = { manualTmdbInput = it },
                        label = { Text(stringResource(R.string.match_picker_manual_label)) },
                        singleLine = true,
                        enabled = !searching,
                        modifier = Modifier.fillMaxWidth().testTag("manual_tmdb_link_field"),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(TroveSpace.small)) {
                        Button(
                            onClick = { onManualTmdbSearch(manualTmdbInput) },
                            enabled = manualTmdbInput.isNotBlank() && !searching,
                        ) { Text(stringResource(R.string.match_picker_manual_search)) }
                        if (searching) CircularProgressIndicator(Modifier.size(24.dp))
                    }
                    when (state.manualTmdbSearch) {
                        ManualTmdbSearchStatus.NO_MATCH -> Text(
                            stringResource(R.string.match_picker_manual_no_match),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        ManualTmdbSearchStatus.INVALID -> Text(
                            stringResource(R.string.match_picker_manual_invalid),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        else -> Unit
                    }
                }
            }
            item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(TroveSpace.small)) {
                    Button(onClick = { visibleFiles.firstOrNull()?.let { onPlay(it.id) } }, enabled = ready && !busy) {
                        if (busy) PlaybackSpinner() else Icon(Icons.Filled.PlayArrow, null)
                        Spacer(Modifier.width(TroveSpace.small))
                        Text(if (busy) stringResource(R.string.preparing) else if (visibleFiles.size > 1) stringResource(R.string.play_first) else stringResource(R.string.play))
                    }
                    TextButton(onClick = { visibleFiles.firstOrNull()?.let { onPlayWith(it.id) } }, enabled = ready && !busy) {
                        Text(stringResource(R.string.play_with))
                    }
                    TextButton(onClick = onDelete, enabled = !busy,
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text(stringResource(R.string.delete)) }
                    TextButton(onClick = onClose, enabled = canClose) { Text(stringResource(R.string.close)) }
                }
            }
            if (currentSeason != null) {
                item {
                    Column {
                        Text(stringResource(R.string.episodes_count, episodes.size), style = MaterialTheme.typography.titleMedium)
                        TextButton(onClick = { showAllEpisodes = !showAllEpisodes }) {
                            Text(
                                if (showAllEpisodes) stringResource(R.string.show_season_only, currentSeason)
                                else stringResource(R.string.show_all_episodes, episodes.size)
                            )
                        }
                    }
                }
                items(visibleEpisodes, key = { it.season to it.episode }) { episode ->
                    Column {
                        Column(
                            Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(TroveSpace.micro),
                        ) {
                            val number = String.format(Locale.ROOT, "S%02dE%02d", episode.season, episode.episode)
                            val title = episode.title?.takeIf { it.isNotBlank() }
                            Text(if (title == null) number else "$number · $title", style = MaterialTheme.typography.bodyMedium)
                            if (episode == matchedEpisode) {
                                val accent = MaterialTheme.colorScheme.primary
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(TroveSpace.small),
                                ) {
                                    Box(Modifier.size(6.dp).background(accent, CircleShape))
                                    Text(stringResource(R.string.episode_matched),
                                        style = MaterialTheme.typography.labelMedium, color = accent)
                                }
                            }
                            episode.released?.takeIf { it.isNotBlank() }?.let { MetadataLine(it) }
                        }
                        HorizontalDivider(Modifier.padding(top = TroveSpace.small))
                    }
                }
            }
            if (visibleFiles.isNotEmpty()) item {
                Text(stringResource(R.string.files_count, visibleFiles.size), style = MaterialTheme.typography.titleMedium)
            }
            items(visibleFiles, key = { it.id }) { file ->
                Column {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(TroveSpace.compact)) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(TroveSpace.micro)) {
                            Text(baseFileName(file.name), style = MaterialTheme.typography.bodyLarge)
                            Text(formatSize(file.sizeBytes), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        FilledTonalIconButton(onClick = { onPlay(file.id) }, enabled = ready && !busy,
                            modifier = Modifier.size(48.dp)) {
                            val playFile = stringResource(R.string.play_file, baseFileName(file.name))
                            if (state.resolvingFileId == file.id) {
                                val preparing = stringResource(R.string.preparing_player)
                                PlaybackSpinner(Modifier.semantics {
                                    contentDescription = playFile
                                    liveRegion = LiveRegionMode.Polite
                                    stateDescription = preparing
                                })
                            } else {
                                Icon(Icons.Filled.PlayArrow, contentDescription = playFile)
                            }
                        }
                        Box {
                            var showActions by remember { mutableStateOf(false) }
                            IconButton(onClick = { showActions = true }, enabled = ready && !busy) {
                                Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.file_actions, baseFileName(file.name)))
                            }
                            DropdownMenu(expanded = showActions && !busy, onDismissRequest = { showActions = false }) {
                                DropdownMenuItem(text = { Text(stringResource(R.string.play_with)) }, onClick = {
                                    showActions = false
                                    onPlayWith(file.id)
                                })
                            }
                        }
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun MetadataLine(text: String, maxLines: Int = Int.MAX_VALUE) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}


internal fun detailsPosterSize(availableWidth: Dp, fontScale: Float): DpSize =
    if (availableWidth < 300.dp || fontScale >= 1.3f) DpSize(64.dp, 96.dp) else DpSize(80.dp, 120.dp)

@Composable
private fun DetailsTitle(title: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(TroveSpace.compact)) {
        Text(title, style = MaterialTheme.typography.headlineSmall)
        Text(subtitle, style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun EnrichmentProvenance.label(): Int = when (this) {
    EnrichmentProvenance.CINEMETA -> R.string.enrichment_cinemeta
    EnrichmentProvenance.TMDB -> R.string.enrichment_tmdb
    EnrichmentProvenance.CINEMETA_TMDB_POSTER -> R.string.enrichment_tmdb_poster
    EnrichmentProvenance.TMDB_CINEMETA_BACKFILL -> R.string.enrichment_cinemeta_backfill
    EnrichmentProvenance.TMDB_CINEMETA_POSTER -> R.string.enrichment_tmdb_cinemeta_poster
    EnrichmentProvenance.LEGACY_TMDB -> R.string.enrichment_tmdb_legacy
    EnrichmentProvenance.NO_MATCH -> R.string.enrichment_no_match
    EnrichmentProvenance.UNAVAILABLE -> R.string.enrichment_unavailable
}
