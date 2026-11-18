package app.trove.debrid.core

private val releaseSeparators = Regex("""[._\-\s\p{Ps}\p{Pe}<>]+""")

private fun releaseTokens(name: String): Set<String> =
    releaseSeparators.splitToSequence(name.lowercase()).filter { it.isNotEmpty() }.toSet()

private fun preferenceTokens(words: List<String>): Set<String> =
    words.asSequence().map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()

fun isExcludedRelease(name: String, excludeWords: List<String>): Boolean {
    val excluded = preferenceTokens(excludeWords)
    return excluded.isNotEmpty() && releaseTokens(name).any { it in excluded }
}

fun preferScore(name: String, preferWords: List<String>): Int {
    val preferred = preferenceTokens(preferWords)
    return if (preferred.isEmpty()) 0 else releaseTokens(name).count { it in preferred }
}

fun filterExcluded(downloads: List<DebridDownload>, excludeWords: List<String>): List<DebridDownload> {
    val excluded = preferenceTokens(excludeWords)
    if (excluded.isEmpty()) return downloads
    return downloads.filterNot { download -> releaseTokens(download.name).any { it in excluded } }
}
