package app.trove.debrid.core.update

import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.StringReader

enum class UpdateChannel { STABLE, BETA }

class InvalidReleaseAssets : IllegalArgumentException()

object ReleaseManifestParser {
    private val codeLine = Regex("Android-Version-Code: ([1-9][0-9]*)")

    private val hashLine = Regex("SHA-256: ([0-9a-f]{64})")

    fun parse(text: String, channel: UpdateChannel): List<LatestRelease> {
        require(text.toByteArray(Charsets.UTF_8).size <= UpdatePolicy.MAX_MANIFEST_BYTES)
        return JsonReader(StringReader(text)).use { reader ->
            reader.strictness = Strictness.STRICT
            val releases = buildList {
                if (channel == UpdateChannel.BETA) {
                    reader.beginArray()
                    while (reader.hasNext()) reader.release(channel)?.let { add(it) }
                    reader.endArray()
                } else {
                    add(requireNotNull(reader.release(channel)))
                }
            }
            require(reader.peek() == JsonToken.END_DOCUMENT)
            releases
        }
    }

    private fun JsonReader.release(channel: UpdateChannel): LatestRelease? {
        val fields = mutableMapOf<String, String>()
        val assets = mutableListOf<Map<String, String>>()
        readFields {
            when (it) {
                "tag_name", "html_url" -> fields[it] = string()
                "name", "body" -> if (peek() == JsonToken.NULL) nextNull() else fields[it] = string()
                "draft", "prerelease" -> fields[it] = nextBoolean().toString()
                "assets" -> {
                    beginArray()
                    while (hasNext()) {
                        val asset = mutableMapOf<String, String>()
                        readFields {
                            when (it) {
                                "name", "state", "browser_download_url" ->
                                    if (peek() == JsonToken.STRING) asset[it] = nextString() else skipValue()
                                "size" -> if (peek() == JsonToken.NUMBER) asset[it] = nextString() else skipValue()
                                else -> skipValue()
                            }
                        }
                        assets.add(asset)
                    }
                    endArray()
                }
                else -> skipValue()
            }
        }
        val tag = fields.getValue("tag_name")
        val draft = fields.getValue("draft").toBoolean()
        val prerelease = fields.getValue("prerelease").toBoolean()
        if (draft || (channel == UpdateChannel.STABLE && (prerelease || '-' in tag))) return null
        require(UpdatePolicy.tagPattern.matches(tag))
        require(fields.getValue("html_url") == "${UpdatePolicy.RELEASES}/tag/$tag")
        fields.getValue("name")
        val lines = fields.getValue("body").replace("\r\n", "\n").trimEnd('\n').split('\n')
        require(lines.count { it.trimStart().startsWith("Android-Version-Code") } == 1)
        val code = requireNotNull(codeLine.matchEntire(lines.last())).groupValues[1].toLong()
        val hashCount = lines.count { it.trimStart().startsWith("SHA-256:") }
        val hash = if (hashCount == 0) "" else {
            require(hashCount == 1 && lines.size >= 2)
            requireNotNull(hashLine.matchEntire(lines[lines.lastIndex - 1])).groupValues[1]
        }
        val name = "Trove-$tag.apk"
        fun asset(expected: String, sizeRange: LongRange): Pair<String, Long> {
            val match = assets.singleOrNull { it["name"] == expected } ?: throw InvalidReleaseAssets()
            val size = match["size"]?.takeIf { it.matches(Regex("[1-9][0-9]*")) }?.toLongOrNull()
                ?: throw InvalidReleaseAssets()
            val url = match["browser_download_url"] ?: throw InvalidReleaseAssets()
            if (match["state"] != "uploaded" || size !in sizeRange ||
                !UpdatePolicy.isAssetUrl(url, tag, expected)) throw InvalidReleaseAssets()
            return url to size
        }
        val (apk, size) = asset(name, 1..UpdatePolicy.MAX_APK_BYTES)
        val checksumSize = (67 + name.length).toLong()
        val checksumName = "$name.sha256"
        val checksumUrl = if (assets.any { it["name"] == checksumName }) {
            asset(checksumName, checksumSize..checksumSize).first
        } else ""
        val notes = lines.dropLast(1 + hashCount).joinToString("\n").trimEnd()
        return LatestRelease(tag.drop(1), code, apk, hash, notes, size, tag, checksumUrl)
    }

    private fun JsonReader.readFields(read: (String) -> Unit) {
        val seen = mutableSetOf<String>()
        beginObject()
        while (hasNext()) {
            val key = nextName()
            require(seen.add(key))
            read(key)
        }
        endObject()
    }

    private fun JsonReader.string(): String {
        require(peek() == JsonToken.STRING)
        return nextString()
    }
}
