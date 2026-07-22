package io.dataloom.testing.identifier

import io.dataloom.api.identifier.ConflictId
import io.dataloom.api.identifier.QueueEntryId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Verifies the [ConstantIdentifierGenerator] contract.
 *
 * No system-clock access, randomness, or platform dependency is used.
 */
class ConstantIdentifierGeneratorTest {

    // -------------------------------------------------------------------------
    // Constant return value
    // -------------------------------------------------------------------------

    @Test
    fun `generate returns the configured value`() {
        val expected = QueueEntryId("entry-fixed")
        val generator = ConstantIdentifierGenerator(value = expected)

        assertEquals(expected, generator.generate())
    }

    @Test
    fun `generate returns the same value on repeated calls`() {
        val generator = ConstantIdentifierGenerator(value = QueueEntryId("repeat"))

        val first = generator.generate()
        val second = generator.generate()
        val third = generator.generate()

        assertEquals(first, second)
        assertEquals(second, third)
    }

    @Test
    fun `generate returns the configured value for a different identifier type`() {
        val expected = ConflictId("conflict-fixed")
        val generator = ConstantIdentifierGenerator(value = expected)

        assertEquals(expected, generator.generate())
    }

    @Test
    fun `generate produces equal values across many invocations`() {
        val generator = ConstantIdentifierGenerator(value = QueueEntryId("stable"))

        repeat(50) {
            assertEquals(QueueEntryId("stable"), generator.generate())
        }
    }

    // -------------------------------------------------------------------------
    // Configured value property
    // -------------------------------------------------------------------------

    @Test
    fun `value property matches the value supplied at construction`() {
        val expected = QueueEntryId("prop-check")
        val generator = ConstantIdentifierGenerator(value = expected)

        assertEquals(expected, generator.value)
    }

    // -------------------------------------------------------------------------
    // Equality
    // -------------------------------------------------------------------------

    @Test
    fun `two generators with the same value compare as equal`() {
        val a = ConstantIdentifierGenerator(value = QueueEntryId("same"))
        val b = ConstantIdentifierGenerator(value = QueueEntryId("same"))

        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `two generators with different values compare as unequal`() {
        val a = ConstantIdentifierGenerator(value = QueueEntryId("a"))
        val b = ConstantIdentifierGenerator(value = QueueEntryId("b"))

        assertNotEquals(a, b)
    }
}
