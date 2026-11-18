package app.trove.debrid.core

private val imageExtensions = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp")
private val coverKeywords = listOf("cover", "folder", "poster", "backdrop", "thumb")
private val sampleToken = Regex("""\.sample\.|-sample-|_sample_""")
private val releaseSeparators = Regex("""[ ._-]+""")

/** Identifies release extras using only the final path component. */
fun isClutterFile(fileName: String): Boolean {
    val name = fileName.substringAfterLast('/').substringAfterLast('\\').lowercase()
    val extension = name.substringAfterLast('.', missingDelimiterValue = "")
    val stem = name.substringBeforeLast('.')

    // Plain spaces can be part of a title such as "The Sample 2024".
    return extension == "nfo" ||
        name.startsWith("sample") || sampleToken.containsMatchIn(name) ||
        (extension in imageExtensions && (stem == "fanart" || coverKeywords.any { it in stem })) ||
        (extension == "txt" && "downloaded from" in stem.replace(releaseSeparators, " "))
}
