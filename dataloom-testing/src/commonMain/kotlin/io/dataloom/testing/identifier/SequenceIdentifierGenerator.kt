package io.dataloom.testing.identifier

import io.dataloom.api.identifier.IdentifierGenerator

/**
 * A deterministic [IdentifierGenerator] that returns values from a
 * finite, pre-supplied sequence.
 *
 * ## Purpose
 *
 * [SequenceIdentifierGenerator] is a test utility intended for deterministic,
 * platform-independent tests. It enables components that depend on
 * [IdentifierGenerator] to be tested with predictable, ordered identifier
 * values.
 *
 * ## Semantics
 *
 * - Values are returned in the order they were supplied.
 * - Each call to [generate] consumes one value.
 * - When all values have been consumed, [generate] throws
 *   [NoSuchElementException] with a clear message.
 * - The sequence does not repeat automatically.
 * - The final value is not silently recycled.
 *
 * ## Defensive copy
 *
 * The supplied [values] collection is defensively copied at construction
 * time. Subsequent mutation of the source collection does not affect the
 * generator.
 *
 * ## No randomness or clock access
 *
 * [SequenceIdentifierGenerator] uses no randomness and performs no clock
 * access.
 *
 * ## Thread safety
 *
 * [SequenceIdentifierGenerator] is not thread-safe. Test code that shares a
 * single instance across threads must coordinate concurrent access externally.
 *
 * ## Usage
 *
 * ```kotlin
 * val generator = SequenceIdentifierGenerator(
 *     values = listOf(
 *         QueueEntryId("entry-001"),
 *         QueueEntryId("entry-002"),
 *     ),
 * )
 *
 * check(generator.generate() == QueueEntryId("entry-001"))
 * check(generator.generate() == QueueEntryId("entry-002"))
 * // Next call throws NoSuchElementException
 * ```
 *
 * Do not use [SequenceIdentifierGenerator] in production code.
 *
 * @param T the strongly typed identifier produced by this generator.
 * @param values the finite sequence of identifier values to return. Must not
 *   be empty. The collection is defensively copied.
 * @throws IllegalArgumentException if [values] is empty.
 */
public class SequenceIdentifierGenerator<T>(
    values: Iterable<T>,
) : IdentifierGenerator<T> {

    private val sequence: List<T>
    private var index: Int = 0

    init {
        val copied = values.toList()
        require(copied.isNotEmpty()) {
            "SequenceIdentifierGenerator requires at least one value."
        }
        sequence = copied
    }

    /**
     * Returns the next identifier in the pre-supplied sequence.
     *
     * Each call advances the internal position by one. When all values
     * have been consumed, subsequent calls throw [NoSuchElementException].
     *
     * @return the next [T] in the configured sequence.
     * @throws NoSuchElementException if the sequence is exhausted.
     */
    override fun generate(): T {
        if (index >= sequence.size) {
            throw NoSuchElementException(
                "SequenceIdentifierGenerator exhausted after ${sequence.size} value(s). " +
                    "Supply additional values to the generator for this test scenario.",
            )
        }
        return sequence[index++]
    }
}
