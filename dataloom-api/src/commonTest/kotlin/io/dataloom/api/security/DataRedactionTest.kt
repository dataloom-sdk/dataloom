package io.dataloom.api.security

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DataRedactionTest {
    @Test
    fun strictDefaultKeepsPublicMasksInternalAndRemovesSensitiveData() {
        val secret = "credential-secret-value"
        val personal = "person@example.test"
        val result = StrictDataLoomRedactor().redact(
            ClassifiedData.of(
                mapOf(
                    "public.status" to value("healthy", DataClassification.PUBLIC),
                    "internal.node" to value("worker-17", DataClassification.INTERNAL),
                    "confidential.email" to value(personal, DataClassification.CONFIDENTIAL),
                    "restricted.token" to value(secret, DataClassification.RESTRICTED),
                ),
            ),
        )

        assertEquals("healthy", result.attributes["public.status"])
        assertEquals("[REDACTED]", result.attributes["internal.node"])
        assertNull(result.attributes["confidential.email"])
        assertNull(result.attributes["restricted.token"])
        assertEquals(
            RedactionSummary(
                inputFieldCount = 4,
                emittedFieldCount = 2,
                maskedFieldCount = 1,
                removedFieldCount = 2,
                truncatedFieldCount = 0,
                overflowFieldCount = 0,
            ),
            result.summary,
        )
        assertFalse(result.toString().contains(secret))
        assertFalse(result.toString().contains(personal))
        assertFalse(result.attributes.toString().contains("public.status"))
    }

    @Test
    fun outputLimitUsesStableKeyOrderAndReportsOverflow() {
        val redactor = StrictDataLoomRedactor(
            DataRedactionPolicy(
                maximumOutputFields = 2,
                maximumOutputValueLength = 16,
            ),
        )
        val first = ClassifiedData.of(
            linkedMapOf(
                "zeta" to value("z", DataClassification.PUBLIC),
                "alpha" to value("a", DataClassification.PUBLIC),
                "middle" to value("m", DataClassification.PUBLIC),
            ),
        )
        val second = ClassifiedData.of(
            linkedMapOf(
                "middle" to value("m", DataClassification.PUBLIC),
                "zeta" to value("z", DataClassification.PUBLIC),
                "alpha" to value("a", DataClassification.PUBLIC),
            ),
        )

        val firstResult = redactor.redact(first)
        val secondResult = redactor.redact(second)

        assertEquals(firstResult, secondResult)
        assertEquals(mapOf("alpha" to "a", "middle" to "m"), firstResult.attributes.entries)
        assertEquals(1, firstResult.summary.overflowFieldCount)
    }

    @Test
    fun keptValuesAreBoundedWithoutChangingMaskedValues() {
        val redactor = StrictDataLoomRedactor(
            DataRedactionPolicy(maximumOutputValueLength = 4),
        )
        val result = redactor.redact(
            ClassifiedData.of(
                mapOf(
                    "public.value" to value("abcdefgh", DataClassification.PUBLIC),
                    "internal.value" to value("abcdefgh", DataClassification.INTERNAL),
                ),
            ),
        )

        assertEquals("abcd", result.attributes["public.value"])
        assertEquals("[REDACTED]", result.attributes["internal.value"])
        assertEquals(1, result.summary.truncatedFieldCount)
        assertEquals(1, result.summary.maskedFieldCount)
    }

    @Test
    fun restrictedDataCannotBeConfiguredForDisclosure() {
        val policy = DataRedactionPolicy(
            publicAction = RedactionAction.KEEP,
            internalAction = RedactionAction.KEEP,
            confidentialAction = RedactionAction.MASK,
        )

        assertEquals(RedactionAction.REMOVE, policy.actionFor(DataClassification.RESTRICTED))
        assertFailsWith<IllegalArgumentException> {
            DataRedactionPolicy(confidentialAction = RedactionAction.KEEP)
        }
    }

    @Test
    fun classifiedInputRejectsUnsafeKeysAndNeverRendersValues() {
        val secret = "do-not-render-me"
        val input = ClassifiedData.of(
            mapOf("safe.key" to value(secret, DataClassification.RESTRICTED)),
        )

        assertFalse(input.toString().contains("safe.key"))
        assertFalse(input.toString().contains(secret))
        assertFalse(input.entries.getValue("safe.key").toString().contains(secret))
        assertFailsWith<IllegalArgumentException> {
            ClassifiedData.of(
                mapOf("unsafe\nkey" to value("value", DataClassification.PUBLIC)),
            )
        }
    }

    @Test
    fun emptyInputProducesCanonicalEmptyOutput() {
        val result = StrictDataLoomRedactor().redact(ClassifiedData.Empty)

        assertTrue(result.attributes.isEmpty())
        assertEquals(RedactedAttributes.Empty, result.attributes)
        assertEquals(
            RedactionSummary(0, 0, 0, 0, 0, 0),
            result.summary,
        )
    }

    private fun value(
        value: String,
        classification: DataClassification,
    ): ClassifiedDataValue = ClassifiedDataValue(value, classification)
}
