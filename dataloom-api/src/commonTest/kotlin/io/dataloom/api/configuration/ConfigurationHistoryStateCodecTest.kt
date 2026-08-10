package io.dataloom.api.configuration

import io.dataloom.api.security.DataLoomDigestCalculator
import io.dataloom.api.security.KeyReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ConfigurationHistoryStateCodecTest {

    private val digestCalculator: DataLoomDigestCalculator = FakeDataLoomDigestCalculator()
    private val codec = ConfigurationHistoryStateCodec(digestCalculator)

    @Test
    fun roundTripsAnEmptyHistory() {
        val state = ConfigurationHistoryState(emptyList())
        assertEquals(state, codec.decode(codec.encode(state)))
    }

    @Test
    fun roundTripsOneSnapshotWithEveryValueType() {
        val entries = mapOf(
            ConfigurationKey("name") to ConfigurationValue.StringValue("acme corp"),
            ConfigurationKey("retries") to ConfigurationValue.LongValue(3L),
            ConfigurationKey("timeout-ratio") to ConfigurationValue.DoubleValue(0.75),
            ConfigurationKey("feature-flag") to ConfigurationValue.BooleanValue(true),
            ConfigurationKey("api-key") to ConfigurationValue.SecretReferenceValue(KeyReference("vault://api-key")),
        )
        val snapshot = ConfigurationSnapshot.create(1L, entries, digestCalculator)
        val state = ConfigurationHistoryState(listOf(snapshot))

        val decoded = codec.decode(codec.encode(state))

        assertEquals(state, decoded)
        assertEquals(snapshot.checksum, decoded.retainedSnapshots.single().checksum)
    }

    @Test
    fun roundTripsMultipleRetainedSnapshots() {
        val state = ConfigurationHistoryState(
            listOf(
                ConfigurationSnapshot.create(
                    1L,
                    mapOf(ConfigurationKey("k") to ConfigurationValue.LongValue(1L)),
                    digestCalculator,
                ),
                ConfigurationSnapshot.create(
                    2L,
                    mapOf(ConfigurationKey("k") to ConfigurationValue.LongValue(2L)),
                    digestCalculator,
                ),
            ),
        )

        assertEquals(state, codec.decode(codec.encode(state)))
    }

    @Test
    fun roundTripsValuesContainingSeparatorCharacters() {
        val entries = mapOf(
            ConfigurationKey("k|1") to ConfigurationValue.StringValue("v:1\n\t|weird"),
        )
        val snapshot = ConfigurationSnapshot.create(1L, entries, digestCalculator)
        val state = ConfigurationHistoryState(listOf(snapshot))

        assertEquals(state, codec.decode(codec.encode(state)))
    }

    @Test
    fun decodeRejectsAnUnrecognizedHeader() {
        assertFailsWith<IllegalArgumentException> {
            codec.decode("NOT_A_CONFIG_HISTORY_PAYLOAD\t1")
        }
    }

    @Test
    fun decodeRejectsATamperedChecksum() {
        val snapshot = ConfigurationSnapshot.create(
            1L,
            mapOf(ConfigurationKey("k") to ConfigurationValue.LongValue(1L)),
            digestCalculator,
        )
        val encoded = codec.encode(ConfigurationHistoryState(listOf(snapshot)))
        val lines = encoded.split('\n').toMutableList()
        val fields = lines[1].split('|').toMutableList()
        fields[1] = "0000000000000000000000000000000000000000000000000000000000000000"
        lines[1] = fields.joinToString("|")

        assertFailsWith<IllegalArgumentException> {
            codec.decode(lines.joinToString("\n"))
        }
    }

    @Test
    fun decodeRejectsATruncatedEntryCount() {
        val snapshot = ConfigurationSnapshot.create(
            1L,
            mapOf(ConfigurationKey("k") to ConfigurationValue.LongValue(1L)),
            digestCalculator,
        )
        val encoded = codec.encode(ConfigurationHistoryState(listOf(snapshot)))
        val lines = encoded.split('\n').toMutableList()
        val fields = lines[1].split('|').toMutableList()
        fields[2] = "5" // claims 5 entries but only 1 is present
        lines[1] = fields.joinToString("|")

        assertFailsWith<IllegalArgumentException> {
            codec.decode(lines.joinToString("\n"))
        }
    }

    @Test
    fun encodeRejectsAPayloadBeyondTheBoundedLimit() {
        val oversized = ConfigurationValue.StringValue("x".repeat(2_000_000))
        val snapshot = ConfigurationSnapshot.create(
            1L,
            mapOf(ConfigurationKey("k") to oversized),
            digestCalculator,
        )
        assertFailsWith<IllegalArgumentException> {
            codec.encode(ConfigurationHistoryState(listOf(snapshot)))
        }
    }
}
