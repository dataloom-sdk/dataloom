package io.dataloom.transport.graphql

import com.apollographql.apollo.api.Error
import com.apollographql.apollo.api.http.HttpHeader
import com.apollographql.apollo.exception.ApolloException
import com.apollographql.apollo.exception.ApolloHttpException
import com.apollographql.apollo.exception.ApolloNetworkException
import io.dataloom.api.error.DataLoomError
import io.dataloom.api.error.ErrorCategory
import io.dataloom.api.error.ErrorSeverity
import io.dataloom.api.error.Recoverability
import io.dataloom.api.error.RetryAfterParser
import io.dataloom.api.error.RetryDelayHint
import io.dataloom.api.time.DataLoomClock

/**
 * Internal error-mapping utilities for the Apollo GraphQL transport provider.
 *
 * These functions translate Apollo Kotlin exception types and GraphQL
 * response-level errors into canonical [GraphQLTransportError] values.
 * No authentication headers, credential values, or sensitive response body
 * content are included in the produced error messages.
 */
internal object ApolloErrorMapper {

    /**
     * Maps an [ApolloException] to a [GraphQLTransportError].
     *
     * - [ApolloNetworkException] → NETWORK_FAILURE, NETWORK category, RECOVERABLE.
     * - [ApolloHttpException] → HTTP_ERROR, NETWORK category; 5xx and 429 are
     *   RECOVERABLE, all other 4xx are NON_RECOVERABLE.
     * - All other [ApolloException] subtypes → NETWORK_FAILURE, NETWORK category,
     *   UNKNOWN recoverability.
     *
     * The exception [cause] is preserved for diagnostics. [GraphQLTransportError.message]
     * never includes [Throwable.message] content — an underlying HTTP client or
     * platform exception's message is not something this codebase controls or
     * can assume is free of URLs, tokens, or other sensitive content (see
     * [safeMessage]), so only the exception's type name is surfaced.
     *
     * A 429 or 503 [ApolloHttpException] whose [ApolloHttpException.headers] carry a valid
     * `Retry-After` value produces a [GraphQLRetryableTransportError] instead, normalized by
     * [RetryAfterParser] exactly as the Ktor and Retrofit transports do. [clock] converts an
     * HTTP-date value into a delay; when `null`, only the delay-seconds form is honored.
     */
    fun fromApolloException(
        exception: ApolloException,
        clock: DataLoomClock? = null,
    ): DataLoomError =
        when (exception) {
            is ApolloNetworkException -> GraphQLTransportError(
                code = GraphQLTransportErrorCode.NETWORK_FAILURE,
                category = ErrorCategory.NETWORK,
                severity = ErrorSeverity.ERROR,
                recoverability = Recoverability.RECOVERABLE,
                message = "GraphQL transport network failure: ${safeMessage(exception)}",
                cause = exception,
            )

            is ApolloHttpException -> fromHttpException(exception, clock)

            else -> GraphQLTransportError(
                code = GraphQLTransportErrorCode.NETWORK_FAILURE,
                category = ErrorCategory.NETWORK,
                severity = ErrorSeverity.ERROR,
                recoverability = Recoverability.UNKNOWN,
                message = "GraphQL transport failure: ${safeMessage(exception)}",
                cause = exception,
            )
        }

    private fun fromHttpException(
        exception: ApolloHttpException,
        clock: DataLoomClock?,
    ): DataLoomError {
        val status = exception.statusCode
        val recoverability = when {
            status == 429 -> Recoverability.RECOVERABLE
            status in 500..599 -> Recoverability.RECOVERABLE
            else -> Recoverability.NON_RECOVERABLE
        }
        val message = "GraphQL transport HTTP error: status=$status"
        // Retry-After is only meaningful on 429 and 503 (RFC 9110).
        val retryDelayHint: RetryDelayHint? =
            if (status == 429 || status == 503) parseRetryDelayHint(exception.headers, clock) else null
        if (retryDelayHint == null) {
            return GraphQLTransportError(
                code = GraphQLTransportErrorCode.HTTP_ERROR,
                category = ErrorCategory.NETWORK,
                severity = ErrorSeverity.ERROR,
                recoverability = recoverability,
                message = message,
                cause = exception,
            )
        }
        return GraphQLRetryableTransportError(
            code = GraphQLTransportErrorCode.HTTP_ERROR,
            category = ErrorCategory.NETWORK,
            severity = ErrorSeverity.ERROR,
            recoverability = recoverability,
            message = message,
            cause = exception,
            retryDelayHint = retryDelayHint,
        )
    }

    /**
     * [ApolloHttpException.headers] is the raw HTTP response header list Apollo's HTTP engine
     * captured, so — as with the Ktor and Retrofit adapters — this is transport data the
     * adapter can see directly; no synthetic header is invented.
     */
    private fun parseRetryDelayHint(headers: List<HttpHeader>, clock: DataLoomClock?): RetryDelayHint? {
        val retryAfterValues: List<String> = headers
            .filter { header -> header.name.equals("Retry-After", ignoreCase = true) }
            .map { header -> header.value }
        return RetryAfterParser.parse(retryAfterValues, clock)
    }

    /**
     * Maps a non-empty list of GraphQL response-level [Error] values to a
     * [GraphQLTransportError].
     *
     * The error is classified as [io.dataloom.api.error.ErrorCategory.PROVIDER]
     * because the failure originates from the application's GraphQL provider
     * (server) rather than the network transport layer. No GraphQL error
     * message content is forwarded — a server-side error message is
     * application-defined text this codebase does not control and cannot
     * assume is free of business data or personal data, so [message] carries
     * only the error count, never response-body content.
     *
     * @param errors non-empty list of GraphQL errors from the response.
     */
    fun fromGraphQLErrors(errors: List<Error>): GraphQLTransportError {
        val count = errors.size
        val summary = if (count == 1) {
            "GraphQL error response (1 error)"
        } else {
            "GraphQL error response ($count errors)"
        }
        return GraphQLTransportError(
            code = GraphQLTransportErrorCode.GRAPHQL_ERROR_RESPONSE,
            category = ErrorCategory.PROVIDER,
            severity = ErrorSeverity.ERROR,
            recoverability = Recoverability.UNKNOWN,
            message = summary,
        )
    }

    /**
     * Returns a null-data error for responses that carried neither transport
     * errors, GraphQL errors[], nor usable data.
     */
    fun nullDataError(): GraphQLTransportError = GraphQLTransportError(
        code = GraphQLTransportErrorCode.NULL_DATA,
        category = ErrorCategory.NETWORK,
        severity = ErrorSeverity.ERROR,
        recoverability = Recoverability.NON_RECOVERABLE,
        message = "GraphQL response contained null data without errors",
    )

    /**
     * Returns a diagnostic label for [exception] that never includes
     * [Throwable.message] content.
     *
     * `.take(200)`-style truncation is not sanitization — a client/platform
     * exception's message routinely embeds the request URL (which may carry
     * a token in a query parameter), a hostname, or other environment detail
     * this codebase does not control. Only the exception's type name is
     * safe to assume is free of that content, matching the same
     * type-name-only pattern [io.dataloom.api.error.safeDiagnosticString]
     * already applies to [io.dataloom.api.error.DataLoomError.cause].
     */
    private fun safeMessage(exception: Throwable): String =
        exception::class.simpleName ?: "unknown"
}
