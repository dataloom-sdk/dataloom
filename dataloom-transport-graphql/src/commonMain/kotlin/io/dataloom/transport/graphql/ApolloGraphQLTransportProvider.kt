package io.dataloom.transport.graphql

import com.apollographql.apollo.ApolloClient
import com.apollographql.apollo.api.ApolloResponse
import com.apollographql.apollo.api.Mutation
import com.apollographql.apollo.api.Query
import com.apollographql.apollo.exception.ApolloException
import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.api.synchronization.ChangeSetAcknowledgement
import io.dataloom.api.transport.PullChangesRequest
import io.dataloom.api.transport.PullChangesResult
import io.dataloom.api.transport.PushChangesRequest
import io.dataloom.api.transport.TransportProvider
import kotlinx.coroutines.CancellationException

/**
 * Reference [TransportProvider] implementation backed by Apollo Kotlin.
 *
 * This abstract class provides the framework contract — coroutine
 * cancellation propagation, [ApolloException] interception, and response
 * error mapping — while delegating the operation-specific logic to
 * subclasses. Applications that own a specific GraphQL schema extend this
 * class, supply their own generated Apollo operations, and adapt response
 * data to DataLoom types using the protected helper methods.
 *
 * ## Design
 *
 * This is a **reference implementation** intended as a starting point.
 * Applications are expected to fork or subclass it to match their schema
 * and business requirements. It is not the only supported way to integrate
 * DataLoom with a GraphQL backend.
 *
 * ## Platform parity
 *
 * Apollo Kotlin 4.x supports JVM/Android, iosArm64, iosSimulatorArm64, and
 * iosX64. This module is therefore available on all three mandated V1 consumer
 * paths: native Android, KMP targeting Android, and KMP targeting iOS.
 *
 * ## Cancellation
 *
 * [pushChanges] and [pullChanges] always rethrow [CancellationException]
 * before any other error mapping so that structured concurrency is preserved.
 *
 * ## Security
 *
 * No credential, token, or header value may appear in logs, `toString()`,
 * or any [GraphQLTransportError] produced by this provider. Configure
 * authentication on the [ApolloClient] using Apollo's interceptor API; do
 * not pass secrets through [PushChangesRequest] or [PullChangesRequest].
 *
 * ## Schema and operations
 *
 * This module is intentionally schema-agnostic. The actual GraphQL schema,
 * generated operation types, and response adapters are owned by the
 * application. See [executePush] and [executePull] for the extension points,
 * and [adaptMutationResponse] / [adaptQueryResponse] for the response-mapping
 * helpers that subclasses should use when translating Apollo responses to
 * DataLoom results.
 *
 * @see GraphQLTransportError
 * @see GraphQLTransportErrorCode
 */
public abstract class ApolloGraphQLTransportProvider : TransportProvider {

    /**
     * Configured Apollo client used to execute GraphQL operations.
     *
     * The client must be set up by the application with the correct server
     * URL, HTTP engine, and any authentication interceptors required by the
     * backend. Authentication headers and tokens must not appear in logs or
     * `toString()` output produced by either the client or this provider.
     *
     * For testability, supply a client built with
     * `ApolloClient.Builder().networkTransport(QueueTestNetworkTransport())`.
     */
    protected abstract val apolloClient: ApolloClient

    // -------------------------------------------------------------------------
    // TransportProvider — push
    // -------------------------------------------------------------------------

    /**
     * Pushes outbound synchronization changes to the GraphQL backend.
     *
     * Delegates to [executePush] and:
     * - rethrows [CancellationException] to preserve structured concurrency;
     * - maps any [ApolloException] thrown by [executePush] to a canonical
     *   [GraphQLTransportError] via [ApolloErrorMapper].
     *
     * [executePush] is responsible for response-level error mapping using
     * [adaptMutationResponse].
     *
     * @param request immutable push request containing the synchronization
     *   request and outbound change set.
     * @return [ProviderOperationResult.Success] with a
     *   [ChangeSetAcknowledgement] on success, or
     *   [ProviderOperationResult.Failure] with a [GraphQLTransportError].
     */
    final override suspend fun pushChanges(
        request: PushChangesRequest,
    ): ProviderOperationResult<ChangeSetAcknowledgement> {
        return try {
            executePush(request)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApolloException) {
            ProviderOperationResult.Failure(ApolloErrorMapper.fromApolloException(e))
        }
    }

