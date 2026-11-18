package app.trove.debrid

/** Filename metadata supplies a concrete type even when the signed URL has no extension.
 * Never rewrite a signed CDN path or expose a credential-bearing requestdl permalink. */
internal fun playbackMimeType(fileName: String?): String = when (
    fileName?.substringAfterLast('.', "")?.lowercase(java.util.Locale.ROOT)
) {
    "mp4", "m4v" -> "video/mp4"
    "mkv" -> "video/x-matroska"
    "webm" -> "video/webm"
    "avi" -> "video/x-msvideo"
    "mov" -> "video/quicktime"
    "ts", "m2ts", "mts" -> "video/mp2t"
    "mpg", "mpeg" -> "video/mpeg"
    else -> "video/*"
}
