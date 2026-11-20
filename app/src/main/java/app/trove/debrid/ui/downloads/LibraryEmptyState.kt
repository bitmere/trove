package app.trove.debrid.ui.downloads

import app.trove.debrid.R
import androidx.compose.ui.res.stringResource

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import app.trove.debrid.core.DownloadKind
import app.trove.debrid.ui.theme.TroveSpace

@Composable
internal fun LibraryEmptyState(kind: DownloadKind, query: String, error: String?,
    onAdd: () -> Unit, onClear: () -> Unit, onRetry: () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .heightIn(min = maxHeight).padding(TroveSpace.section),
            verticalArrangement = Arrangement.spacedBy(TroveSpace.medium, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                when {
                    error != null -> stringResource(R.string.library_unavailable)
                    query.isNotBlank() -> stringResource(R.string.no_matches)
                    else -> stringResource(R.string.empty_library)
                },
                style = MaterialTheme.typography.headlineMedium,
                textAlign = TextAlign.Center,
            )
            Text(
                when {
                    error != null -> error
                    query.isNotBlank() -> stringResource(R.string.no_query_match, query)
                    else -> stringResource(R.string.empty_kind, kindWord(kind).lowercase())
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Button(onClick = when {
                error != null -> onRetry
                query.isNotBlank() -> onClear
                else -> onAdd
            }) {
                Text(stringResource(when {
                    error != null -> R.string.try_again
                    query.isNotBlank() -> R.string.clear_search
                    else -> R.string.add_download
                }))
            }
        }
    }
}
