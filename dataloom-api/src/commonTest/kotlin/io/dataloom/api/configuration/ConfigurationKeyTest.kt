package io.dataloom.api.configuration

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ConfigurationKeyTest {

    @Test
    fun nonBlankValueIsAcceptedAndPreservedExactly() {
        val key = ConfigurationKey("sync.retry.max-attempts")
        assertEquals("sync.retry.max-attempts", key.value)
    }

    @Test
    fun blankValueIsRejected() {
        assertFailsWith<IllegalArgumentException> {
            ConfigurationKey("")
        }
    }

    @Test
    fun whitespaceOnlyValueIsRejected() {
        assertFailsWith<IllegalArgumentException> {
            ConfigurationKey("   ")
        }
    }

    @Test
    fun toStringReturnsTheUnderlyingValue() {
        val key = ConfigurationKey("feature.enabled")
        assertEquals("feature.enabled", key.toString())
    }

    @Test
    fun equalValuesCompareAsEqual() {
        assertEquals(ConfigurationKey("same"), ConfigurationKey("same"))
    }
}
