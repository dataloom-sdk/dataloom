package io.dataloom.api.configuration

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ConfigurationSchemaTest {

    @Test
    fun emptyEntriesAreRejected() {
        assertFailsWith<IllegalArgumentException> {
            ConfigurationSchema(emptyList())
        }
    }

    @Test
    fun duplicateKeysAreRejected() {
        val key = ConfigurationKey("duplicate")
        assertFailsWith<IllegalArgumentException> {
            ConfigurationSchema(
                listOf(
                    ConfigurationEntrySchema(key, ConfigurationValueType.STRING),
                    ConfigurationEntrySchema(key, ConfigurationValueType.LONG),
                ),
            )
        }
    }

    @Test
    fun getReturnsTheDeclaredEntryForAKnownKey() {
        val key = ConfigurationKey("timeout-ms")
        val schema = ConfigurationSchema(listOf(ConfigurationEntrySchema(key, ConfigurationValueType.LONG)))
        assertEquals(ConfigurationValueType.LONG, schema[key]?.type)
    }

    @Test
    fun getReturnsNullForAnUndeclaredKey() {
        val schema = ConfigurationSchema(
            listOf(ConfigurationEntrySchema(ConfigurationKey("declared"), ConfigurationValueType.STRING)),
        )
        assertNull(schema[ConfigurationKey("undeclared")])
    }

    @Test
    fun requiredDefaultsToTrue() {
        val entry = ConfigurationEntrySchema(ConfigurationKey("k"), ConfigurationValueType.STRING)
        assertEquals(true, entry.required)
    }
}
