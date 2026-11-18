package app.trove.debrid.core

import java.security.MessageDigest

object ClipboardOffer {
    /** Returns the fingerprint to record before presenting a new offer. */
    fun newLinkHash(text: String?, offeredHashes: Set<String>): String? {
        val link = when (val share = ShareClassifier.classify(ShareInput(text = text), emptySet())) {
            is ClassifiedShare.Magnet -> {
                // The Add flow also accepts bare hashes, but clipboard offers require a link.
                if (text?.contains("magnet:?", ignoreCase = true) != true) return null
                share.magnetUri
            }
            is ClassifiedShare.TorrentUrl -> share.url
            else -> return null
        }
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(link.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return hash.takeUnless { it in offeredHashes }
    }
}
