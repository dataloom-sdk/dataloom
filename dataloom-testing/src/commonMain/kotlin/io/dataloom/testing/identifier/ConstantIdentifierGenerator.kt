package io.dataloom.testing.identifier

import io.dataloom.api.identifier.IdentifierGenerator

/**
 * A deterministic [IdentifierGenerator] that always returns the same
 * configured value.
 *
 * ## Purpose
 *
 * [ConstantIdentifierGenerator] is a test utility intended for deterministic,
 * platform-independent tests where the generated identifier value is not under
 * test and uniqueness is not required. It is useful when a test executes only
 * one creation path or does not care about identifier uniqueness.
 *
 * ## Semantics
 *
 * Every call to [generate] returns the same [value] supplied at construction.
 *
 * ## Uniqueness
 *
 * [ConstantIdentifierGenerator] does not guarantee uniqueness. Every call
 * returns the identical value. Do not use this generator in tests that
 * verify identifier uniqueness.
 *
 * ## No randomness or clock access
 *
 * [ConstantIdentifierGenerator] uses no randomness and performs no clock
 * access.
 *
 * ## Thread safety
 *
 * [ConstantIdentifierGenerator] is immutable and safe for concurrent use.
 *
 * ## Usage
 *
 * ```kotlin
 * val generator = ConstantIdentifierGenerator(
 *     value = QueueEntryId("entry-fixed"),
 * )
 *
 * check(generator.generate() == QueueEntryId("entry-fixed"))
 * check(generator.generate() == QueueEntryId("entry-fixed"))
 * ```
 *
 * Do not use [ConstantIdentifierGenerator] in production code.
 *
 * @param T the strongly typed identifier produced by this generator.
 * @param value the constant identifier value returned by every [generate]
 *   invocation.
 */
public class ConstantIdentifierGenerator<T>(
    /** The constant value returned by every [generate] invocation. */
    public val value: T,
) : IdentifierGenerator<T> {

    /**
     * Returns the constant [value] configured at construction.
     *
     * This method performs no I/O, uses no randomness, and does not advance
     * any state. Every invocation returns the same value.
     *
     * @return the configured constant identifier of type [T].
     */
    override fun generate(): T = value

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ConstantIdentifierGenerator<*>) return false
        return value == other.value
    }

    override fun hashCode(): Int = value.hashCode()

    override fun toString(): String = "ConstantIdentifierGenerator(value=$value)"
}
