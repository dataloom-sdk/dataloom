package io.dataloom.api.context

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class DataLoomMetadataTest {

    @Test
    fun `empty metadata can be created`() {
        val metadata: DataLoomMetadata = DataLoomMetadata.Empty

        assertEquals(emptyMap(), metadata.entries)
        assertEquals(true, metadata.isEmpty())
    }

    @Test
    fun `valid key value pairs are preserved`() {
        val metadata: DataLoomMetadata = DataLoomMetadata.of(
            mapOf(
                "channel" to "manual",
                "source" to "host-app",
            ),
        )

        assertEquals("manual", metadata["channel"])
        assertEquals("host-app", metadata["source"])
    }

    @Test
    fun `blank metadata keys are rejected`() {
        assertFailsWith<IllegalArgumentException> {
            DataLoomMetadata.of(mapOf("" to "value"))
        }
    }

    @Test
    fun `whitespace-only metadata keys are rejected`() {
        assertFailsWith<IllegalArgumentException> {
            DataLoomMetadata.of(mapOf("   " to "value"))
        }
    }

    @Test
    fun `empty values are preserved`() {
        val metadata: DataLoomMetadata = DataLoomMetadata.of(mapOf("notes" to ""))

        assertEquals("", metadata["notes"])
    }

    @Test
    fun `equal metadata compares as equal`() {
        val first: DataLoomMetadata = DataLoomMetadata.of(mapOf("a" to "1"))
        val second: DataLoomMetadata = DataLoomMetadata.of(mapOf("a" to "1"))

        assertEquals(first, second)
        assertEquals(first.hashCode(), second.hashCode())
    }

    @Test
    fun `mutable source map cannot mutate created metadata`() {
        val source: MutableMap<String, String> = mutableMapOf("key" to "value")
        val metadata: DataLoomMetadata = DataLoomMetadata.of(source)

        source["key"] = "changed"
        source["other"] = "added"

        assertEquals("value", metadata["key"])
        assertNull(metadata["other"])
    }

    @Test
    fun `lookup for missing key returns null`() {
        val metadata: DataLoomMetadata = DataLoomMetadata.of(mapOf("key" to "value"))

        assertNull(metadata["missing"])
    }
}
