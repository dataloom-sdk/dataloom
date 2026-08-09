package io.dataloom.transport.graphql

import com.apollographql.apollo.api.Error
import com.apollographql.apollo.exception.ApolloException
import com.apollographql.apollo.exception.ApolloHttpException
import com.apollographql.apollo.exception.ApolloNetworkException
import io.dataloom.api.error.ErrorCategory
import io.dataloom.api.error.ErrorSeverity
import io.dataloom.api.error.Recoverability

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
     * The exception [cause] is preserved for diagnostics. No sensitive fields
     * are surfaced in [GraphQLTransportError.message].
     */
    fun fromApolloException(exception: ApolloException): GraphQLTransportError =
        when (exception) {
            is ApolloNetworkException -> GraphQLTransportError(
                code = GraphQLTransportErrorCode.NETWORK_FAILURE,
                category = ErrorCategory.NETWORK,
                severity = ErrorSeverity.ERROR,
                recoverability = Recoverability.RECOVERABLE,
                message = "GraphQL transport network failure: ${safeMessage(exception)}",
                cause = exception,
            )

            is ApolloHttpException -> {
                val status = exception.statusCode
                val recoverability = when {
                    status == 429 -> Recoverability.RECOVERABLE
                    status in 500..599 -> Recoverability.RECOVERABLE
                    else -> Recoverability.NON_RECOVERABLE
                }
                GraphQLTransportError(
                    code = GraphQLTransportErrorCode.HTTP_ERROR,
                    category = ErrorCategory.NETWORK,
                    severity = ErrorSeverity.ERROR,
                    recoverability = recoverability,
                    message = "GraphQL transport HTTP error: status=$status",
                    cause = exception,
                )
            }

            else -> GraphQLTransportError(
                code = GraphQLTransportErrorCode.NETWORK_FAILURE,
                category = ErrorCategory.NETWORK,
                severity = ErrorSeverity.ERROR,
                recoverability = Recoverability.UNKNOWN,
                message = "GraphQL transport failure: ${safeMessage(exception)}",
                cause = exception,
            )
        }

    /**
     * Maps a non-empty list of GraphQL response-level [Error] values to a
     * [GraphQLTransportError].
     *
     * Only the message of the first error is included in the diagnostic
     * summary. Full error details are not forwarded to prevent accidental
     * leakage of sensitive server-side context.
     *
     * @param errors non-empty list of GraphQL errors from the response.
     */
    fun fromGraphQLErrors(errors: List<Error>): GraphQLTransportError {
        val count = errors.size
        val firstMessage = errors.firstOrNull()?.message?.take(200) ?: "GraphQL error"
        val summary = if (count == 1) {
            "GraphQL error response: $firstMessage"
        } else {
            "GraphQL error response ($count errors): $firstMessage"
        }
        return GraphQLTransportError(
            code = GraphQLTransportErrorCode.GRAPHQL_ERROR_RESPONSE,
            category = ErrorCategory.NETWORK,
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

    private fun safeMessage(exception: Throwable): String =
        exception.message?.take(200) ?: exception::class.simpleName ?: "unknown"
}