    /**
     * Pulls inbound synchronization changes from the GraphQL backend.
     *
     * Delegates to [executePull] and:
     * - rethrows [CancellationException] to preserve structured concurrency;
     * - maps any [ApolloException] thrown by [executePull] to a canonical
     *   [GraphQLTransportError] via [ApolloErrorMapper].
     *
     * [executePull] is responsible for response-level error mapping using
     * [adaptQueryResponse].
     *
     * @param request immutable pull request containing the synchronization
     *   request, optional entity-type restrictions, optional batch hint, and
     *   optional prior checkpoint.
     * @return [ProviderOperationResult.Success] with a [PullChangesResult] on
     *   success, or [ProviderOperationResult.Failure] with a
     *   [GraphQLTransportError].
     */
    final override suspend fun pullChanges(
        request: PullChangesRequest,
    ): ProviderOperationResult<PullChangesResult> {
        return try {
            executePull(request)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApolloException) {
            ProviderOperationResult.Failure(ApolloErrorMapper.fromApolloException(e))
        }
    }

    // -------------------------------------------------------------------------
    // Extension points — subclass implements
    // -------------------------------------------------------------------------

    /**
     * Executes the GraphQL mutation that pushes outbound changes.
     *
     * Implementations should:
     * 1. Build the appropriate application-specific Apollo mutation from
     *    [request].
     * 2. Execute it: `apolloClient.mutation(myMutation).execute()`.
     * 3. Map the [ApolloResponse] to a [ProviderOperationResult] using
     *    [adaptMutationResponse].
     *
     * Any [ApolloException] thrown here is caught by [pushChanges] and mapped
     * to a canonical [GraphQLTransportError]. Do not catch [ApolloException]
     * in this method unless your implementation requires specialized handling.
     * Never catch [CancellationException].
     *
     * ## Example
     *
     * ```kotlin
     * override suspend fun executePush(
     *     request: PushChangesRequest,
     * ): ProviderOperationResult<ChangeSetAcknowledgement> {
     *     val mutation = PushChangesMutation(
     *         changeSetId = request.changeSet.id.value,
     *         events = request.changeSet.events.map { it.toInput() },
     *     )
     *     val response = apolloClient.mutation(mutation).execute()
     *     return adaptMutationResponse(response) { data ->
     *         data.push.toChangeSetAcknowledgement(request.changeSet.id)
     *     }
     * }
     * ```
     *
     * @param request immutable push request.
     * @return [ProviderOperationResult] produced from the Apollo response.
     */
    protected abstract suspend fun executePush(
        request: PushChangesRequest,
    ): ProviderOperationResult<ChangeSetAcknowledgement>

    /**
     * Executes the GraphQL query that pulls inbound changes.
     *
     * Implementations should:
     * 1. Build the appropriate application-specific Apollo query from
     *    [request], including an optional cursor from
     *    `request.checkpoint?.token?.value`.
     * 2. Execute it: `apolloClient.query(myQuery).execute()`.
     * 3. Map the [ApolloResponse] to a [ProviderOperationResult] using
     *    [adaptQueryResponse].
     *
     * Any [ApolloException] thrown here is caught by [pullChanges] and mapped
     * to a canonical [GraphQLTransportError]. Do not catch [ApolloException]
     * in this method unless your implementation requires specialized handling.
     * Never catch [CancellationException].
     *
     * ## Checkpoint / cursor mapping
     *
     * If the application's GraphQL schema uses cursor-based pagination,
     * supply `request.checkpoint?.token?.value` as the cursor argument. The
     * returned next-page cursor should be wrapped in a
     * [io.dataloom.api.synchronization.SynchronizationCheckpoint] and passed
     * as `nextCheckpoint` in [PullChangesResult.Changes] or
     * [PullChangesResult.NoChanges].
     *
     * [io.dataloom.api.transport.PullChangesRequest.checkpoint] is treated as
     * opaque by this provider — the token value is passed to the application's
     * schema without interpretation.
     *
     * ## Example
     *
     * ```kotlin
     * override suspend fun executePull(
     *     request: PullChangesRequest,
     * ): ProviderOperationResult<PullChangesResult> {
     *     val cursor = request.checkpoint?.token?.value
     *     val query = PullChangesQuery(cursor = cursor, limit = request.maxEvents)
     *     val response = apolloClient.query(query).execute()
     *     return adaptQueryResponse(response) { data ->
     *         val page = data.changes
     *         if (page.edges.isEmpty()) {
     *             PullChangesResult.NoChanges(
     *                 nextCheckpoint = page.pageInfo.endCursor?.toCheckpoint(),
     *             )
     *         } else {
     *             PullChangesResult.Changes(
     *                 changeSet = page.edges.map { it.toChangeEvent() }.toChangeSet(),
     *                 hasMore = page.pageInfo.hasNextPage,
     *                 nextCheckpoint = page.pageInfo.endCursor?.toCheckpoint(),
     *             )
     *         }
     *     }
     * }
     * ```
     *
     * @param request immutable pull request.
     * @return [ProviderOperationResult] produced from the Apollo response.
     */
    protected abstract suspend fun executePull(
        request: PullChangesRequest,
    ): ProviderOperationResult<PullChangesResult>

