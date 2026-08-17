package io.dataloom.api.security

/**
 * Redacts secret-shaped substrings from free-text diagnostic content, most
 * notably [io.dataloom.api.error.DataLoomError.message].
 *
 * ## Why this exists
 *
 * [io.dataloom.api.error.DataLoomError.message] is documented to never
 * include credentials, tokens, keys, or personal data — but that constraint
 * is a convention each implementation must uphold when constructing its own
 * message, not something the type system enforces. There is no compile-time
 * guarantee against a future implementation putting sensitive content in
 * `message`. This contract exists as defense-in-depth for that case: a
 * best-effort, deterministic scan for common secret-shaped patterns, applied
 * on top of (never instead of) each implementation's own responsibility to
 * never put sensitive content in `message` in the first place.
 *
 * ## Relationship to [DataLoomRedactor]
 *
 * [DataLoomRedactor] redacts already-classified, structured key-value pairs
 * ([ClassifiedData]) — every field's sensitivity is known up front by the
 * caller. This contract instead scans unstructured free text for
 * secret-*shaped* substrings, since free text carries no field-level
 * classification to consult. The two are complementary: apply
 * [DataLoomRedactor] to structured operational attributes, and
 * [MessageContentRedactor] to free text that might embed secret-shaped
 * content despite not being expected to.
 */
public fun interface MessageContentRedactor {
    /**
     * Returns [message] with any recognized secret-shaped substrings
     * replaced by a mask. Returns [message] unchanged when nothing is
     * recognized. Never throws for any [String] input, including an empty
     * string.
     */
    public fun redact(message: String): String
}

/**
 * Deterministic, bounded, fail-closed reference [MessageContentRedactor].
 *
 * Recognizes a fixed set of common secret-shaped patterns: `Bearer`/
 * `Authorization` tokens, JWT-shaped three-segment tokens, AWS-style access
 * key IDs, sensitive query-string parameter values (`token`, `key`,
 * `apikey`, `api_key`, `secret`, `password`, `pwd`, `auth`, `access_token`
 * — case-insensitive; only the value is masked, the parameter name is kept
 * for diagnosability), URL Basic-Auth embedded credentials (`user:pass@`),
 * and email addresses. Input longer than [MAX_INPUT_LENGTH] characters is
 * bounded before scanning, so this never runs unbounded regex work against
 * adversarially large input.
 *
 * Each pattern is applied once, in a fixed order, across the whole input.
 * This is deliberately simple and predictable rather than iteratively
 * re-scanning masked output — deterministic, single-pass behavior is easier
 * to reason about and test than a scheme that could theoretically loop.
 *
 * ## What this deliberately does not do
 *
 * This is **not** a general-purpose secret scanner. It recognizes a fixed,
 * reference set of common patterns and nothing more — it will not detect
 * arbitrary opaque secrets, application-specific credential formats, or
 * content an attacker has deliberately obfuscated. Treat it as one layer of
 * defense-in-depth alongside — never instead of — each
 * [io.dataloom.api.error.DataLoomError] implementation's own responsibility
 * to never put sensitive content in `message` in the first place. A custom
 * [mask] should itself avoid looking like one of these patterns (the
 * default `[REDACTED]` does not), since masked output is not re-scanned.
 *
 * @param mask the literal replacement text for a recognized match, or for a
 *   recognized value within a larger match. Must be non-blank and at most
 *   64 characters.
 */
public class PatternBasedMessageContentRedactor(
    private val mask: String = DEFAULT_MASK,
) : MessageContentRedactor {

    init {
        require(mask.isNotBlank()) {
            "Message content redaction mask must not be blank."
        }
        require(mask.length <= MAX_MASK_LENGTH) {
            "Message content redaction mask must be at most $MAX_MASK_LENGTH characters."
        }
    }

    override fun redact(message: String): String {
        if (message.isEmpty()) {
            return message
        }
        var result = if (message.length > MAX_INPUT_LENGTH) {
            message.substring(0, MAX_INPUT_LENGTH)
        } else {
            message
        }
        result = BEARER_TOKEN_PATTERN.replace(result, mask)
        result = JWT_PATTERN.replace(result, mask)
        result = AWS_ACCESS_KEY_PATTERN.replace(result, mask)
        result = SENSITIVE_QUERY_PARAMETER_PATTERN.replace(result) { match -> match.groupValues[1] + mask }
        result = BASIC_AUTH_CREDENTIAL_PATTERN.replace(result) { match -> match.groupValues[1] + mask + "@" }
        result = EMAIL_ADDRESS_PATTERN.replace(result, mask)
        return result
    }

    public companion object {
        /** Default mask applied when no explicit mask is supplied. */
        public const val DEFAULT_MASK: String = "[REDACTED]"

        private const val MAX_MASK_LENGTH: Int = 64

        /**
         * Input longer than this is bounded before any pattern is applied,
         * so redaction cost stays predictable regardless of input size.
         */
        private const val MAX_INPUT_LENGTH: Int = 8_192

        private val BEARER_TOKEN_PATTERN = Regex("(?i)bearer\\s+[A-Za-z0-9._~+/=-]+")

        private val JWT_PATTERN = Regex("[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}")

        private val AWS_ACCESS_KEY_PATTERN = Regex("AKIA[0-9A-Z]{16}")

        private val SENSITIVE_QUERY_PARAMETER_PATTERN = Regex(
            "(?i)([?&](?:token|key|apikey|api_key|secret|password|pwd|auth|access_token)=)[^&\\s]+",
        )

        private val BASIC_AUTH_CREDENTIAL_PATTERN = Regex(
            "(?i)([a-z][a-z0-9+.-]*://)[^/\\s:@]+:[^/\\s:@]+@",
        )

        private val EMAIL_ADDRESS_PATTERN = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")
    }
}
