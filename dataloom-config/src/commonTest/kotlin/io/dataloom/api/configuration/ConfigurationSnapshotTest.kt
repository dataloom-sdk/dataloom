package io.dataloom.api.configuration

import io.dataloom.api.security.DataLoomDigestCalculator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Verifies the [ConfigurationSnapshot] contract.
 *
 * Uses a deterministic, non-cryptographic [FakeDataLoomDigestCalculator]
 * test stub rather than a production digest implementation — this module
 * has no platform-specific source set to host
 * SystemDataLoomDigestCalculator/AppleDataLoomDigestCalculator (those live
 * in dataloom-model's jvmMain/iosMain), and only determinism is needed here,
 * matching the same posture RuntimeDependenciesTest already uses for
 * DataLoomClock.
 */
class ConfigurationSnapshotTest {

    private val digestCalculator: DataLoomDigestCalculator = FakeDataLoomDigestCalculator()

    @Test
    fun getReturnsTheValueForAKnownKey() {
        val snapshot = snapshotOf(ConfigurationKey("a") to ConfigurationValue.StringValue("1"))
        assertEquals(ConfigurationValue.StringValue("1"), snapshot[ConfigurationKey("a")])
    }

    @Test
    fun getReturnsNullForAnUnknownKey() {
        val snapshot = snapshotOf(ConfigurationKey("a") to ConfigurationValue.StringValue("1"))
        assertNull(snapshot[ConfigurationKey("missing")])
    }

    @Test
    fun mutatingTheSourceMapAfterConstructionDoesNotAffectTheSnapshot() {
        val entries = mutableMapOf<ConfigurationKey, ConfigurationValue>(
            ConfigurationKey("a") to ConfigurationValue.StringValue("original"),
        )
        val snapshot = ConfigurationSnapshot.create(1L, entries, digestCalculator)
        entries[ConfigurationKey("a")] = ConfigurationValue.StringValue("mutated")
        assertEquals(ConfigurationValue.StringValue("original"), snapshot[ConfigurationKey("a")])
    }

    @Test
    fun checksumIsDeterministicForIdenticalEntriesRegardlessOfInsertionOrder() {
        val first = ConfigurationSnapshot.create(
            1L,
            linkedMapOf(
                ConfigurationKey("a") to ConfigurationValue.StringValue("1"),
                ConfigurationKey("b") to ConfigurationValue.LongValue(2L),
            ),
            digestCalculator,
        )
        val second = ConfigurationSnapshot.create(
            1L,
            linkedMapOf(
                ConfigurationKey("b") to ConfigurationValue.LongValue(2L),
                ConfigurationKey("a") to ConfigurationValue.StringValue("1"),
            ),
            digestCalculator,
        )
        assertTrue(first.checksum contentEquals second.checksum)
    }

    @Test
    fun checksumDiffersWhenAnEntryValueDiffers() {
        val first = snapshotOf(ConfigurationKey("a") to ConfigurationValue.StringValue("1"))
        val second = snapshotOf(ConfigurationKey("a") to ConfigurationValue.StringValue("2"))
        assertFalse(first.checksum contentEquals second.checksum)
    }

    @Test
    fun checksumDoesNotDependOnVersion() {
        // The checksum is an integrity digest over entries, not an identity
        // of the whole snapshot (equals()/hashCode() also fold in version).
        val entries = mapOf(ConfigurationKey("a") to ConfigurationValue.StringValue("1"))
        val v1 = ConfigurationSnapshot.create(1L, entries, digestCalculator)
        val v2 = ConfigurationSnapshot.create(2L, entries, digestCalculator)
        assertTrue(v1.checksum contentEquals v2.checksum)
        assertNotEquals(v1, v2)
    }

    @Test
    fun toStringNeverRendersEntryValues() {
        val snapshot = snapshotOf(ConfigurationKey("secret-ish") to ConfigurationValue.StringValue("do-not-leak"))
        assertFalse(snapshot.toString().contains("do-not-leak"))
    }

    private fun snapshotOf(vararg entries: Pair<ConfigurationKey, ConfigurationValue>): ConfigurationSnapshot =
        ConfigurationSnapshot.create(1L, mapOf(*entries), digestCalculator)
}
