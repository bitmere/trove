package app.trove.debrid.ui.settings

import app.trove.debrid.core.DebridService
import app.trove.debrid.core.PlayerComponent
import app.trove.debrid.R
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: SettingsViewModel, updateViewModel: UpdateViewModel, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsState()
    val updateChannel by updateViewModel.channel.collectAsState()
    val canEdit = !state.backupBusy && !state.restoreNeedsPassword
    var showPlayers by remember { mutableStateOf(false) }
    if (showPlayers) {
        DefaultPlayerDialog(state, onSelect = {
            viewModel.setDefaultPlayer(it)
            showPlayers = false
        }, onRetry = viewModel::refreshPlayers, onDismiss = { showPlayers = false })
    }
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
                .imePadding().padding(horizontal = TroveSpace.page, vertical = TroveSpace.medium),
            verticalArrangement = Arrangement.spacedBy(TroveSpace.medium),
        ) {
            ProviderList(
                state,
                canEdit && !state.saving && !state.rdSaving && !state.adSaving && !state.pmSaving && !state.ocSaving && !state.dlSaving,
                viewModel::setActiveProvider,
            )
            HorizontalDivider()
            Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.primaryContainer) {
                Column(Modifier.fillMaxWidth().padding(TroveSpace.large), verticalArrangement = Arrangement.spacedBy(TroveSpace.small)) {
                    Text(stringResource(R.string.torbox), style = MaterialTheme.typography.headlineMedium)
                    Text(if (state.hasKey) stringResource(R.string.connected_account) else stringResource(R.string.connect_library), style = MaterialTheme.typography.titleMedium)
                }
            }
            Text(stringResource(R.string.connection), style = MaterialTheme.typography.titleLarge)
            Text(
                if (state.hasKey) state.accountLabel ?: stringResource(R.string.key_saved) else stringResource(R.string.connect_body),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (state.hasKey) {
                Text(stringResource(R.string.saved_key, state.maskedKey), style = MaterialTheme.typography.bodyMedium)
            }
            OutlinedTextField(
                value = state.keyField,
                onValueChange = viewModel::setKeyField,
                label = { Text(if (state.hasKey) stringResource(R.string.replace_key) else stringResource(R.string.api_key)) },
                singleLine = true,
                shape = MaterialTheme.shapes.medium,
                enabled = canEdit && !state.saving,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                visualTransformation = if (state.showKey) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    TextButton(onClick = viewModel::toggleShowKey, enabled = canEdit && !state.saving) {
                        Text(if (state.showKey) stringResource(R.string.hide) else stringResource(R.string.show))
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Button(onClick = viewModel::testAndSave,
                enabled = canEdit && !state.saving && state.keyField.isNotBlank(), modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(18.dp)) {
                if (state.saving) {
                    CircularProgressIndicator(Modifier.padding(end = TroveSpace.small).size(TroveSpace.page),
                        color = LocalContentColor.current, strokeWidth = 2.dp)
                }
                Text(if (state.saving) stringResource(R.string.checking_key) else stringResource(R.string.test_save))
            }
            state.message?.takeUnless { state.rdFeedback || state.adFeedback || state.pmFeedback || state.ocFeedback || state.dlFeedback }?.let {
                Feedback(it, error = state.messageIsError)
            }
            Text(stringResource(R.string.key_help),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (state.hasKey) {
                OutlinedButton(onClick = viewModel::signOut, enabled = canEdit && !state.saving,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                    Text(stringResource(R.string.sign_out))
                }
            }
            HorizontalDivider()
            RealDebridSection(state, canEdit, viewModel)
            AllDebridSection(state, canEdit, viewModel)
            PremiumizeSection(state, canEdit, viewModel)
            OffcloudSection(state, canEdit, viewModel)
            DebridLinkSection(state, canEdit, viewModel)
            HorizontalDivider()
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
                    viewModel.refreshPlayers()
                    showPlayers = true
                },
            )
            if (state.playerError) Feedback(stringResource(R.string.player_setting_failed), error = true)
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
            ThemeSection(state, canEdit, viewModel::setTheme)
            HorizontalDivider()
            BackupSection(state, viewModel, updateViewModel)
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
            UpdateSection(updateViewModel, updateChannel)
        }
    }
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
            val signedIn = when (service) {
                DebridService.TORBOX -> state.hasKey
                DebridService.REAL_DEBRID -> state.rdHasKey
                DebridService.ALLDEBRID -> state.adHasKey
                DebridService.PREMIUMIZE -> state.pmHasKey
                DebridService.OFFCLOUD -> state.ocHasKey
                DebridService.DEBRID_LINK -> state.dlHasKey
                else -> account != null
            }
            val label = when (service) {
                DebridService.TORBOX -> state.accountLabel
                DebridService.REAL_DEBRID -> state.rdAccountLabel
                DebridService.ALLDEBRID -> state.adAccountLabel
                DebridService.PREMIUMIZE -> state.pmAccountLabel
                DebridService.OFFCLOUD -> state.ocAccountLabel
                DebridService.DEBRID_LINK -> state.dlAccountLabel
                else -> account?.label
            }
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
private fun RealDebridSection(state: SettingsUiState, canEdit: Boolean, viewModel: SettingsViewModel) {
    val enabled = canEdit && !state.rdSaving
    Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.primaryContainer) {
        Column(
            Modifier.fillMaxWidth().padding(TroveSpace.large),
            verticalArrangement = Arrangement.spacedBy(TroveSpace.small),
        ) {
            Text(stringResource(R.string.real_debrid), style = MaterialTheme.typography.headlineMedium)
            Text(
                stringResource(if (state.rdHasKey) R.string.connected_account else R.string.connect_library),
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
    Text(
        if (state.rdHasKey) state.rdAccountLabel ?: stringResource(R.string.rd_token_saved)
        else stringResource(R.string.connect_body),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (state.rdHasKey) {
        Text(stringResource(R.string.rd_saved_token, SAVED_CREDENTIAL_MASK), style = MaterialTheme.typography.bodyMedium)
    }
    OutlinedTextField(
        value = state.rdTokenField,
        onValueChange = viewModel::setRdTokenField,
        label = {
            Text(stringResource(if (state.rdHasKey) R.string.rd_replace_token else R.string.real_debrid_api_token))
        },
        singleLine = true,
        shape = MaterialTheme.shapes.medium,
        enabled = enabled,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
        visualTransformation = if (state.rdShowToken) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            TextButton(onClick = viewModel::toggleShowRdToken, enabled = enabled) {
                Text(stringResource(if (state.rdShowToken) R.string.hide else R.string.show))
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
    Button(
        onClick = viewModel::testAndSaveRealDebrid,
        enabled = enabled && state.rdTokenField.isNotBlank(),
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(TroveSpace.medium),
    ) {
        if (state.rdSaving) {
            CircularProgressIndicator(
                Modifier.padding(end = TroveSpace.small).size(TroveSpace.page),
                color = LocalContentColor.current,
            )
        }
        Text(stringResource(if (state.rdSaving) R.string.rd_checking_token else R.string.test_save))
    }
    if (state.rdFeedback) {
        val message = state.messageResource?.let { resource ->
            state.messageArgument?.let { stringResource(resource, it) } ?: stringResource(resource)
        } ?: state.message
        message?.let { Feedback(it, error = state.messageIsError) }
    }
    Text(stringResource(R.string.rd_token_help),
        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text(stringResource(R.string.rd_token_note),
        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (state.rdHasKey) {
        OutlinedButton(
            onClick = viewModel::signOutRealDebrid,
            enabled = enabled,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
        ) {
            Text(stringResource(R.string.sign_out))
        }
    }
}

@Composable
private fun AllDebridSection(state: SettingsUiState, canEdit: Boolean, viewModel: SettingsViewModel) {
    val enabled = canEdit && !state.adSaving
    Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.primaryContainer) {
        Column(
            Modifier.fillMaxWidth().padding(TroveSpace.large),
            verticalArrangement = Arrangement.spacedBy(TroveSpace.small),
        ) {
            Text(stringResource(R.string.all_debrid), style = MaterialTheme.typography.headlineMedium)
            Text(
                stringResource(if (state.adHasKey) R.string.connected_account else R.string.connect_library),
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
    Text(
        if (state.adHasKey) state.adAccountLabel ?: stringResource(R.string.ad_key_saved)
        else stringResource(R.string.connect_body),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (state.adHasKey) {
        Text(stringResource(R.string.ad_saved_key, SAVED_CREDENTIAL_MASK), style = MaterialTheme.typography.bodyMedium)
    }
    OutlinedTextField(
        value = state.adKeyField,
        onValueChange = viewModel::setAdKeyField,
        label = {
            Text(stringResource(if (state.adHasKey) R.string.ad_replace_key else R.string.all_debrid_api_key))
        },
        singleLine = true,
        shape = MaterialTheme.shapes.medium,
        enabled = enabled,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
        visualTransformation = if (state.adShowKey) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            TextButton(onClick = viewModel::toggleShowAdKey, enabled = enabled) {
                Text(stringResource(if (state.adShowKey) R.string.hide else R.string.show))
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
    Button(
        onClick = viewModel::testAndSaveAllDebrid,
        enabled = enabled && state.adKeyField.isNotBlank(),
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(TroveSpace.medium),
    ) {
        if (state.adSaving) {
            CircularProgressIndicator(
                Modifier.padding(end = TroveSpace.small).size(TroveSpace.page),
                color = LocalContentColor.current,
            )
        }
        Text(stringResource(if (state.adSaving) R.string.ad_checking_key else R.string.test_save))
    }
    if (state.adFeedback) {
        val message = state.messageResource?.let { resource ->
            state.messageArgument?.let { stringResource(resource, it) } ?: stringResource(resource)
        } ?: state.message
        message?.let { Feedback(it, error = state.messageIsError) }
    }
    Text(stringResource(R.string.ad_key_help),
        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text(stringResource(R.string.ad_key_note),
        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (state.adHasKey) {
        OutlinedButton(
            onClick = viewModel::signOutAllDebrid,
            enabled = enabled,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
        ) {
            Text(stringResource(R.string.sign_out))
        }
    }
}

@Composable
private fun PremiumizeSection(state: SettingsUiState, canEdit: Boolean, viewModel: SettingsViewModel) {
    val enabled = canEdit && !state.pmSaving
    Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.primaryContainer) {
        Column(
            Modifier.fillMaxWidth().padding(TroveSpace.large),
            verticalArrangement = Arrangement.spacedBy(TroveSpace.small),
        ) {
            Text(stringResource(R.string.premiumize), style = MaterialTheme.typography.headlineMedium)
            Text(
                stringResource(if (state.pmHasKey) R.string.connected_account else R.string.connect_library),
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
    Text(
        if (state.pmHasKey) state.pmAccountLabel ?: stringResource(R.string.pm_key_saved)
        else stringResource(R.string.connect_body),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (state.pmHasKey) {
        Text(stringResource(R.string.pm_saved_key, SAVED_CREDENTIAL_MASK), style = MaterialTheme.typography.bodyMedium)
    }
    OutlinedTextField(
        value = state.pmKeyField,
        onValueChange = viewModel::setPmKeyField,
        label = {
            Text(stringResource(if (state.pmHasKey) R.string.pm_replace_key else R.string.premiumize_api_key))
        },
        singleLine = true,
        shape = MaterialTheme.shapes.medium,
        enabled = enabled,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
        visualTransformation = if (state.pmShowKey) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            TextButton(onClick = viewModel::toggleShowPmKey, enabled = enabled) {
                Text(stringResource(if (state.pmShowKey) R.string.hide else R.string.show))
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
    Button(
        onClick = viewModel::testAndSavePremiumize,
        enabled = enabled && state.pmKeyField.isNotBlank(),
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(TroveSpace.medium),
    ) {
        if (state.pmSaving) {
            CircularProgressIndicator(
                Modifier.padding(end = TroveSpace.small).size(TroveSpace.page),
                color = LocalContentColor.current,
            )
        }
        Text(stringResource(if (state.pmSaving) R.string.pm_checking_key else R.string.test_save))
    }
    if (state.pmFeedback) {
        val message = state.messageResource?.let { resource ->
            state.messageArgument?.let { stringResource(resource, it) } ?: stringResource(resource)
        } ?: state.message
        message?.let { Feedback(it, error = state.messageIsError) }
    }
    Text(stringResource(R.string.pm_key_help),
        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (state.pmHasKey) {
        OutlinedButton(
            onClick = viewModel::signOutPremiumize,
            enabled = enabled,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
        ) {
            Text(stringResource(R.string.sign_out))
        }
    }
}

@Composable
private fun OffcloudSection(state: SettingsUiState, canEdit: Boolean, viewModel: SettingsViewModel) {
    val enabled = canEdit && !state.ocSaving
    Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.primaryContainer) {
        Column(
            Modifier.fillMaxWidth().padding(TroveSpace.large),
            verticalArrangement = Arrangement.spacedBy(TroveSpace.small),
        ) {
            Text(stringResource(R.string.offcloud), style = MaterialTheme.typography.headlineMedium)
            Text(
                stringResource(if (state.ocHasKey) R.string.connected_account else R.string.connect_library),
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
    Text(
        if (state.ocHasKey) state.ocAccountLabel ?: stringResource(R.string.oc_key_saved)
        else stringResource(R.string.connect_body),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (state.ocHasKey) {
        Text(stringResource(R.string.oc_saved_key, SAVED_CREDENTIAL_MASK), style = MaterialTheme.typography.bodyMedium)
    }
    OutlinedTextField(
        value = state.ocKeyField,
        onValueChange = viewModel::setOcKeyField,
        label = {
            Text(stringResource(if (state.ocHasKey) R.string.oc_replace_key else R.string.offcloud_api_key))
        },
        singleLine = true,
        shape = MaterialTheme.shapes.medium,
        enabled = enabled,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
        visualTransformation = if (state.ocShowKey) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            TextButton(onClick = viewModel::toggleShowOcKey, enabled = enabled) {
                Text(stringResource(if (state.ocShowKey) R.string.hide else R.string.show))
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
    Button(
        onClick = viewModel::testAndSaveOffcloud,
        enabled = enabled && state.ocKeyField.isNotBlank(),
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(TroveSpace.medium),
    ) {
        if (state.ocSaving) {
            CircularProgressIndicator(
                Modifier.padding(end = TroveSpace.small).size(TroveSpace.page),
                color = LocalContentColor.current,
            )
        }
        Text(stringResource(if (state.ocSaving) R.string.oc_checking_key else R.string.test_save))
    }
    if (state.ocFeedback) {
        val message = state.messageResource?.let { resource ->
            state.messageArgument?.let { stringResource(resource, it) } ?: stringResource(resource)
        } ?: state.message
        message?.let { Feedback(it, error = state.messageIsError) }
    }
    Text(stringResource(R.string.oc_key_help),
        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (state.ocHasKey) {
        OutlinedButton(
            onClick = viewModel::signOutOffcloud,
            enabled = enabled,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
        ) {
            Text(stringResource(R.string.sign_out))
        }
    }
}

@Composable
private fun DebridLinkSection(state: SettingsUiState, canEdit: Boolean, viewModel: SettingsViewModel) {
    val enabled = canEdit && !state.dlSaving
    Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.primaryContainer) {
        Column(
            Modifier.fillMaxWidth().padding(TroveSpace.large),
            verticalArrangement = Arrangement.spacedBy(TroveSpace.small),
        ) {
            Text(stringResource(R.string.debrid_link), style = MaterialTheme.typography.headlineMedium)
            Text(
                stringResource(if (state.dlHasKey) R.string.connected_account else R.string.connect_library),
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
    Text(
        if (state.dlHasKey) state.dlAccountLabel ?: stringResource(R.string.dl_key_saved)
        else stringResource(R.string.connect_body),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (state.dlHasKey) {
        Text(stringResource(R.string.dl_saved_key, SAVED_CREDENTIAL_MASK), style = MaterialTheme.typography.bodyMedium)
    }
    OutlinedTextField(
        value = state.dlKeyField,
        onValueChange = viewModel::setDlKeyField,
        label = {
            Text(stringResource(if (state.dlHasKey) R.string.dl_replace_key else R.string.debridlink_api_key))
        },
        singleLine = true,
        shape = MaterialTheme.shapes.medium,
        enabled = enabled,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
        visualTransformation = if (state.dlShowKey) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            TextButton(onClick = viewModel::toggleShowDlKey, enabled = enabled) {
                Text(stringResource(if (state.dlShowKey) R.string.hide else R.string.show))
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
    Button(
        onClick = viewModel::testAndSaveDebridLink,
        enabled = enabled && state.dlKeyField.isNotBlank(),
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(TroveSpace.medium),
    ) {
        if (state.dlSaving) {
            CircularProgressIndicator(
                Modifier.padding(end = TroveSpace.small).size(TroveSpace.page),
                color = LocalContentColor.current,
            )
        }
        Text(stringResource(if (state.dlSaving) R.string.dl_checking_key else R.string.test_save))
    }
    if (state.dlFeedback) {
        val message = state.messageResource?.let { resource ->
            state.messageArgument?.let { stringResource(resource, it) } ?: stringResource(resource)
        } ?: state.message
        message?.let { Feedback(it, error = state.messageIsError) }
    }
    Text(stringResource(R.string.dl_key_help),
        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (state.dlHasKey) {
        OutlinedButton(
            onClick = viewModel::signOutDebridLink,
            enabled = enabled,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
        ) {
            Text(stringResource(R.string.sign_out))
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
