package app.trove.debrid.ui.downloads

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.trove.debrid.ui.theme.TroveSpace

/** Content width is retained even when the tab's remaining space is narrow. */
@Composable
internal fun CountPill(count: Int, selected: Boolean, hasMore: Boolean = false) {
    Surface(
        // Keep every digit under narrow constraints without a nested intrinsic query.
        modifier = Modifier.wrapContentWidth(unbounded = true),
        shape = MaterialTheme.shapes.extraLarge,
        color = if (selected) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
            else MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Text(
            text = formatCount(count, hasMore),
            modifier = Modifier.padding(horizontal = TroveSpace.small, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            softWrap = false,
        )
    }
}
