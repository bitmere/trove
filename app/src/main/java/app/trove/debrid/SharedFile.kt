package app.trove.debrid

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Preserve document bytes and its actual display name; never decode binary content as text. */
internal suspend fun readSharedFile(resolver: ContentResolver, uri: Uri): PendingShare = withContext(Dispatchers.IO) {
    val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            .takeIf { it >= 0 }?.let { cursor.getString(it) } else null
    } ?: uri.lastPathSegment?.substringAfterLast('/') ?: "file"
    val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: error("Unreadable file")
    PendingShare(bytes = bytes, fileName = name, mimeType = resolver.getType(uri))
}
