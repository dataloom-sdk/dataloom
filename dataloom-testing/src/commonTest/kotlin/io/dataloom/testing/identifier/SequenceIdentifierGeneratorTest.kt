package io.dataloom.testing.identifier

import io.dataloom.api.identifier.QueueEntryId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Verifies the [SequenceIdentifierGenerator] contract.
 *
 * Uses [QueueEntryId] as a representative strongly typed identifier.
 * No system-clock access, randomness, or platform dependency is used.
 */
class SequenceIdentifierGeneratorTest {

    // -------------------------------------------------------------------------
    // Construction — valid inputs
    // -------------------------------------------------------------------------

    @Test
    fun `single value is accepted`() {
        val generator = SequenceIdentifierGenerator(
            values = listOf(QueueEntryId("entry-001")),
        )
        assertEquals(QueueEntryId("entry-001"), generator.generate())
    }

    @Test
    fun `multiple values are accepted`() {
        val generator = SequenceIdentifierGenerator(
            values = listOf(
                QueueEntryId("entry-001"),
                QueueEntryId("entry-002"),
                QueueEntryId("entry-003"),
            ),
        )

        assertEquals(QueueEntryId("entry-001"), generator.generate())
        assertEquals(QueueEntryId("entry-002"), generator.generate())
        assertEquals(QueueEntryId("entry-003"), generator.generate())
    }

    // -------------------------------------------------------------------------
    // Construction — empty source is rejected
    // -------------------------------------------------------------------------

    @Test
    fun `empty value list is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            SequenceIdentifierGenerator<QueueEntryId>(values = emptyList())
        }
    }

    // -------------------------------------------------------------------------
    // Order preservation
    // -------------------------------------------------------------------------

    @Test
    fun `values are returned in supplied order`() {
        val values = listOf(
            QueueEntryId("b"),
            QueueEntryId("a"),
            QueueEntryId("c"),
        )
        val generator = SequenceIdentifierGenerator(values = values)

        assertEquals(QueueEntryId("b"), generator.generate())
        assertEquals(QueueEntryId("a"), generator.generate())
        assertEquals(QueueEntryId("c"), generator.generate())
    }

    // -------------------------------------------------------------------------
    // Exhaustion
    // -------------------------------------------------------------------------

    @Test
    fun `generate after exhaustion throws NoSuchElementException`() {
        val generator = SequenceIdentifierGenerator(
            values = listOf(QueueEntryId("entry-001")),
        )

        generator.generate() // consume the only value

        assertFailsWith<NoSuchElementException> {
            generator.generate()
        }
    }

    @Test
    fun `exhaustion message is informative`() {
        val generator = SequenceIdentifierGenerator(
            values = listOf(QueueEntryId("entry-only")),
        )
        generator.generate()

        val exception = assertFailsWith<NoSuchElementException> {
            generator.generate()
        }
        val message = exception.message ?: ""
        assertEquals(
            true,
            message.isNotBlank(),
            "Exhaustion message must not be blank.",
        )
    }

    @Test
    fun `final value is not repeated automatically`() {
        val generator = SequenceIdentifierGenerator(
            values = listOf(QueueEntryId("final")),
        )
        generator.generate()

        // Second call must throw, not silently return QueueEntryId("final").
        assertFailsWith<NoSuchElementException> {
            generator.generate()
        }
    }

    // -------------------------------------------------------------------------
    // Defensive copy
    // -------------------------------------------------------------------------

    @Test
    fun `source list mutation after construction does not affect generator`() {
        val source = mutableListOf(QueueEntryId("original"))
        val generator = SequenceIdentifierGenerator(values = source)

        // Mutate the source after construction.
        source.clear()
        source.add(QueueEntryId("mutated"))

        // Generator must still return the original value.
        assertEquals(QueueEntryId("original"), generator.generate())
    }

    @Test
    fun `source list add after construction does not affect generator`() {
        val source = mutableListOf(QueueEntryId("a"), QueueEntryId("b"))
        val generator = SequenceIdentifierGenerator(values = source)

        // Add to source after construction.
        source.add(QueueEntryId("c"))

        // Generator provides only the two original values.
        assertEquals(QueueEntryId("a"), generator.generate())
        assertEquals(QueueEntryId("b"), generator.generate())

        // Generator is exhausted after the two original values.
        assertFailsWith<NoSuchElementException> {
            generator.generate()
        }
    }
}
