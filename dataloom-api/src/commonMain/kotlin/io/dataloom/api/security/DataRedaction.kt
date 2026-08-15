package io.dataloom.api.security

/** Sensitivity assigned to one field before it crosses an operational boundary. */
public enum class DataClassification {
    PUBLIC,
    INTERNAL,
    CONFIDENTIAL,
    RESTRICTED,
}

/** Deterministic action applied to one classified field. */
public enum class RedactionAction {
    KEEP,
    MASK,
    REMOVE,
}

/** A value that must be redacted before export, persistence, or diagnostics. */
public data class ClassifiedDataValue(
    public val value: String,
    public val classification: DataClassification,
) {
    init {
        require(value.length <= MAX_CLASSIFIED_VALUE_LENGTH) {
            "Classified data value exceeds the maximum supported length."
        }
    }

    /** Never renders the classified value. */
    override fun toString(): String =
        "ClassifiedDataValue(classification=$classification, value=[REDACTED])"

    private companion object {
        const val MAX_CLASSIFIED_VALUE_LENGTH: Int = 16_384
    }
}

/** Immutable classified input accepted by [DataLoomRedactor]. */
public class ClassifiedData private constructor(
    private val values: Map<String, ClassifiedDataValue>,
) {
    /** Defensive read-only copy for policy evaluation and explicit integration. */
    public val entries: Map<String, ClassifiedDataValue>
        get() = values.toMap()

    public val size: Int
        get() = values.size

    public fun isEmpty(): Boolean = values.isEmpty()

    override fun equals(other: Any?): Boolean =
        this === other || other is ClassifiedData && values == other.values

    override fun hashCode(): Int = values.hashCode()

    /** Never renders field names or values. */
    override fun toString(): String = "ClassifiedData(entryCount=${values.size})"

    public companion object {
        public val Empty: ClassifiedData = ClassifiedData(emptyMap())

        /**
         * Creates a classified snapshot after validating bounded, serialization-safe keys.
         *
         * Keys are intentionally restricted to stable ASCII tokens so a field name cannot
         * inject control characters into logs, wire records, or dashboard adapters.
         */
        public fun of(entries: Map<String, ClassifiedDataValue>): ClassifiedData {
            if (entries.isEmpty()) {
                return Empty
            }
            require(entries.size <= MAX_CLASSIFIED_FIELD_COUNT) {
                "Classified data contains too many fields."
            }
            entries.keys.forEach { key: String ->
                require(isBoundedToken(key, MAX_CLASSIFIED_KEY_LENGTH, ::isSafeAttributeKeyCharacter)) {
                    "Classified data key must be a bounded ASCII token."
                }
            }
            return ClassifiedData(entries.toMap())
        }

        private const val MAX_CLASSIFIED_FIELD_COUNT: Int = 1_024
        private const val MAX_CLASSIFIED_KEY_LENGTH: Int = 128
    }
}

/** Immutable output that is safe to pass to an operational envelope. */
public class RedactedAttributes private constructor(
    private val values: Map<String, String>,
) {
    /** Values are already redacted; a defensive copy prevents mutation. */
    public val entries: Map<String, String>
        get() = values.toMap()

    public val size: Int
        get() = values.size

    public operator fun get(key: String): String? = values[key]

    public fun isEmpty(): Boolean = values.isEmpty()

    override fun equals(other: Any?): Boolean =
        this === other || other is RedactedAttributes && values == other.values

    override fun hashCode(): Int = values.hashCode()

    /** Never renders field names or values. */
    override fun toString(): String = "RedactedAttributes(entryCount=${values.size})"

    public companion object {
        public val Empty: RedactedAttributes = RedactedAttributes(emptyMap())

        internal fun fromRedactor(entries: Map<String, String>): RedactedAttributes {
            if (entries.isEmpty()) {
                return Empty
            }
            require(entries.size <= MAX_REDACTED_FIELD_COUNT) {
                "Redacted attributes contain too many fields."
            }
            entries.forEach { (key: String, value: String) ->
                require(isBoundedToken(key, MAX_REDACTED_KEY_LENGTH, ::isSafeAttributeKeyCharacter)) {
                    "Redacted attribute key must be a bounded ASCII token."
                }
                require(value.length <= MAX_REDACTED_VALUE_LENGTH) {
                    "Redacted attribute value exceeds the maximum supported length."
                }
            }
            return RedactedAttributes(entries.toMap())
        }

        private const val MAX_REDACTED_FIELD_COUNT: Int = 256
        private const val MAX_REDACTED_KEY_LENGTH: Int = 128
        private const val MAX_REDACTED_VALUE_LENGTH: Int = 4_096
    }
}

