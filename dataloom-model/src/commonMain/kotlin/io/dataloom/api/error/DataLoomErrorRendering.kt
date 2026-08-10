package io.dataloom.api.error

/**
 * Safe, bounded diagnostic rendering of a [DataLoomError], suitable as the
 * default `toString()` for every implementation.
 *
 * ## Why this exists
 *
 * [DataLoomError.message] is documented to exclude credentials, tokens,
 * keys, and other sensitive values, and [DataLoomError.cause] is documented
 * as "for diagnostics only" with the same expectation. Neither constraint is
 * enforced by the type system, and a naive `data class` `toString()` would
 * render [DataLoomError.cause] via its own `toString()`/`message` — which
 * belongs to a third-party or platform exception this codebase does not
 * control and cannot assume is safe to print. A wrapped HTTP client
 * exception, for example, commonly embeds the request URL — which may carry
 * a token in a query parameter — directly in its message.
 *
 * This function renders [DataLoomError.cause] as its exception *type name*
 * only, never its message or `toString()`, so that using it as the default
 * `toString()` implementation cannot leak wrapped-exception content by
 * accident. Call sites that genuinely need the full cause chain (a debugger,
 * an explicit diagnostic export path) can still read
 * [DataLoomError.cause] directly — this function only bounds what happens
 * by *default*.
 *
 * ## Usage
 *
 * ```kotlin
 * public data class MyProviderError(
 *     override val code: ErrorCode,
 *     override val category: ErrorCategory,
 *     override val severity: ErrorSeverity,
 *     override val recoverability: Recoverability,
 *     override val message: String,
 *     override val cause: Throwable? = null,
 * ) : DataLoomError {
 *     override fun toString(): String = safeDiagnosticString()
 * }
 * ```
 *
 * This does not sanitize [DataLoomError.message] itself — that remains each
 * implementation's responsibility per the [DataLoomError.message] contract.
 */
public fun DataLoomError.safeDiagnosticString(): String {
    val typeName = this::class.simpleName ?: "DataLoomError"
    val causeTypeName = cause?.let { it::class.simpleName ?: "Throwable" }
    return "$typeName(code=$code, category=$category, severity=$severity, " +
        "recoverability=$recoverability, message=$message, cause=$causeTypeName)"
}
