package app.trove.debrid.ui

import app.trove.debrid.R
import androidx.compose.ui.res.stringResource

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import app.trove.debrid.ui.theme.TroveSpace

/** Search and Add in the thumb zone. Search retains the optional
 * device-supported backdrop blur, with a themed tonal fallback. */
@Composable
fun FloatingActions(
    onAdd: () -> Unit,
    onSearch: () -> Unit,
    modifier: Modifier = Modifier,
    /** Captured backdrop for the Search button; null = translucent fallback. */
    searchBackdrop: SearchBackdrop? = null,
    /** Reports the Search button's position in root coordinates, so
     *  the captured backdrop can be aligned behind it. */
    onSearchPositioned: ((Offset) -> Unit)? = null,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .shadow(app.trove.debrid.ui.theme.TroveElevation.floating, CircleShape, ambientColor = androidx.compose.material3.MaterialTheme.colorScheme.scrim.copy(alpha = 0.14f))
                .clip(CircleShape)
                .border(1.dp, androidx.compose.material3.MaterialTheme.colorScheme.outlineVariant, CircleShape)
                .then(
                    if (onSearchPositioned != null) {
                        Modifier.onGloballyPositioned { onSearchPositioned(it.localToRoot(Offset.Zero)) }
                    } else {
                        Modifier
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (searchBackdrop != null) {
                // True blur: the captured list, blurred, behind the icon.
                // The border and shadow live on the outer box, so they
                // stay sharp; the icon is a sibling above the blur.
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .graphicsLayer { renderEffect = searchBackdrop.blurEffect }
                        .backdropImage(searchBackdrop.layer, searchBackdrop.offset,
                            surfaceColor = androidx.compose.material3.MaterialTheme.colorScheme.surfaceContainerHigh,
                            isCurrent = searchBackdrop.isCurrent),
                )
            } else {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(androidx.compose.material3.MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.92f), CircleShape),
                )
            }
            IconButton(
                onClick = onSearch,
                modifier = Modifier.size(48.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.Search,
                    contentDescription = stringResource(R.string.search),
                    tint = androidx.compose.material3.MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        Box(modifier = Modifier.padding(top = TroveSpace.compact)) {
            androidx.compose.material3.FloatingActionButton(
                onClick = onAdd,
                modifier = Modifier.size(56.dp),
                shape = CircleShape,
                containerColor = androidx.compose.material3.MaterialTheme.colorScheme.primary,
                contentColor = androidx.compose.material3.MaterialTheme.colorScheme.onPrimary,
            ) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = stringResource(R.string.add_download),
                )
            }
        }
    }
}