/** Bounded fail-closed policy shared by logs, events, audit, traces, and support output. */
public data class DataRedactionPolicy(
    public val publicAction: RedactionAction = RedactionAction.KEEP,
    public val internalAction: RedactionAction = RedactionAction.MASK,
    public val confidentialAction: RedactionAction = RedactionAction.REMOVE,
    public val maximumOutputFields: Int = 64,
    public val maximumOutputValueLength: Int = 256,
    public val mask: String = "[REDACTED]",
) {
    init {
        require(confidentialAction != RedactionAction.KEEP) {
            "Confidential data cannot use the KEEP redaction action."
        }
        require(maximumOutputFields in 1..256) {
            "maximumOutputFields must be between 1 and 256."
        }
        require(maximumOutputValueLength in 1..4_096) {
            "maximumOutputValueLength must be between 1 and 4096."
        }
        require(mask.isNotBlank() && mask.length <= 64) {
            "Redaction mask must be non-blank and at most 64 characters."
        }
    }

    /** Restricted fields are always removed and cannot be configured for disclosure. */
    public fun actionFor(classification: DataClassification): RedactionAction =
        when (classification) {
            DataClassification.PUBLIC -> publicAction
            DataClassification.INTERNAL -> internalAction
            DataClassification.CONFIDENTIAL -> confidentialAction
            DataClassification.RESTRICTED -> RedactionAction.REMOVE
        }
}

/** Non-sensitive counters explaining one deterministic redaction operation. */
public data class RedactionSummary(
    public val inputFieldCount: Int,
    public val emittedFieldCount: Int,
    public val maskedFieldCount: Int,
    public val removedFieldCount: Int,
    public val truncatedFieldCount: Int,
    public val overflowFieldCount: Int,
) {
    init {
        require(
            listOf(
                inputFieldCount,
                emittedFieldCount,
                maskedFieldCount,
                removedFieldCount,
                truncatedFieldCount,
                overflowFieldCount,
            ).all { it >= 0 },
        ) { "Redaction counters must not be negative." }
        require(emittedFieldCount + removedFieldCount + overflowFieldCount == inputFieldCount) {
            "Redaction counters must account for every input field exactly once."
        }
        require(maskedFieldCount <= emittedFieldCount) {
            "Masked fields must be a subset of emitted fields."
        }
        require(truncatedFieldCount <= emittedFieldCount) {
            "Truncated fields must be a subset of emitted fields."
        }
    }
}

/** Exact safe output and evidence from one redaction operation. */
public data class RedactionResult(
    public val attributes: RedactedAttributes,
    public val summary: RedactionSummary,
)

/** Central redaction boundary for operational, audit, telemetry, and support data. */
public fun interface DataLoomRedactor {
    public fun redact(input: ClassifiedData): RedactionResult
}

/**
 * Deterministic, bounded, fail-closed [DataLoomRedactor].
 *
 * Field selection is sorted by key before the output limit is applied so map iteration order
 * cannot change persisted or exported results. Restricted fields are always removed.
 */
public class StrictDataLoomRedactor(
    private val policy: DataRedactionPolicy = DataRedactionPolicy(),
) : DataLoomRedactor {
    override fun redact(input: ClassifiedData): RedactionResult {
        val ordered: List<Map.Entry<String, ClassifiedDataValue>> =
            input.entries.entries.sortedBy { it.key }
        val output: MutableMap<String, String> = linkedMapOf()
        var masked: Int = 0
        var removed: Int = 0
        var truncated: Int = 0
        var overflow: Int = 0

        ordered.forEach { entry: Map.Entry<String, ClassifiedDataValue> ->
            when (policy.actionFor(entry.value.classification)) {
                RedactionAction.REMOVE -> removed += 1
                RedactionAction.MASK -> {
                    if (output.size >= policy.maximumOutputFields) {
                        overflow += 1
                    } else {
                        output[entry.key] = policy.mask
                        masked += 1
                    }
                }
                RedactionAction.KEEP -> {
                    if (output.size >= policy.maximumOutputFields) {
                        overflow += 1
                    } else {
                        val value: String = entry.value.value
                        if (value.length > policy.maximumOutputValueLength) {
                            output[entry.key] = value.take(policy.maximumOutputValueLength)
                            truncated += 1
                        } else {
                            output[entry.key] = value
                        }
                    }
                }
            }
        }

        return RedactionResult(
            attributes = RedactedAttributes.fromRedactor(output),
            summary = RedactionSummary(
                inputFieldCount = ordered.size,
                emittedFieldCount = output.size,
                maskedFieldCount = masked,
                removedFieldCount = removed,
                truncatedFieldCount = truncated,
                overflowFieldCount = overflow,
            ),
        )
    }
}

private fun isSafeAttributeKeyCharacter(character: Char): Boolean =
    character in 'a'..'z' ||
        character in 'A'..'Z' ||
        character in '0'..'9' ||
        character == '.' ||
        character == '_' ||
        character == '-'
