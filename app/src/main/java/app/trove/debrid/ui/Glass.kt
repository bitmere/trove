package app.trove.debrid.ui

import app.trove.debrid.R
import androidx.compose.ui.res.stringResource

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import app.trove.debrid.ui.theme.TroveElevation
import app.trove.debrid.ui.theme.TroveSize
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
    goToTopVisible: Boolean = false,
    onGoToTop: () -> Unit = {},
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        AnimatedVisibility(
            visible = goToTopVisible,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            Box(Modifier.padding(bottom = TroveSpace.medium)) {
                IconButton(
                    onClick = onGoToTop,
                    modifier = Modifier
                        .size(TroveSize.control)
                        .shadow(TroveElevation.floating, CircleShape, ambientColor = MaterialTheme.colorScheme.scrim.copy(alpha = 0.14f))
                        .clip(CircleShape)
                        .border(TroveSize.outline, MaterialTheme.colorScheme.outlineVariant, CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.92f)),
                ) {
                    Icon(
                        imageVector = Icons.Filled.KeyboardArrowUp,
                        contentDescription = stringResource(R.string.go_to_top),
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
        Box(
            modifier = Modifier
                .size(TroveSize.control)
                .shadow(TroveElevation.floating, CircleShape, ambientColor = MaterialTheme.colorScheme.scrim.copy(alpha = 0.14f))
                .clip(CircleShape)
                .border(TroveSize.outline, MaterialTheme.colorScheme.outlineVariant, CircleShape)
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
                            surfaceColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            isCurrent = searchBackdrop.isCurrent),
                )
            } else {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.92f), CircleShape),
                )
            }
            IconButton(
                onClick = onSearch,
                modifier = Modifier.size(TroveSize.control),
            ) {
                Icon(
                    imageVector = Icons.Filled.Search,
                    contentDescription = stringResource(R.string.search),
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        Box(modifier = Modifier.padding(top = TroveSpace.compact)) {
            androidx.compose.material3.FloatingActionButton(
                onClick = onAdd,
                modifier = Modifier.size(TroveSize.floatingAction),
                shape = CircleShape,
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = stringResource(R.string.add_download),
                )
            }
        }
    }
}