    // -------------------------------------------------------------------------
    // Response-mapping helpers — subclasses call from executePush / executePull
    // -------------------------------------------------------------------------

    /**
     * Maps an [ApolloResponse] from a GraphQL mutation to a
     * [ProviderOperationResult].
     *
     * Resolution order:
     * 1. [ApolloResponse.exception] is non-null → [ProviderOperationResult.Failure]
     *    with a [GraphQLTransportError] mapped from the exception.
     * 2. [ApolloResponse.hasErrors] → [ProviderOperationResult.Failure] with a
     *    [GraphQLTransportError] summarising the GraphQL `errors[]`.
     * 3. [ApolloResponse.data] is non-null → [ProviderOperationResult.Success]
     *    with the value produced by [adapt].
     * 4. All three absent → [ProviderOperationResult.Failure] with a
     *    NULL_DATA [GraphQLTransportError].
     *
     * If both data and errors are present (partial-data response), errors
     * take precedence to maintain a fail-safe contract.
     *
     * @param D mutation data type produced by the Apollo code generator.
     * @param response Apollo response from executing the mutation.
     * @param adapt maps the non-null response data to a
     *   [ChangeSetAcknowledgement].
     * @return [ProviderOperationResult] for the push operation.
     */
    protected fun <D : Mutation.Data> adaptMutationResponse(
        response: ApolloResponse<D>,
        adapt: (D) -> ChangeSetAcknowledgement,
    ): ProviderOperationResult<ChangeSetAcknowledgement> {
        response.exception?.let { ex ->
            return ProviderOperationResult.Failure(ApolloErrorMapper.fromApolloException(ex))
        }
        if (response.hasErrors()) {
            return ProviderOperationResult.Failure(
                ApolloErrorMapper.fromGraphQLErrors(response.errors!!),
            )
        }
        val data = response.data
            ?: return ProviderOperationResult.Failure(ApolloErrorMapper.nullDataError())
        return ProviderOperationResult.Success(adapt(data))
    }

    /**
     * Maps an [ApolloResponse] from a GraphQL query to a
     * [ProviderOperationResult].
     *
     * Resolution order mirrors [adaptMutationResponse]:
     * 1. [ApolloResponse.exception] is non-null → failure.
     * 2. [ApolloResponse.hasErrors] → failure with GraphQL error summary.
     * 3. [ApolloResponse.data] is non-null → success via [adapt].
     * 4. All three absent → NULL_DATA failure.
     *
     * @param D query data type produced by the Apollo code generator.
     * @param response Apollo response from executing the query.
     * @param adapt maps the non-null response data to a [PullChangesResult].
     * @return [ProviderOperationResult] for the pull operation.
     */
    protected fun <D : Query.Data> adaptQueryResponse(
        response: ApolloResponse<D>,
        adapt: (D) -> PullChangesResult,
    ): ProviderOperationResult<PullChangesResult> {
        response.exception?.let { ex ->
            return ProviderOperationResult.Failure(ApolloErrorMapper.fromApolloException(ex))
        }
        if (response.hasErrors()) {
            return ProviderOperationResult.Failure(
                ApolloErrorMapper.fromGraphQLErrors(response.errors!!),
            )
        }
        val data = response.data
            ?: return ProviderOperationResult.Failure(ApolloErrorMapper.nullDataError())
        return ProviderOperationResult.Success(adapt(data))
    }
}
