package app.trove.debrid.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.ui.res.painterResource
import app.trove.debrid.R
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import app.trove.debrid.ui.theme.TroveSpace

/** Persistent feedback is readable by touch exploration and announced when it changes. */
@Composable
fun Feedback(
    message: String,
    error: Boolean = false,
    modifier: Modifier = Modifier,
    containerColor: Color = if (error) MaterialTheme.colorScheme.errorContainer
        else MaterialTheme.colorScheme.secondaryContainer,
    contentColor: Color = if (error) MaterialTheme.colorScheme.onErrorContainer
        else MaterialTheme.colorScheme.onSecondaryContainer,
) {
    Surface(
        modifier = modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
        shape = MaterialTheme.shapes.medium,
        color = containerColor,
        contentColor = contentColor,
    ) {
        Row(
            Modifier.padding(TroveSpace.medium),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(TroveSpace.small),
        ) {
            if (error) {
                Icon(painterResource(R.drawable.ic_error_outline), contentDescription = null,
                    tint = contentColor)
            } else {
                Icon(Icons.Filled.Check, contentDescription = null, tint = contentColor)
            }
            Text(message, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
