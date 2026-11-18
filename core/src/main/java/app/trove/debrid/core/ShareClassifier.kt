package app.trove.debrid.core

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** What the user shared, before any provider is involved. */
data class ShareInput(
    val text: String? = null,
    val fileBytes: ByteArray? = null,
    val fileName: String? = null,
    val mimeType: String? = null,
)

sealed interface ClassifiedShare {
    data class Magnet(val magnetUri: String, val infoHashHex: String?) : ClassifiedShare
    data class TorrentFile(val bytes: ByteArray, val fileName: String) : ClassifiedShare
    /** A URL pointing at a .torrent file — fetch the bytes, then add. */
    data class TorrentUrl(val url: String) : ClassifiedShare {
        val fileName: String get() = url.toHttpUrl().fileName()
    }
    data class Nzb(val bytes: ByteArray, val fileName: String) : ClassifiedShare
    /** A URL pointing at a .nzb file — fetch the bytes, then add. */
    data class NzbUrl(val url: String) : ClassifiedShare {
        val fileName: String get() = url.toHttpUrl().fileName()
    }
    data class HosterLink(val url: String) : ClassifiedShare
    data class UnsupportedHoster(val url: String) : ClassifiedShare
    data object NotShareable : ClassifiedShare
}

private fun HttpUrl.fileName(): String = pathSegments.last().substringAfterLast('/')

/** Pure classifier for the share/add flow. No I/O, fully unit tested.
 *  Rules (from the spec): magnet: → torrent; .torrent link/file →
 *  torrent; .nzb → Usenet; any other URL → web download, validated
 *  against the provider's hoster list first. */
object ShareClassifier {
    private val HEX40 = Regex("^[0-9a-fA-F]{40}$")
    private val BTIH = Regex("(?:^|&)xt=urn:btih:([0-9a-f]{40})(?=&|$)", RegexOption.IGNORE_CASE)

    fun classify(input: ShareInput, hosters: Set<String>): ClassifiedShare {
        // A file wins over text: the user picked a file.
        val fileName = input.fileName?.lowercase()
        val bytes = input.fileBytes
        if (bytes != null && fileName != null) {
            return when {
                fileName.endsWith(".torrent") || input.mimeType == "application/x-bittorrent" ->
                    ClassifiedShare.TorrentFile(bytes, input.fileName)
                fileName.endsWith(".nzb") || input.mimeType == "application/x-nzb" ->
                    ClassifiedShare.Nzb(bytes, input.fileName)
                else -> ClassifiedShare.NotShareable
            }
        }

        val text = input.text?.trim().orEmpty()
        if (text.isEmpty()) return ClassifiedShare.NotShareable

        // A bare v1 info-hash is a magnet.
        if (HEX40.matches(text)) {
            return ClassifiedShare.Magnet("magnet:?xt=urn:btih:${text.lowercase()}", text.lowercase())
        }
        cleanMagnet(text)?.let { magnet ->
            return ClassifiedShare.Magnet(magnet, extractInfoHash(magnet))
        }
        val url = text.takeWhile { !it.isWhitespace() }
        val parsed = url.toHttpUrlOrNull() ?: return ClassifiedShare.NotShareable
        val name = parsed.fileName().lowercase()
        return when {
            name.endsWith(".torrent") -> ClassifiedShare.TorrentUrl(url)
            name.endsWith(".nzb") -> ClassifiedShare.NzbUrl(url)
            hosters.isEmpty() -> ClassifiedShare.HosterLink(url)
            isSupportedHoster(parsed.host, hosters) -> ClassifiedShare.HosterLink(url)
            else -> ClassifiedShare.UnsupportedHoster(url)
        }
    }

    /** Extract one URI from browser share prose. Keep escaped query values intact:
     * decoding the whole URI would turn an encoded tracker '&' into a new field. */
    fun cleanMagnet(text: String): String? {
        val match = Regex("""magnet:\?[^\s<>"']+""", RegexOption.IGNORE_CASE).find(text)
        val token = if (match != null) {
            val closing = when (text.getOrNull(match.range.first - 1)) {
                '(' -> ')'
                '[' -> ']'
                '{' -> '}'
                else -> null
            }
            if (closing != null) match.value.removeSuffix(closing.toString()) else match.value
        } else {
            Regex("""^(?:\?)?xt=urn:btih:[^\s<>"']+""", RegexOption.IGNORE_CASE)
                .find(text.trim())?.value?.let { "magnet:?" + it.removePrefix("?") }
                ?: return null
        }
        return "magnet:" + token.substringAfter(':').replace("&amp;", "&")
    }

    /** The 40-hex info-hash inside a magnet URI, or null (e.g. base32). */
    fun extractInfoHash(magnetUri: String): String? =
        BTIH.find(magnetUri.substringAfter('?', "").substringBefore('#'))?.groupValues?.get(1)?.lowercase()

    private fun isSupportedHoster(host: String, hosters: Set<String>): Boolean {
        val normalizedHost = host.lowercase().removePrefix("www.")
        return hosters.any { h ->
            val clean = h.lowercase().removePrefix("www.")
            normalizedHost == clean || normalizedHost.endsWith(".$clean")
        }
    }
}
