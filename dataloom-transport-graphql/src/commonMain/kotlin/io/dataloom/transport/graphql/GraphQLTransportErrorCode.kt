package io.dataloom.transport.graphql

import io.dataloom.api.error.ErrorCode

/**
 * Pre-defined [io.dataloom.api.error.ErrorCode] constants for failures
 * produced by [ApolloGraphQLTransportProvider].
 *
 * These codes are stable identifiers. Applications may use them to
 * distinguish failure categories in retry logic, logging, or monitoring.
 */
public object GraphQLTransportErrorCode {
    /**
     * A network-level failure prevented the GraphQL operation from reaching
     * the server or receiving a response (e.g. DNS resolution failure, TCP
     * connection refused, TLS handshake failure, request timeout).
     *
     * This error category is generally [io.dataloom.api.error.Recoverability.RECOVERABLE]
     * with transient retry.
     */
    public val NETWORK_FAILURE: ErrorCode = ErrorCode("GRAPHQL_TRANSPORT_NETWORK_FAILURE")

    /**
     * The GraphQL server returned an HTTP error status (4xx or 5xx) rather
     * than a well-formed GraphQL response.
     *
     * Recoverability depends on the HTTP status code; 5xx errors are
     * generally retryable while 4xx errors (except 429) are typically not.
     */
    public val HTTP_ERROR: ErrorCode = ErrorCode("GRAPHQL_TRANSPORT_HTTP_ERROR")

    /**
     * The GraphQL response contained one or more entries in the `errors[]`
     * array, indicating a GraphQL-level failure (resolver error, validation
     * error, or application-level business rule rejection).
     *
     * This is classified under [io.dataloom.api.error.ErrorCategory.PROVIDER]
     * because the failure originates from the application's GraphQL provider
     * (server), not from the network transport itself. Recoverability depends
     * on the semantic content of the GraphQL error; this provider conservatively
     * classifies it as [io.dataloom.api.error.Recoverability.UNKNOWN].
     */
    public val GRAPHQL_ERROR_RESPONSE: ErrorCode =
        ErrorCode("GRAPHQL_TRANSPORT_GRAPHQL_ERROR_RESPONSE")

    /**
     * The GraphQL response completed without a transport error or GraphQL
     * `errors[]` but returned `null` data where non-null data was required
     * to produce a valid DataLoom result.
     *
     * This indicates a schema contract violation or an unexpected server
     * behaviour and is classified as
     * [io.dataloom.api.error.Recoverability.NON_RECOVERABLE].
     */
    public val NULL_DATA: ErrorCode = ErrorCode("GRAPHQL_TRANSPORT_NULL_DATA")
}
