package app.trove.debrid.core

import com.google.gson.JsonObject
import kotlinx.coroutines.CancellationException

/**
 * Shared provider helpers: one error mapping and one lenient JSON
 * accessor set for all six providers. The six `call()` bodies stay
 * separate — only these two pieces are shared, so a provider keeps
 * its own envelope, auth, and rate-limit behaviour.
 */

/** Maps a failure to a typed [DebridError].
 *
 * Cancellation is never a failure: it is rethrown so the caller's
 * coroutine still cancels instead of reporting a bogus network error.
 *
 * Non-`Exception` throwables are JVM Errors (`OutOfMemoryError`,
 * `StackOverflowError`, ...). Five providers already rethrew them;
 * TorBox used to wrap them in [DebridError.Network], which hid a
 * fatal condition behind a retryable-looking network error. The
 * shared mapping adopts the rethrow: an Error must propagate, never
 * masquerade as a failed request. This is the one deliberate
 * behaviour change for TorBox in this unification; every other
 * provider keeps exactly the mapping it already had.
 */
internal fun <T> Result<T>.mapError(): Result<T> {
    val failure = exceptionOrNull() ?: return this
    if (failure is CancellationException) throw failure
    if (failure !is Exception) throw failure
    return Result.failure(if (failure is DebridError) failure else DebridError.Network(failure))
}

// ------------------------------------------------------------------
// Gson lenient accessors — the canonical set. Providers read JSON
// fields through these only; do not add a per-provider variant.
// ------------------------------------------------------------------

internal fun JsonObject.optString(name: String): String? {
    val el = this[name] ?: return null
    return if (el.isJsonNull) null else runCatching { el.asString }.getOrNull()
}

/** [optString] that also drops blank strings, for fields where a
 *  blank value means "absent" (the old per-provider `value()`). */
internal fun JsonObject.optNonBlankString(name: String): String? =
    optString(name)?.takeIf { it.isNotBlank() }

internal fun JsonObject.optLong(name: String): Long? {
    val el = this[name] ?: return null
    return if (el.isJsonNull) null else runCatching { el.asLong }.getOrNull()
        ?: el.asString.toLongOrNull()
}

internal fun JsonObject.optInt(name: String): Int? = optLong(name)?.toInt()

internal fun JsonObject.optDouble(name: String): Double? {
    val el = this[name] ?: return null
    return if (el.isJsonNull) null else runCatching { el.asDouble }.getOrNull()
        ?: el.asString.toDoubleOrNull()
}

internal fun JsonObject.optBoolean(name: String, default: Boolean): Boolean {
    val el = this[name] ?: return default
    return if (el.isJsonNull) default else runCatching { el.asBoolean }.getOrNull() ?: default
}

internal fun JsonObject.optBooleanOrNull(name: String): Boolean? =
    this[name]?.takeUnless { it.isJsonNull }?.let { runCatching { it.asBoolean }.getOrNull() }
