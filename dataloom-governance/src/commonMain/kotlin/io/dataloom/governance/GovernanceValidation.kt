package io.dataloom.governance

internal const val MAXIMUM_VOCABULARY_IDENTIFIER_LENGTH: Int = 128
internal const val MAXIMUM_PRINCIPAL_ID_LENGTH: Int = 256

/**
 * Validates a DataLoom-controlled vocabulary identifier (role ids, resource
 * types, audit event types).
 *
 * The charset is deliberately closed to `[A-Za-z0-9._:-]`, starting with an
 * alphanumeric. That makes a wildcard (`*`), whitespace, control characters,
 * and any string the canonical audit encoding could treat ambiguously
 * unrepresentable, rather than merely discouraged.
 */
internal fun requireVocabularyIdentifier(kind: String, value: String) {
    require(value.isNotBlank()) { "$kind must not be blank." }
    require(value.length <= MAXIMUM_VOCABULARY_IDENTIFIER_LENGTH) {
        "$kind must not exceed $MAXIMUM_VOCABULARY_IDENTIFIER_LENGTH characters."
    }
    require(value[0].isAsciiLetterOrDigit()) { "$kind must start with an ASCII letter or digit." }
    require(value.all { it.isAsciiLetterOrDigit() || it == '.' || it == '_' || it == ':' || it == '-' }) {
        "$kind may contain only ASCII letters, digits, '.', '_', ':' and '-'."
    }
}

private fun Char.isAsciiLetterOrDigit(): Boolean =
    this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9'

/**
 * Rejects strings that are not well-formed UTF-16 (a lone high or low
 * surrogate). The JVM and Kotlin/Native replace such sequences differently when
 * encoding to UTF-8, which would make the audit chain's canonical bytes — and
 * therefore its MACs — platform-dependent.
 */
internal fun requireWellFormedUnicode(kind: String, value: String) {
    var index = 0
    while (index < value.length) {
        val char = value[index]
        when {
            char.isHighSurrogate() -> {
                require(index + 1 < value.length && value[index + 1].isLowSurrogate()) {
                    "$kind must be well-formed Unicode (unpaired high surrogate at index $index)."
                }
                index += 2
            }

            char.isLowSurrogate() -> {
                throw IllegalArgumentException(
                    "$kind must be well-formed Unicode (unpaired low surrogate at index $index).",
                )
            }

            else -> index += 1
        }
    }
}
