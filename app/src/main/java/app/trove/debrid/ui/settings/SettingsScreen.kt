package app.trove.debrid.ui.settings

import app.trove.debrid.core.DebridService
import app.trove.debrid.core.PlayerComponent
import app.trove.debrid.R
import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import app.trove.debrid.ui.theme.TroveSpace
import app.trove.debrid.ui.Feedback

/** One group per sub-screen; the root list shows these in order. */
internal enum class SettingsDestination(val titleRes: Int, val summaryRes: Int) {
    PROVIDERS(R.string.providers_title, R.string.settings_group_providers_summary),
    LIBRARY(R.string.settings_group_library, R.string.settings_group_library_summary),
    PLAYBACK(R.string.settings_group_playback, R.string.settings_group_playback_summary),
    APPEARANCE(R.string.settings_group_appearance, R.string.settings_group_appearance_summary),
    UPDATES(R.string.update_title, R.string.settings_group_updates_summary),
    BACKUP(R.string.backup_and_restore, R.string.settings_group_backup_summary),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: SettingsViewModel, updateViewModel: UpdateViewModel, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsState()
    val updateChannel by updateViewModel.channel.collectAsState()
    val canEdit = !state.backupBusy && !state.restoreNeedsPassword
    var showPlayers by remember { mutableStateOf(false) }
    // Destination survives rotation as its entry name; system back returns to the root list.
    var destinationName by rememberSaveable { mutableStateOf<String?>(null) }
    val destination = destinationName?.let { name -> SettingsDestination.entries.firstOrNull { it.name == name } }
    BackHandler(enabled = destination != null) { destinationName = null }
    if (showPlayers) {
        DefaultPlayerDialog(state, onSelect = {
            viewModel.setDefaultPlayer(it)
            showPlayers = false
        }, onRetry = viewModel::refreshPlayers, onDismiss = { showPlayers = false })
    }
    if (destination == null) {
        Scaffold(
            modifier = modifier.safeDrawingPadding(),
            topBar = {
                LargeTopAppBar(title = { Text(stringResource(R.string.settings)) }, navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back_downloads))
                    }
                }, windowInsets = WindowInsets(0, 0, 0, 0))
            },
        ) { insets ->
            Column(
                Modifier.fillMaxSize().padding(insets).verticalScroll(rememberScrollState())
                    .padding(horizontal = TroveSpace.page, vertical = TroveSpace.medium),
                verticalArrangement = Arrangement.spacedBy(TroveSpace.small),
            ) {
                SettingsDestination.entries.forEach { group ->
                    GroupRow(group) { destinationName = group.name }
                }
            }
        }
    } else {
        Scaffold(
            modifier = modifier.safeDrawingPadding(),
            topBar = {
                TopAppBar(title = { Text(stringResource(destination.titleRes)) }, navigationIcon = {
                    IconButton(onClick = { destinationName = null }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.settings_back))
                    }
                }, windowInsets = WindowInsets(0, 0, 0, 0))
            },
        ) { insets ->
            Column(
                Modifier.fillMaxSize().padding(insets).verticalScroll(rememberScrollState())
                    .imePadding().padding(horizontal = TroveSpace.page, vertical = TroveSpace.medium),
                verticalArrangement = Arrangement.spacedBy(TroveSpace.medium),
            ) {
                when (destination) {
                    SettingsDestination.PROVIDERS -> ProvidersContent(state, canEdit, viewModel)
                    SettingsDestination.LIBRARY -> LibraryContent(state, canEdit, viewModel)
                    SettingsDestination.PLAYBACK -> PlaybackContent(state, canEdit) {
                        viewModel.refreshPlayers()
                        showPlayers = true
                    }
                    SettingsDestination.APPEARANCE -> ThemeSection(state, canEdit, viewModel::setTheme)
                    SettingsDestination.UPDATES -> UpdateSection(updateViewModel, updateChannel)
                    SettingsDestination.BACKUP -> BackupSection(state, viewModel, updateViewModel)
                }
            }
        }
    }
}

