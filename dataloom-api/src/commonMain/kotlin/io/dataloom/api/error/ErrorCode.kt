package io.dataloom.api.error

/**
 * Stable machine-readable DataLoom error code.
 *
 * Ownership: DataLoom error catalogue.
 */
@JvmInline
public value class ErrorCode(
    /** Underlying canonical error code value. */
    public val value: String,
) {
    init {
        require(value.isNotBlank()) { "ErrorCode must not be blank." }
    }

    override fun toString(): String = value
}
