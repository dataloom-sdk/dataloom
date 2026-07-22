package io.dataloom.api.retry

/**
 * Identifies the logical operation being evaluated by a [RetryPolicy].
 *
 * [RetryOperation] is an extensible value type. Using a value class avoids
 * changing the public API whenever a new provider operation is introduced.
 * It is not a closed enumeration.
 *
 * ## Constraints
 *
 * - [value] must not be blank or whitespace-only.
 * - Valid input is preserved exactly as supplied.
 * - No normalization or automatic generation is applied.
 * - Construction does not execute any operation, access storage, or schedule work.
 * - `toString()` returns the underlying [value].
 *
 * ## Equality
 *
 * Equality compares [value] by content.
 *
 * ## Example placeholder values
 *
 * ```
 * transport.push
 * transport.pull
 * storage.read-outbound
 * storage.apply-inbound
 * storage.write-checkpoint
 * provider.initialize
 * scheduler.schedule
 * queue.acquire
 * ```
 *
 * These examples are illustrative only and must not be treated as an
 * exhaustive catalogue. New provider or runtime operations may be represented
 * by adding new [RetryOperation] values without changing the public API.
 *
 * @param value the logical operation identifier. Must not be blank.
 */
@JvmInline
public value class RetryOperation(
    /** Logical operation identifier. Must not be blank. */
    public val value: String,
) {
    init {
        require(value.isNotBlank()) { "RetryOperation value must not be blank." }
    }

    override fun toString(): String = value
}
