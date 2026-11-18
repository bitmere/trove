package app.trove.debrid.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.trove.debrid.R
import app.trove.debrid.data.ThemeAccent
import app.trove.debrid.data.ThemeMode
import app.trove.debrid.data.ThemePreference
import app.trove.debrid.ui.theme.troveColorScheme

@Composable
internal fun ThemeSection(
    state: SettingsUiState,
    canEdit: Boolean,
    onTheme: (ThemePreference) -> Unit,
) {
    val enabled = canEdit && !state.loadingTheme && !state.savingTheme
    val theme = state.theme
    val systemDark = isSystemInDarkTheme()

    Text(stringResource(R.string.theme_title), style = MaterialTheme.typography.titleMedium)
    Column(Modifier.selectableGroup()) {
        Text(stringResource(R.string.theme_mode), style = MaterialTheme.typography.titleSmall)
        ThemeMode.entries.forEach { mode ->
            val label = when (mode) {
                ThemeMode.SYSTEM -> R.string.theme_system
                ThemeMode.LIGHT -> R.string.theme_light
                ThemeMode.DARK -> R.string.theme_dark
                ThemeMode.OLED -> R.string.theme_oled
            }
            ThemeOption(stringResource(label), theme.mode == mode, enabled, {
                onTheme(theme.copy(mode = mode))
            })
        }
    }
    Column(Modifier.selectableGroup()) {
        Text(stringResource(R.string.theme_accent), style = MaterialTheme.typography.titleSmall)
        ThemeAccent.entries.forEach { accent ->
            val label = when (accent) {
                ThemeAccent.JADE -> R.string.theme_jade
                ThemeAccent.OCEAN -> R.string.theme_ocean
                ThemeAccent.EMBER -> R.string.theme_ember
                ThemeAccent.ROSE -> R.string.theme_rose
            }
            ThemeOption(stringResource(label), theme.accent == accent, enabled, {
                onTheme(theme.copy(accent = accent))
            }) {
                val primary = troveColorScheme(theme.mode, accent, systemDark).primary
                Box(Modifier.size(24.dp).background(primary, CircleShape))
            }
        }
    }
    if (state.themeError) {
        Text(
            stringResource(R.string.theme_save_failed),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun ThemeOption(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    swatch: (@Composable () -> Unit)? = null,
) {
    ListItem(
        headlineContent = { Text(label) },
        leadingContent = { RadioButton(selected = selected, onClick = null, enabled = enabled) },
        trailingContent = swatch,
        modifier = Modifier.selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onClick),
    )
}
