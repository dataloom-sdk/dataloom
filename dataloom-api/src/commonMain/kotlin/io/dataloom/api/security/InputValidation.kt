package io.dataloom.api.security

/**
 * Reusable security primitive: `true` when [value] is a non-blank,
 * length-bounded token drawn only from an explicitly allowed character set.
 *
 * Establishes `#93`'s required "input validation" security primitive
 * (alongside the already-shipped integrity/redaction/key-reference
 * primitives in this same package). Before this function existed, the
 * identical shape — a length bound plus a per-character allowlist check
 * against untrusted external input — was independently hand-rolled in two
 * places with no shared implementation backing either:
 * `OperationalEventEnvelope`'s private `isOperationalToken()` and this
 * file's own private `isSafeAttributeKey()`. Both now delegate to this
 * function; see their call sites for the concrete adoption.
 *
 * This exists specifically because a field name, event type, or similar
 * bounded identifier that later flows into logs, wire records, dashboard
 * adapters, or persisted state must not be able to inject control
 * characters or grow unboundedly — the same "keys are intentionally
 * restricted to stable ASCII tokens" rationale [ClassifiedData.of] already
 * documented, generalized into one tested primitive instead of duplicated
 * per call site.
 *
 * @param value the candidate token.
 * @param maxLength the inclusive maximum length. Must be positive.
 * @param isAllowedCharacter predicate selecting which characters [value]
 *   may contain. Different token families legitimately need different
 *   allowed character sets — for example, hierarchical operational tokens
 *   allow `:` and `/` for path-like structure; attribute keys do not. This
 *   function fixes the shared bounds-and-predicate validation shape, not a
 *   single character set.
 * @return `true` when [value] is non-blank, at most [maxLength] characters,
 *   and every character satisfies [isAllowedCharacter]; `false` otherwise.
 * @throws IllegalArgumentException if [maxLength] is not positive.
 */
public fun isBoundedToken(
    value: String,
    maxLength: Int,
    isAllowedCharacter: (Char) -> Boolean,
): Boolean {
    require(maxLength > 0) { "maxLength must be positive, but was $maxLength." }
    return value.length in 1..maxLength && value.all(isAllowedCharacter)
}
