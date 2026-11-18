package app.trove.debrid.data

import app.trove.debrid.core.DebridAccount
import app.trove.debrid.core.DebridService
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/** Explicit disk schema: Kotlin field names and R8 mappings must never define JSON keys. */
internal object StoredAccounts {
    fun encode(accounts: List<DebridAccount>): String = JsonArray().apply {
        accounts.forEach { account ->
            add(JsonObject().apply {
                addProperty("id", account.id)
                addProperty("service", account.service.name)
                addProperty("label", account.label)
                addProperty("addedAtEpochMs", account.addedAtEpochMs)
                addProperty("lastValidatedEpochMs", account.lastValidatedEpochMs)
            })
        }
    }.toString()

    fun decode(json: String?): List<DebridAccount> {
        if (json == null) return emptyList()
        val array = runCatching { JsonParser.parseString(json).asJsonArray }.getOrNull()
            ?: return emptyList()
        return array.mapNotNull { element ->
            // One damaged/unknown entry must not prevent the library from opening.
            runCatching {
                val obj = element.asJsonObject
                val strings = obj.entrySet().mapNotNull { (_, value) ->
                    value.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
                }
                // Earlier minified releases wrote a/b/c/d and sometimes stripped id.
                // Recognize their TorBox metadata by value, not by unstable field names.
                val legacyTorbox = obj.get("service") == null && "TORBOX" in strings
                val knownLegacyLayout = obj.get("a")?.let {
                    it.isJsonPrimitive && it.asJsonPrimitive.isString && it.asString == "TORBOX"
                } == true
                val service = if (legacyTorbox) DebridService.TORBOX
                    else DebridService.valueOf(obj.get("service").asString)
                val id = obj.get("id")?.asString
                    ?: if (legacyTorbox) DebridRepository.TORBOX_ACCOUNT_ID else return@runCatching null
                val label = obj.get("label")?.asString
                    ?: if (legacyTorbox) strings.firstOrNull { it.startsWith("TorBox (") } ?: "TorBox"
                    else return@runCatching null
                if (id.isBlank() || label.isBlank()) return@runCatching null
                DebridAccount(
                    id = id,
                    service = service,
                    label = label,
                    addedAtEpochMs = obj.get("addedAtEpochMs")?.asLong
                        ?: if (knownLegacyLayout) obj.get("c")?.asLong ?: 0L else 0L,
                    lastValidatedEpochMs = (obj.get("lastValidatedEpochMs")
                        ?: if (knownLegacyLayout) obj.get("d") else null)
                        ?.takeUnless { it.isJsonNull }?.asLong,
                )
            }.getOrNull()
        }
    }
}
