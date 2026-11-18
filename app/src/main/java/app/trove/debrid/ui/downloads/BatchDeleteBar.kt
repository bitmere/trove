package app.trove.debrid.ui.downloads

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import app.trove.debrid.R
import app.trove.debrid.ui.theme.TroveSpace

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun BatchDeleteBar(selectedCount: Int, onDelete: () -> Unit, onCancel: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
        FlowRow(
            modifier = Modifier.fillMaxWidth().padding(
                horizontal = TroveSpace.medium,
                vertical = TroveSpace.small,
            ),
            horizontalArrangement = Arrangement.spacedBy(TroveSpace.compact),
        ) {
            TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
            Button(
                onClick = onDelete,
                enabled = selectedCount > 0,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
            ) {
                Text(pluralStringResource(R.plurals.delete_selected_count, selectedCount, selectedCount))
            }
        }
    }
}
