package app.trove.debrid.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CredentialStoreTest {

    @Test
    fun `masked display never contains the key`() {
        val store = InMemoryCredentialStore()
        store.save("tb-1", "abcdefghijklmnop1234")
        val masked = store.masked("tb-1")
        assertFalse(masked.contains("abcdefghijklmnop1234"))
        assertTrue(masked.endsWith("1234"))
        assertEquals("••••••••1234", masked)
        assertTrue(store.has("tb-1"))
        store.delete("tb-1")
        assertFalse(store.has("tb-1"))
    }

    @Test
    fun `short key masks fully`() {
        val store = InMemoryCredentialStore()
        store.save("x", "ab")
        assertEquals("••••", store.masked("x"))
    }
}
