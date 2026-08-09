package io.dataloom.api.configuration

import kotlin.test.Test
import kotlin.test.assertEquals

class ConfigurationSourceTest {

    @Test
    fun mutatingTheSourceMapAfterConstructionDoesNotAffectTheSource() {
        val entries = mutableMapOf<ConfigurationKey, ConfigurationValue>(
            ConfigurationKey("k") to ConfigurationValue.StringValue("original"),
        )
        val source = ConfigurationSource(ConfigurationScope.BUILT_IN_DEFAULT, entries)
        entries[ConfigurationKey("k")] = ConfigurationValue.StringValue("mutated")
        assertEquals(
            ConfigurationValue.StringValue("original"),
            source.entries[ConfigurationKey("k")],
        )
    }

    @Test
    fun sameScopeAndEntriesCompareAsEqual() {
        val a = ConfigurationSource(
            ConfigurationScope.LOCAL_OVERRIDE,
            mapOf(ConfigurationKey("k") to ConfigurationValue.BooleanValue(true)),
        )
        val b = ConfigurationSource(
            ConfigurationScope.LOCAL_OVERRIDE,
            mapOf(ConfigurationKey("k") to ConfigurationValue.BooleanValue(true)),
        )
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun scopeOrdinalsReflectTheDocumentedPrecedenceOrder() {
        assertEquals(0, ConfigurationScope.BUILT_IN_DEFAULT.ordinal)
        assertEquals(1, ConfigurationScope.REMOTE_ASSIGNED.ordinal)
        assertEquals(2, ConfigurationScope.LOCAL_OVERRIDE.ordinal)
    }
}
