package io.dataloom.api.context

/**
 * Immutable optional contextual attributes for DataLoom contracts.
 *
 * Keys must be non-blank. Values are preserved exactly as provided, including
 * empty strings.
 *
 * Do not store credentials, tokens, encryption keys, personal data, or full
 * payloads in metadata.
 *
 * `toString()` is deliberately redacted and is not a serialization format.
 */
public class DataLoomMetadata private constructor(
    private val values: Map<String, String>,
) {
    /**
     * Read-only metadata snapshot.
     *
     * Each access returns a copy to prevent accidental mutation of internal
     * state.
     */
    public val entries: Map<String, String>
        get() = values.toMap()

    /**
     * Returns the value associated with [key], or `null` when [key] is absent.
     */
    public operator fun get(key: String): String? = values[key]

    /**
     * Returns `true` when this metadata contains no entries.
     */
    public fun isEmpty(): Boolean = values.isEmpty()

    override fun equals(other: Any?): Boolean {
        if (this === other) {
            return true
        }
        if (other !is DataLoomMetadata) {
            return false
        }
        return values == other.values
    }

    override fun hashCode(): Int = values.hashCode()

    /** Returns only bounded cardinality and never renders metadata keys or values. */
    override fun toString(): String = "DataLoomMetadata(entryCount=${values.size})"

    public companion object {
        /** Empty immutable metadata value. */
        public val Empty: DataLoomMetadata = DataLoomMetadata(emptyMap())

        /**
         * Creates immutable metadata from [entries].
         *
         * The input map is defensively copied and keys are validated as
         * non-blank.
         */
        public fun of(entries: Map<String, String>): DataLoomMetadata {
            if (entries.isEmpty()) {
                return Empty
            }

            val copy: Map<String, String> = entries.toMap()
            copy.keys.forEach { key: String ->
                require(key.isNotBlank()) { "Metadata key must not be blank." }
            }
            return DataLoomMetadata(copy)
        }
    }
}
