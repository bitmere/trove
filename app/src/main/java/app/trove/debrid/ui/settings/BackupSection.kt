package app.trove.debrid.ui.settings

import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import app.trove.debrid.R
import app.trove.debrid.ui.Feedback
import app.trove.debrid.ui.theme.TroveSpace

private const val BACKUP_MIME = "application/json"

@Composable
internal fun BackupSection(state: SettingsUiState, viewModel: SettingsViewModel, updateViewModel: UpdateViewModel) {
    val resolver = LocalContext.current.contentResolver
    val create = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(BACKUP_MIME)) {
        viewModel.writeBackup(resolver, it)
    }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {
        viewModel.readBackup(resolver, it, updateViewModel)
    }
    val filename = stringResource(R.string.backup_filename)
    LaunchedEffect(state.chooseBackupDestination) {
        if (state.chooseBackupDestination) {
            viewModel.backupDestinationRequested()
            try {
                create.launch(filename)
            } catch (_: ActivityNotFoundException) {
                viewModel.backupPickerUnavailable()
            }
        }
    }

    var showBackup by remember { mutableStateOf(false) }
    Text(stringResource(R.string.backup_and_restore), style = MaterialTheme.typography.titleMedium)
    Row(horizontalArrangement = Arrangement.spacedBy(TroveSpace.small)) {
        OutlinedButton(onClick = { showBackup = true }, enabled = state.canStartBackup) {
            Text(stringResource(R.string.backup_action))
        }
        OutlinedButton(
            onClick = {
                if (viewModel.chooseRestoreSource()) {
                    try {
                        open.launch(arrayOf(BACKUP_MIME, "text/plain", "application/octet-stream"))
                    } catch (_: ActivityNotFoundException) {
                        viewModel.backupPickerUnavailable()
                    }
                }
            },
            enabled = state.canStartBackup,
        ) {
            Text(stringResource(R.string.restore_action))
        }
    }
    if (state.backupBusy) Text(stringResource(R.string.backup_working))
    if (!state.restoreNeedsPassword) state.backupFeedback?.let {
        Feedback(stringResource(it), error = state.backupError)
    }

    if (showBackup) {
        BackupDialog(
            hasKey = state.hasKey,
            onDismiss = { showBackup = false },
            onContinue = { includeSignIn, password ->
                showBackup = false
                viewModel.prepareBackup(includeSignIn, password, updateViewModel.channel.value)
            },
        )
    }
    if (state.restoreNeedsPassword) {
        var password by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = viewModel::dismissRestorePassword,
            title = { Text(stringResource(R.string.restore_sign_in)) },
            text = {
                Column(
                    Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(TroveSpace.small),
                ) {
                    BackupPasswordField(password, { password = it }, R.string.backup_password, !state.backupBusy)
                    state.backupFeedback?.let { Feedback(stringResource(it), error = state.backupError) }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = password.isNotEmpty() && !state.backupBusy,
                    onClick = {
                        viewModel.restoreWithPassword(password, updateViewModel)
                        password = ""
                    },
                ) { Text(stringResource(R.string.restore_action)) }
            },
            dismissButton = {
                TextButton(enabled = !state.backupBusy, onClick = viewModel::dismissRestorePassword) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun BackupDialog(
    hasKey: Boolean,
    onDismiss: () -> Unit,
    onContinue: (Boolean, String) -> Unit,
) {
    var includeSignIn by remember { mutableStateOf(false) }
    var password by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<Int?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.backup_action)) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(TroveSpace.small),
            ) {
                Row(
                    Modifier.fillMaxWidth().toggleable(
                        value = includeSignIn,
                        enabled = hasKey,
                        role = Role.Checkbox,
                        onValueChange = {
                            includeSignIn = it
                            password = ""
                            confirmation = ""
                            error = null
                        },
                    ),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(TroveSpace.small),
                ) {
                    Checkbox(checked = includeSignIn, onCheckedChange = null, enabled = hasKey)
                    Text(stringResource(R.string.backup_include_sign_ins))
                }
                if (includeSignIn) {
                    BackupPasswordField(password, {
                        password = it
                        error = null
                    }, R.string.backup_password)
                    BackupPasswordField(confirmation, {
                        confirmation = it
                        error = null
                    }, R.string.backup_confirm_password)
                }
                error?.let { Feedback(stringResource(it), error = true) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                when {
                    includeSignIn && password.isEmpty() -> error = R.string.backup_password_required
                    includeSignIn && password != confirmation -> error = R.string.backup_password_mismatch
                    else -> onContinue(includeSignIn, password)
                }
            }) { Text(stringResource(R.string.backup_action)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

@Composable
private fun BackupPasswordField(value: String, onChange: (String) -> Unit, label: Int, enabled: Boolean = true) {
    OutlinedTextField(
        modifier = Modifier.fillMaxWidth(),
        value = value,
        onValueChange = onChange,
        label = { Text(stringResource(label)) },
        enabled = enabled,
        singleLine = true,
        shape = MaterialTheme.shapes.medium,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
        visualTransformation = PasswordVisualTransformation(),
    )
}