@Composable
private fun GroupRow(group: SettingsDestination, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(stringResource(group.titleRes)) },
        supportingContent = { Text(stringResource(group.summaryRes)) },
        trailingContent = {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
        },
        modifier = Modifier.testTag("settings_group_${group.name.lowercase()}").clickable(onClick = onClick),
    )
}

@Composable
private fun ProvidersContent(state: SettingsUiState, canEdit: Boolean, viewModel: SettingsViewModel) {
    ProviderList(
        state,
        canEdit && !state.anyProviderSaving,
        viewModel::setActiveProvider,
    )
    HorizontalDivider()
    ProviderSection(state, canEdit, viewModel, providerSectionDescriptors.getValue(DebridService.TORBOX))
    HorizontalDivider()
    ProviderSection(state, canEdit, viewModel, providerSectionDescriptors.getValue(DebridService.REAL_DEBRID))
    ProviderSection(state, canEdit, viewModel, providerSectionDescriptors.getValue(DebridService.ALLDEBRID))
    ProviderSection(state, canEdit, viewModel, providerSectionDescriptors.getValue(DebridService.PREMIUMIZE))
    ProviderSection(state, canEdit, viewModel, providerSectionDescriptors.getValue(DebridService.OFFCLOUD))
    ProviderSection(state, canEdit, viewModel, providerSectionDescriptors.getValue(DebridService.DEBRID_LINK))
    if (COMING_SOON_SERVICES.isNotEmpty()) {
        HorizontalDivider()
        Text(stringResource(R.string.more_providers), style = MaterialTheme.typography.titleMedium)
        Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
            Column {
                COMING_SOON_SERVICES.forEach { (_, label) ->
                    ListItem(
                        headlineContent = { Text(label) },
                        supportingContent = { Text(stringResource(R.string.coming_soon)) },
                        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                    )
                }
            }
        }
    }
}

