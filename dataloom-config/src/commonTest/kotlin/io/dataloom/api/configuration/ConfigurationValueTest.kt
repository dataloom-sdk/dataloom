package io.dataloom.api.configuration

import io.dataloom.api.security.KeyReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class ConfigurationValueTest {

    @Test
    fun stringValueReportsStringType() {
        assertEquals(ConfigurationValueType.STRING, ConfigurationValue.StringValue("v").type)
    }

    @Test
    fun longValueReportsLongType() {
        assertEquals(ConfigurationValueType.LONG, ConfigurationValue.LongValue(1L).type)
    }

    @Test
    fun doubleValueReportsDoubleType() {
        assertEquals(ConfigurationValueType.DOUBLE, ConfigurationValue.DoubleValue(1.5).type)
    }

    @Test
    fun booleanValueReportsBooleanType() {
        assertEquals(ConfigurationValueType.BOOLEAN, ConfigurationValue.BooleanValue(true).type)
    }

    @Test
    fun secretReferenceValueReportsSecretReferenceType() {
        val value = ConfigurationValue.SecretReferenceValue(KeyReference("android-keystore:sync-key"))
        assertEquals(ConfigurationValueType.SECRET_REFERENCE, value.type)
    }

    @Test
    fun secretReferenceValueNeverCarriesRawSecretBytes() {
        // The only constructor parameter is a KeyReference (a label), not a
        // ByteArray/String secret — this test documents that constraint at
        // the type level; it would fail to compile otherwise.
        val value = ConfigurationValue.SecretReferenceValue(KeyReference("kms-key-1"))
        assertEquals("kms-key-1", value.reference.value)
    }

    @Test
    fun differentValuesOfTheSameTypeCompareAsUnequal() {
        assertNotEquals(ConfigurationValue.StringValue("a"), ConfigurationValue.StringValue("b"))
    }
}
