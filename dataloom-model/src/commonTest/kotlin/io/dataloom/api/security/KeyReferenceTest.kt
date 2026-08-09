package io.dataloom.api.security

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class KeyReferenceTest {

    @Test
    fun `non-blank value is accepted and preserved exactly`() {
        val reference = KeyReference("android-keystore:sync-hmac-key")
        assertEquals("android-keystore:sync-hmac-key", reference.value)
    }

    @Test
    fun `blank value is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            KeyReference("")
        }
    }

    @Test
    fun `whitespace-only value is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            KeyReference("   ")
        }
    }

    @Test
    fun `toString returns the underlying value`() {
        val reference = KeyReference("kms-key-42")
        assertEquals("kms-key-42", reference.toString())
    }

    @Test
    fun `equal values compare as equal`() {
        val a = KeyReference("same-key")
        val b = KeyReference("same-key")
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `different values compare as unequal`() {
        val a = KeyReference("key-a")
        val b = KeyReference("key-b")
        assertNotEquals(a, b)
    }
}
