package app.trove.debrid.core

import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Stage 1 of the provider-helper unification: the one shared
 *  [mapError] and the canonical `opt*` JSON accessors. */
class ProviderHelpersTest {

    // mapError -------------------------------------------------------

    @Test
    fun `success passes through unchanged`() {
        assertEquals(Result.success("ok"), Result.success("ok").mapError())
    }

    @Test
    fun `a DebridError passes through unchanged`() {
        val error = DebridError.AuthInvalid()
        assertSame(error, Result.failure<String>(error).mapError().exceptionOrNull())
    }

    @Test
    fun `a plain Exception becomes a Network failure`() {
        val cause = IllegalStateException("boom")
        val failure = Result.failure<String>(cause).mapError().exceptionOrNull()
        assertTrue(failure is DebridError.Network)
    }

    @Test
    fun `cancellation is rethrown, never mapped`() {
        val cancellation = CancellationException("cancelled")
        val thrown = runCatching { Result.failure<String>(cancellation).mapError() }.exceptionOrNull()
        assertSame(cancellation, thrown)
    }

    @Test
    fun `a non-Exception Throwable is rethrown, never mapped`() {
        // The explicit TorBox decision: five providers already rethrew
        // JVM Errors; TorBox used to wrap them in Network, hiding a
        // fatal condition behind a retryable-looking error. The shared
        // mapping adopts the rethrow.
        val error = OutOfMemoryError("test")
        val thrown = runCatching { Result.failure<String>(error).mapError() }.exceptionOrNull()
        assertSame(error, thrown)
    }

    // opt* accessors -------------------------------------------------

    private fun obj(json: String) = JsonParser.parseString(json).asJsonObject

    @Test
    fun `optString reads strings and numbers, skips missing null and blank-adjacent values`() {
        val json = obj("""{"s":"Dune","n":42,"b":true,"nil":null,"o":{"x":1},"a":[1],"a2":[1,2]}""")
        assertEquals("Dune", json.optString("s"))
        assertEquals("42", json.optString("n"))
        assertEquals("true", json.optString("b"))
        assertNull(json.optString("nil"))
        assertNull(json.optString("missing"))
        assertNull(json.optString("o"))
        // Gson unwraps a single-element array to the element's string;
        // a multi-element array is not a string. Verbatim old behaviour.
        assertEquals("1", json.optString("a"))
        assertNull(json.optString("a2"))
    }

    @Test
    fun `optNonBlankString drops blank strings but keeps the rest`() {
        val json = obj("""{"s":"Dune","blank":"  ","n":42}""")
        assertEquals("Dune", json.optNonBlankString("s"))
        assertNull(json.optNonBlankString("blank"))
        assertEquals("42", json.optNonBlankString("n"))
        assertNull(json.optNonBlankString("missing"))
    }

    @Test
    fun `optLong reads numbers and numeric strings`() {
        val json = obj("""{"n":18400000000,"s":"123","f":1.5,"bad":"abc","nil":null}""")
        assertEquals(18_400_000_000L, json.optLong("n"))
        assertEquals(123L, json.optLong("s"))
        // Gson's asLong truncates a fraction; verbatim old behaviour.
        assertEquals(1L, json.optLong("f"))
        assertNull(json.optLong("bad"))
        assertNull(json.optLong("nil"))
        assertNull(json.optLong("missing"))
    }

    @Test
    fun `optInt narrows optLong`() {
        val json = obj("""{"n":7,"s":"9","big":3000000000}""")
        assertEquals(7, json.optInt("n"))
        assertEquals(9, json.optInt("s"))
        // A value wider than Int truncates, exactly as the old call sites did.
        assertEquals(3000000000L.toInt(), json.optInt("big"))
        assertNull(json.optInt("missing"))
    }

    @Test
    fun `optDouble reads decimals and numeric strings`() {
        val json = obj("""{"d":0.62,"s":"1.5","n":2,"bad":"abc"}""")
        assertEquals(0.62, json.optDouble("d")!!, 0.0001)
        assertEquals(1.5, json.optDouble("s")!!, 0.0001)
        assertEquals(2.0, json.optDouble("n")!!, 0.0001)
        assertNull(json.optDouble("bad"))
        assertNull(json.optDouble("missing"))
    }

    @Test
    fun `optBoolean falls back to the default for missing null and non-boolean values`() {
        val json = obj("""{"t":true,"f":false,"s":"true","nil":null}""")
        assertTrue(json.optBoolean("t", false))
        assertFalse(json.optBoolean("f", true))
        assertTrue(json.optBoolean("s", false))
        assertTrue(json.optBoolean("nil", true))
        assertFalse(json.optBoolean("missing", false))
        assertTrue(json.optBoolean("missing", true))
    }

    @Test
    fun `optBooleanOrNull distinguishes false from absent`() {
        val json = obj("""{"t":true,"f":false,"nil":null}""")
        assertTrue(json.optBooleanOrNull("t")!!)
        assertFalse(json.optBooleanOrNull("f")!!)
        assertNull(json.optBooleanOrNull("nil"))
        assertNull(json.optBooleanOrNull("missing"))
    }
}
