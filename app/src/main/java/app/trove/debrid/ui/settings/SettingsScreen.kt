package app.trove.debrid.ui.settings

import app.trove.debrid.core.PlayerComponent
import app.trove.debrid.R
import androidx.compose.ui.res.stringResource

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
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
    var showPlayers by remember { mutableStateOf(false) }
    if (showPlayers) {
        DefaultPlayerDialog(state, onSelect = {
            viewModel.setDefaultPlayer(it)
            showPlayers = false
        }, onDismiss = { showPlayers = false })
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
                enabled = !state.saving,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                visualTransformation = if (state.showKey) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    TextButton(onClick = viewModel::toggleShowKey) {
                        Text(if (state.showKey) stringResource(R.string.hide) else stringResource(R.string.show))
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Button(onClick = viewModel::testAndSave,
                enabled = !state.saving && state.keyField.isNotBlank(), modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(18.dp)) {
                if (state.saving) {
                    CircularProgressIndicator(Modifier.padding(end = TroveSpace.small).size(TroveSpace.page),
                        color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp)
                }
                Text(if (state.saving) stringResource(R.string.checking_key) else stringResource(R.string.test_save))
            }
            state.message?.let {
                Feedback(it, error = state.messageIsError)
            }
            Text(stringResource(R.string.key_help),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (state.hasKey) {
                OutlinedButton(onClick = viewModel::signOut, enabled = !state.saving,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                    Text(stringResource(R.string.sign_out))
                }
            }
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
                modifier = Modifier.clickable(enabled = !state.loadingPlayers && !state.savingPlayer) {
                    viewModel.refreshPlayers()
                    showPlayers = true
                },
            )
            if (state.playerError) Feedback(stringResource(R.string.player_setting_failed), error = true)
            HorizontalDivider()
            Text(stringResource(R.string.more_providers), style = MaterialTheme.typography.titleMedium)
            Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
            Column {
            COMING_SOON_SERVICES.forEach { (_, label) ->
                ListItem(headlineContent = { Text(label) }, supportingContent = { Text(stringResource(R.string.coming_soon)) },
                    colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow))
            }
            }
            }
            UpdateSection(updateViewModel, updateChannel)
        }
    }
}

/** One bounded, scrolling list; no package-manager or preferences work in composition. */
@Composable
private fun DefaultPlayerDialog(
    state: SettingsUiState,
    onSelect: (PlayerComponent?) -> Unit,
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
                if (state.playerError) item { Text(stringResource(R.string.player_setting_failed)) }
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
        .padding(vertical = TroveSpace.small), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Spacer(Modifier.width(TroveSpace.small))
        Column {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            detail?.let { Text(it, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}