@Composable
private fun ProviderSection(
    state: SettingsUiState,
    canEdit: Boolean,
    viewModel: SettingsViewModel,
    descriptor: ProviderSectionDescriptor,
) {
    val hasKey = descriptor.hasKey(state)
    val saving = descriptor.saving(state)
    val enabled = canEdit && !saving
    val credential = descriptor.field(state)
    val showCredential = descriptor.showCredential(state)
    Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.primaryContainer) {
        Column(
            Modifier.fillMaxWidth().padding(TroveSpace.large),
            verticalArrangement = Arrangement.spacedBy(TroveSpace.small),
        ) {
            Text(stringResource(descriptor.title), style = MaterialTheme.typography.headlineMedium)
            Text(
                stringResource(if (hasKey) R.string.connected_account else R.string.connect_library),
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
    descriptor.connectionHeading?.let {
        Text(stringResource(it), style = MaterialTheme.typography.titleLarge)
    }
    Text(
        if (hasKey) descriptor.savedLabel(state) ?: stringResource(descriptor.credentialSaved)
        else stringResource(R.string.connect_body),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (hasKey) {
        Text(
            stringResource(descriptor.savedCredential, descriptor.maskedCredential(state)),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
    OutlinedTextField(
        value = credential,
        onValueChange = { descriptor.setField(viewModel, it) },
        label = { Text(stringResource(if (hasKey) descriptor.replaceCredential else descriptor.credentialLabel)) },
        singleLine = true,
        shape = MaterialTheme.shapes.medium,
        enabled = enabled,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
        visualTransformation = if (showCredential) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            TextButton(onClick = { descriptor.toggleShow(viewModel) }, enabled = enabled) {
                Text(stringResource(if (showCredential) R.string.hide else R.string.show))
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
    Button(
        onClick = { viewModel.testAndSave(descriptor.service) },
        enabled = enabled && credential.isNotBlank(),
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(descriptor.buttonPadding),
    ) {
        if (saving) {
            val indicator = Modifier.padding(end = TroveSpace.small).size(TroveSpace.page)
            val stroke = descriptor.progressStroke
            if (stroke == null) CircularProgressIndicator(indicator, color = LocalContentColor.current)
            else CircularProgressIndicator(indicator, color = LocalContentColor.current, strokeWidth = stroke)
        }
        Text(stringResource(if (saving) descriptor.checkingCredential else R.string.test_save))
    }
    if (descriptor.showFeedback(state)) {
        val resource = state.messageResource
        val argument = state.messageArgument
        val message = when {
            descriptor.plainFeedback || resource == null -> state.message
            argument != null -> stringResource(resource, argument)
            else -> stringResource(resource)
        }
        message?.let { Feedback(it, error = state.messageIsError) }
    }
    descriptor.helpLines.forEach { resource ->
        Text(
            stringResource(resource),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (hasKey) {
        OutlinedButton(
            onClick = { viewModel.signOut(descriptor.service) },
            enabled = enabled,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
        ) {
            Text(stringResource(R.string.sign_out))
        }
    }
}

@Composable
private fun LibraryContent(state: SettingsUiState, canEdit: Boolean, viewModel: SettingsViewModel) {
    val clutterSwitchEnabled = canEdit && !state.loadingClutterFiles && !state.savingClutterFiles
    ListItem(
        headlineContent = { Text(stringResource(R.string.show_clutter_files)) },
        trailingContent = {
            Switch(
                checked = state.showClutterFiles,
                onCheckedChange = null,
                enabled = clutterSwitchEnabled,
            )
        },
        modifier = Modifier.toggleable(
            value = state.showClutterFiles,
            enabled = clutterSwitchEnabled,
            role = Role.Switch,
            onValueChange = viewModel::setShowClutterFiles,
        ),
    )
    if (state.clutterFilesError) {
        Feedback(stringResource(R.string.clutter_files_setting_failed), error = true)
    }
    val clipboardSwitchEnabled = canEdit && !state.loadingClipboardOffers && !state.savingClipboardOffers
    ListItem(
        headlineContent = { Text(stringResource(R.string.clipboard_offers_setting)) },
        trailingContent = {
            Switch(
                checked = state.clipboardOffersEnabled,
                onCheckedChange = null,
                enabled = clipboardSwitchEnabled,
            )
        },
        modifier = Modifier.toggleable(
            value = state.clipboardOffersEnabled,
            enabled = clipboardSwitchEnabled,
            role = Role.Switch,
            onValueChange = viewModel::setClipboardOffersEnabled,
        ),
    )
    if (state.clipboardOffersError) {
        Feedback(stringResource(R.string.clipboard_offers_setting_failed), error = true)
    }
    HorizontalDivider()
    Text(stringResource(R.string.release_preferences), style = MaterialTheme.typography.titleMedium)
    val releaseWordsEnabled = canEdit && !state.loadingReleaseWords && !state.savingReleaseWords
    ReleaseWordsField(
        label = stringResource(R.string.exclude_release_words),
        words = state.excludeWords,
        enabled = releaseWordsEnabled,
        onApply = viewModel::setExcludeWords,
    )
    ReleaseWordsField(
        label = stringResource(R.string.prefer_release_words),
        words = state.preferWords,
        enabled = releaseWordsEnabled,
        onApply = viewModel::setPreferWords,
    )
    if (state.releaseWordsError) {
        Feedback(stringResource(R.string.release_preferences_failed), error = true)
    }
    HorizontalDivider()
    ListItem(
        headlineContent = { Text(stringResource(R.string.clear_cached_metadata)) },
        supportingContent = { Text(stringResource(R.string.clear_cached_metadata_description)) },
        trailingContent = {
            TextButton(onClick = viewModel::clearCachedMetadata) {
                Text(stringResource(R.string.clear))
            }
        },
    )
    if (state.metadataCacheCleared) {
        Feedback(stringResource(R.string.cached_metadata_cleared))
    }
    HorizontalDivider()
    Text(stringResource(R.string.tmdb_title), style = MaterialTheme.typography.titleMedium)
    if (state.hasTmdbKey) {
        Text(
            stringResource(R.string.tmdb_saved_key, state.maskedTmdbKey),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
    OutlinedTextField(
        value = state.tmdbKeyField,
        onValueChange = viewModel::setTmdbKeyField,
        label = { Text(stringResource(if (state.hasTmdbKey) R.string.tmdb_replace_key else R.string.tmdb_api_key)) },
        singleLine = true,
        shape = MaterialTheme.shapes.medium,
        enabled = canEdit && !state.savingTmdbKey,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
        visualTransformation = if (state.showTmdbKey) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            TextButton(onClick = viewModel::toggleShowTmdbKey, enabled = canEdit && !state.savingTmdbKey) {
                Text(stringResource(if (state.showTmdbKey) R.string.hide else R.string.show))
            }
        },
        modifier = Modifier.fillMaxWidth().testTag("tmdb_key_field"),
    )
    Text(
        stringResource(R.string.tmdb_key_help),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(TroveSpace.small)) {
        Button(
            onClick = viewModel::saveTmdbKey,
            enabled = canEdit && !state.savingTmdbKey && state.tmdbKeyField.isNotBlank(),
            modifier = Modifier.weight(1f),
        ) {
            if (state.savingTmdbKey) {
                CircularProgressIndicator(
                    Modifier.padding(end = TroveSpace.small).size(TroveSpace.page),
                    color = LocalContentColor.current,
                )
            }
            Text(stringResource(R.string.tmdb_save_key))
        }
        if (state.hasTmdbKey) {
            OutlinedButton(
                onClick = viewModel::clearTmdbKey,
                enabled = canEdit && !state.savingTmdbKey,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) {
                Text(stringResource(R.string.clear))
            }
        }
    }
    if (state.tmdbKeySaved) {
        Feedback(stringResource(R.string.tmdb_key_saved))
    }
    if (state.tmdbKeyError) {
        Feedback(stringResource(R.string.tmdb_key_failed), error = true)
    }
    Text(
        stringResource(R.string.tmdb_attribution),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun PlaybackContent(state: SettingsUiState, canEdit: Boolean, onShowPlayers: () -> Unit) {
    ListItem(
        headlineContent = { Text(stringResource(R.string.default_player)) },
        supportingContent = {
            Text(when {
                state.loadingPlayers -> stringResource(R.string.loading_players)
                state.defaultPlayer == null -> stringResource(R.string.ask_every_time)
                else -> state.players.firstOrNull { it.component == state.defaultPlayer }?.label
                    ?: stringResource(R.string.player_unavailable, state.defaultPlayer!!.packageName)
            })
        },
        modifier = Modifier.clickable(enabled = canEdit && !state.loadingPlayers && !state.savingPlayer) {
            onShowPlayers()
        },
    )
    if (state.playerError) Feedback(stringResource(R.string.player_setting_failed), error = true)
}

@Composable
private fun ProviderList(state: SettingsUiState, canEdit: Boolean, onSelect: (DebridService) -> Unit) {
    Text(stringResource(R.string.providers_title), style = MaterialTheme.typography.titleLarge)
    Column(Modifier.selectableGroup()) {
        val supportedServices = listOf(
            DebridService.TORBOX,
            DebridService.REAL_DEBRID,
            DebridService.ALLDEBRID,
            DebridService.PREMIUMIZE,
            DebridService.OFFCLOUD,
            DebridService.DEBRID_LINK,
        )
        val services = (supportedServices + state.accounts.map { it.service }).distinct()
        services.forEach { service ->
            val account = state.accounts.firstOrNull { it.service == service }
            val record = state.providerAccount(service)
            val signedIn = record.hasKey
            val label = record.accountLabel
            val selected = signedIn && state.activeService == service
            val enabled = signedIn && canEdit
            ListItem(
                headlineContent = {
                    Text(when (service) {
                        DebridService.TORBOX -> stringResource(R.string.torbox)
                        DebridService.REAL_DEBRID -> stringResource(R.string.real_debrid)
                        DebridService.ALLDEBRID -> stringResource(R.string.all_debrid)
                        DebridService.PREMIUMIZE -> stringResource(R.string.premiumize)
                        DebridService.OFFCLOUD -> stringResource(R.string.offcloud)
                        DebridService.DEBRID_LINK -> stringResource(R.string.debrid_link)
                        else -> account?.label.orEmpty()
                    })
                },
                supportingContent = {
                    Column {
                        Text(stringResource(if (signedIn) R.string.provider_signed_in else R.string.provider_not_signed_in))
                        if (signedIn && label != null) Text(label)
                    }
                },
                trailingContent = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (selected) {
                            Text(stringResource(R.string.provider_active), style = MaterialTheme.typography.labelMedium)
                            Spacer(Modifier.width(TroveSpace.small))
                        }
                        RadioButton(selected = selected, onClick = null, enabled = enabled)
                    }
                },
                modifier = Modifier.selectable(selected, enabled = enabled, role = Role.RadioButton) {
                    onSelect(service)
                },
            )
        }
    }
}

@Composable
private fun ReleaseWordsField(
    label: String,
    words: List<String>,
    enabled: Boolean,
    onApply: (List<String>) -> Unit,
) {
    val savedText = words.joinToString(", ")
    var draft by rememberSaveable(savedText) { mutableStateOf(savedText) }
    OutlinedTextField(
        value = draft,
        onValueChange = { draft = it },
        label = { Text(label) },
        supportingText = { Text(stringResource(R.string.release_words_hint)) },
        singleLine = true,
        shape = MaterialTheme.shapes.medium,
        enabled = enabled,
        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
        trailingIcon = {
            TextButton(enabled = enabled && draft != savedText, onClick = { onApply(draft.split(',')) }) {
                Text(stringResource(R.string.apply_release_words))
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

/** One bounded, scrolling list; no package-manager or preferences work in composition. */
@Composable
private fun DefaultPlayerDialog(
    state: SettingsUiState,
    onSelect: (PlayerComponent?) -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.default_player)) },
        text = {
            LazyColumn(Modifier.heightIn(max = 400.dp).selectableGroup()) {
                item {
                    PlayerOption(stringResource(R.string.ask_every_time), null,
                        state.defaultPlayer == null, !state.loadingPlayers && !state.savingPlayer) { onSelect(null) }
                }
                if (state.loadingPlayers) item { Text(stringResource(R.string.loading_players)) }
                if (state.playerError) item {
                    Feedback(stringResource(R.string.player_setting_failed), error = true)
                    TextButton(onClick = onRetry, enabled = !state.loadingPlayers && !state.savingPlayer) {
                        Text(stringResource(R.string.try_again))
                    }
                }
                val missing = state.defaultPlayer?.takeIf { saved -> state.players.none { it.component == saved } }
                if (missing != null && !state.loadingPlayers) item {
                    PlayerOption(stringResource(R.string.player_unavailable, missing.packageName),
                        missing.activityName, true, false) { }
                }
                items(state.players, key = { "${it.component.packageName}/${it.component.activityName}" }) { player ->
                    PlayerOption(player.label, "${player.component.packageName} / ${player.component.activityName}",
                        state.defaultPlayer == player.component, !state.loadingPlayers && !state.savingPlayer) {
                        onSelect(player.component)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
    )
}

@Composable
private fun PlayerOption(label: String, detail: String?, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
        .heightIn(min = 48.dp).padding(vertical = TroveSpace.small), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Spacer(Modifier.width(TroveSpace.small))
        Column {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            detail?.let { Text(it, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

