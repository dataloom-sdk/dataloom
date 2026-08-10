package io.dataloom.transport.graphql

import io.dataloom.api.error.DataLoomError
import io.dataloom.api.error.ErrorCategory
import io.dataloom.api.error.ErrorCode
import io.dataloom.api.error.ErrorSeverity
import io.dataloom.api.error.Recoverability
import io.dataloom.api.error.safeDiagnosticString

/**
 * Canonical DataLoom error produced by the Apollo GraphQL transport provider.
 *
 * [GraphQLTransportError] is the only error type that crosses the public
 * surface of [ApolloGraphQLTransportProvider]. Raw Apollo exception types and
 * GraphQL response-level error payloads are mapped to this type before being
 * returned to callers.
 *
 * [message] must not contain authentication tokens, authorization headers,
 * credentials, encryption keys, personal data, or any other sensitive value.
 * The [cause] chain is included for diagnostic purposes only; sensitive fields
 * from nested exceptions must be stripped before surfacing.
 *
 * ## Error codes
 *
 * Use the pre-defined constants in [GraphQLTransportErrorCode] when selecting
 * a [code] for a well-known failure category.
 *
 * ## Equality
 *
 * Equality compares all properties by value.
 *
 * @param code stable machine-readable code identifying the failure.
 * @param category technology-neutral category for this failure.
 * @param severity canonical impact severity.
 * @param recoverability canonical recoverability classification.
 * @param message sanitized human-readable diagnostic summary. Must not
 *   contain sensitive data.
 * @param cause optional underlying cause for diagnostics. Must not expose
 *   sensitive fields through its `toString()`.
 */
public data class GraphQLTransportError(
    override val code: ErrorCode,
    override val category: ErrorCategory,
    override val severity: ErrorSeverity,
    override val recoverability: Recoverability,
    override val message: String,
    override val cause: Throwable? = null,
) : DataLoomError {
    override fun toString(): String = safeDiagnosticString()
}
