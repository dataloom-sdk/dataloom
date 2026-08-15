package io.dataloom.api.security

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LeastPrivilegeTest {

    private val storageRead = Capability("storage.read")
    private val storageWrite = Capability("storage.write")
    private val networkPush = Capability("network.push")

    @Test
    fun `blank capability label is rejected`() {
        assertFailsWith<IllegalArgumentException> { Capability("") }
        assertFailsWith<IllegalArgumentException> { Capability("   ") }
    }

    @Test
    fun `None grant holds nothing`() {
        assertTrue(GrantedCapabilities.None.isEmpty())
        assertEquals(0, GrantedCapabilities.None.size)
        assertFalse(GrantedCapabilities.None.holds(storageRead))
    }

    @Test
    fun `of with an empty set returns None`() {
        assertEquals(GrantedCapabilities.None, GrantedCapabilities.of(emptySet()))
    }

    @Test
    fun `of grants exactly the supplied capabilities with nothing implicit`() {
        val granted = GrantedCapabilities.of(setOf(storageRead, storageWrite))
        assertEquals(2, granted.size)
        assertTrue(granted.holds(storageRead))
        assertTrue(granted.holds(storageWrite))
        assertFalse(granted.holds(networkPush))
    }

    @Test
    fun `of rejects more than 64 capabilities`() {
        val tooMany = (1..65).map { Capability("capability.$it") }.toSet()
        assertFailsWith<IllegalArgumentException> { GrantedCapabilities.of(tooMany) }
    }

    @Test
    fun `of accepts exactly 64 capabilities`() {
        val exactlySixtyFour = (1..64).map { Capability("capability.$it") }.toSet()
        assertEquals(64, GrantedCapabilities.of(exactlySixtyFour).size)
    }

    @Test
    fun `holdsAll requires every requested capability to be present`() {
        val granted = GrantedCapabilities.of(setOf(storageRead, storageWrite))
        assertTrue(granted.holdsAll(setOf(storageRead)))
        assertTrue(granted.holdsAll(setOf(storageRead, storageWrite)))
        assertFalse(granted.holdsAll(setOf(storageRead, networkPush)))
    }

    @Test
    fun `isAuthorized denies by default when a requested capability is not granted`() {
        val granted = GrantedCapabilities.of(setOf(storageRead))
        assertFalse(isAuthorized(setOf(storageRead, networkPush), granted))
    }

    @Test
    fun `isAuthorized allows only when every requested capability is granted`() {
        val granted = GrantedCapabilities.of(setOf(storageRead, networkPush))
        assertTrue(isAuthorized(setOf(storageRead, networkPush), granted))
    }

    @Test
    fun `isAuthorized against the empty grant denies any non-empty request`() {
        assertFalse(isAuthorized(setOf(storageRead), GrantedCapabilities.None))
    }

    @Test
    fun `isAuthorized trivially allows an empty request`() {
        assertTrue(isAuthorized(emptySet(), GrantedCapabilities.None))
    }

    @Test
    fun `equal grants compare equal regardless of construction order`() {
        val first = GrantedCapabilities.of(setOf(storageRead, storageWrite))
        val second = GrantedCapabilities.of(setOf(storageWrite, storageRead))
        assertEquals(first, second)
        assertEquals(first.hashCode(), second.hashCode())
    }

    @Test
    fun `toString never renders individual capability labels`() {
        val granted = GrantedCapabilities.of(setOf(storageRead, storageWrite))
        val rendered = granted.toString()
        assertFalse(rendered.contains("storage.read"))
        assertFalse(rendered.contains("storage.write"))
        assertTrue(rendered.contains("size=2"))
    }
}
