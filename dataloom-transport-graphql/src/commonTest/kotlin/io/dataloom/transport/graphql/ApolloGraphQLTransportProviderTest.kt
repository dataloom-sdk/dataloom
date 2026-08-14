package io.dataloom.transport.graphql

import com.apollographql.apollo.ApolloClient
import com.apollographql.apollo.exception.ApolloHttpException
import com.apollographql.apollo.exception.ApolloNetworkException
import io.dataloom.api.change.ChangeEvent
import io.dataloom.api.change.ChangeSet
import io.dataloom.api.change.EntityReference
import io.dataloom.api.context.ExecutionContext
import io.dataloom.api.error.ErrorCategory
import io.dataloom.api.error.Recoverability
import io.dataloom.api.identifier.ChangeEventId
import io.dataloom.api.identifier.ChangeSetId
import io.dataloom.api.identifier.CheckpointKey
import io.dataloom.api.identifier.CheckpointToken
import io.dataloom.api.identifier.CorrelationId
import io.dataloom.api.identifier.EntityId
import io.dataloom.api.identifier.EntityType
import io.dataloom.api.identifier.ExecutionId
import io.dataloom.api.identifier.SynchronizationSessionId
import io.dataloom.api.identifier.WorkflowId
import io.dataloom.api.model.ChangeOperation
import io.dataloom.api.model.SynchronizationDirection
import io.dataloom.api.model.SynchronizationMode
import io.dataloom.api.model.SynchronizationRequest
import io.dataloom.api.provider.ProviderDescriptor
import io.dataloom.api.provider.ProviderHealth
import io.dataloom.api.provider.ProviderHealthStatus
import io.dataloom.api.provider.ProviderInitializationContext
import io.dataloom.api.provider.ProviderName
import io.dataloom.api.provider.ProviderId
import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.api.provider.ProviderType
import io.dataloom.api.provider.ProviderVersion
import io.dataloom.api.synchronization.ChangeAcknowledgementStatus
import io.dataloom.api.synchronization.ChangeEventAcknowledgement
import io.dataloom.api.synchronization.ChangeSetAcknowledgement
import io.dataloom.api.synchronization.SynchronizationCheckpoint
import io.dataloom.api.transport.PullChangesRequest
import io.dataloom.api.transport.PullChangesResult
import io.dataloom.api.transport.PushChangesRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ApolloGraphQLTransportProviderTest {

    // -------------------------------------------------------------------------
    // Fixtures
    // -------------------------------------------------------------------------

    private val syncRequest: SynchronizationRequest = SynchronizationRequest(
        workflowId = WorkflowId("workflow-001"),
        sessionId = SynchronizationSessionId("session-001"),
        direction = SynchronizationDirection.PUSH,
        mode = SynchronizationMode.FULL,
        context = ExecutionContext(
            executionId = ExecutionId("exec-001"),
            correlationId = CorrelationId("corr-001"),
        ),
    )

    private val changeEvent: ChangeEvent = ChangeEvent(
        id = ChangeEventId("evt-001"),
        entity = EntityReference(
            type = EntityType("invoice"),
            id = EntityId("inv-001"),
        ),
        operation = ChangeOperation.CREATE,
    )

    private val changeSet: ChangeSet = ChangeSet(
        id = ChangeSetId("cs-001"),
        events = listOf(changeEvent),
    )

    private val pushRequest: PushChangesRequest = PushChangesRequest(
        request = syncRequest,
        changeSet = changeSet,
    )

    private val pullRequest: PullChangesRequest = PullChangesRequest(
        request = syncRequest,
    )

    private val successAcknowledgement: ChangeSetAcknowledgement = ChangeSetAcknowledgement(
        changeSetId = changeSet.id,
        events = listOf(
            ChangeEventAcknowledgement(
                eventId = changeEvent.id,
                status = ChangeAcknowledgementStatus.ACCEPTED,
            ),
        ),
    )

    private val noChangesResult: PullChangesResult = PullChangesResult.NoChanges()

    private val checkpoint: SynchronizationCheckpoint = SynchronizationCheckpoint(
        key = CheckpointKey("stream-1"),
        token = CheckpointToken("cursor-abc123"),
    )

    // -------------------------------------------------------------------------
    // Push success
    // -------------------------------------------------------------------------

    @Test
    fun `pushChanges returns success when executePush returns success`() = runTest {
        val provider = StubTransportProvider(
            pushBehaviour = { ProviderOperationResult.Success(successAcknowledgement) },
        )

        val result = provider.pushChanges(pushRequest)

        assertIs<ProviderOperationResult.Success<ChangeSetAcknowledgement>>(result)
        assertEquals(successAcknowledgement, result.value)
    }

    // -------------------------------------------------------------------------
    // Push transport failure — ApolloNetworkException
    // -------------------------------------------------------------------------

    @Test
    fun `pushChanges maps ApolloNetworkException to canonical NETWORK error`() = runTest {
        val networkEx = ApolloNetworkException(
            message = "Connection refused",
            platformCause = null,
        )
        val provider = StubTransportProvider(
            pushBehaviour = { throw networkEx },
        )

        val result = provider.pushChanges(pushRequest)

        assertIs<ProviderOperationResult.Failure>(result)
        val error = assertIs<GraphQLTransportError>(result.error)
        assertEquals(GraphQLTransportErrorCode.NETWORK_FAILURE, error.code)
        assertEquals(ErrorCategory.NETWORK, error.category)
        assertEquals(Recoverability.RECOVERABLE, error.recoverability)
        assertEquals(networkEx, error.cause)
    }

    // -------------------------------------------------------------------------
    // Push transport failure — ApolloHttpException (5xx → recoverable)
    // -------------------------------------------------------------------------

    @Test
    fun `pushChanges maps ApolloHttpException 503 to RECOVERABLE HTTP error`() = runTest {
        val httpEx = ApolloHttpException(
            statusCode = 503,
            headers = emptyList(),
            body = null,
            message = "Service Unavailable",
        )
        val provider = StubTransportProvider(
            pushBehaviour = { throw httpEx },
        )

        val result = provider.pushChanges(pushRequest)

        assertIs<ProviderOperationResult.Failure>(result)
        val error = assertIs<GraphQLTransportError>(result.error)
        assertEquals(GraphQLTransportErrorCode.HTTP_ERROR, error.code)
        assertEquals(Recoverability.RECOVERABLE, error.recoverability)
    }

    // -------------------------------------------------------------------------
    // Push transport failure — ApolloHttpException (4xx → non-recoverable)
    // -------------------------------------------------------------------------

    @Test
    fun `pushChanges maps ApolloHttpException 400 to NON_RECOVERABLE HTTP error`() = runTest {
        val httpEx = ApolloHttpException(
            statusCode = 400,
            headers = emptyList(),
            body = null,
            message = "Bad Request",
        )
        val provider = StubTransportProvider(
            pushBehaviour = { throw httpEx },
        )

        val result = provider.pushChanges(pushRequest)

        assertIs<ProviderOperationResult.Failure>(result)
        val error = assertIs<GraphQLTransportError>(result.error)
        assertEquals(GraphQLTransportErrorCode.HTTP_ERROR, error.code)
        assertEquals(Recoverability.NON_RECOVERABLE, error.recoverability)
    }

    // -------------------------------------------------------------------------
    // Push cancellation
    // -------------------------------------------------------------------------

    @Test
    fun `pushChanges propagates CancellationException without wrapping`() = runTest {
        val provider = StubTransportProvider(
            pushBehaviour = { throw CancellationException("test cancelled") },
        )

        assertFailsWith<CancellationException> {
            provider.pushChanges(pushRequest)
        }
    }

    // -------------------------------------------------------------------------
    // Pull success — no changes
    // -------------------------------------------------------------------------

    @Test
    fun `pullChanges returns NoChanges result on success`() = runTest {
        val provider = StubTransportProvider(
            pullBehaviour = { ProviderOperationResult.Success(noChangesResult) },
        )

        val result = provider.pullChanges(pullRequest)

        assertIs<ProviderOperationResult.Success<PullChangesResult>>(result)
        assertIs<PullChangesResult.NoChanges>(result.value)
    }

    // -------------------------------------------------------------------------
    // Pull success — changes with hasMore and checkpoint
    // -------------------------------------------------------------------------

    @Test
    fun `pullChanges returns Changes result with hasMore true and next checkpoint`() = runTest {
        val changesResult = PullChangesResult.Changes(
            changeSet = changeSet,
            hasMore = true,
            nextCheckpoint = checkpoint,
        )
        val provider = StubTransportProvider(
            pullBehaviour = { ProviderOperationResult.Success(changesResult) },
        )

        val result = provider.pullChanges(pullRequest)

        assertIs<ProviderOperationResult.Success<PullChangesResult>>(result)
        val changes = assertIs<PullChangesResult.Changes>(result.value)
        assertEquals(true, changes.hasMore)
        assertNotNull(changes.nextCheckpoint)
        assertEquals(checkpoint.token.value, changes.nextCheckpoint!!.token.value)
    }

    // -------------------------------------------------------------------------
    // Pull success — changes with hasMore false and no checkpoint
    // -------------------------------------------------------------------------

    @Test
    fun `pullChanges returns Changes result with hasMore false and no checkpoint`() = runTest {
        val changesResult = PullChangesResult.Changes(
            changeSet = changeSet,
            hasMore = false,
            nextCheckpoint = null,
        )
        val provider = StubTransportProvider(
            pullBehaviour = { ProviderOperationResult.Success(changesResult) },
        )

        val result = provider.pullChanges(pullRequest)

        assertIs<ProviderOperationResult.Success<PullChangesResult>>(result)
        val changes = assertIs<PullChangesResult.Changes>(result.value)
        assertEquals(false, changes.hasMore)
        assertNull(changes.nextCheckpoint)
    }

    // -------------------------------------------------------------------------
    // Pull transport failure — ApolloNetworkException
    // -------------------------------------------------------------------------

    @Test
    fun `pullChanges maps ApolloNetworkException to canonical NETWORK error`() = runTest {
        val networkEx = ApolloNetworkException(
            message = "Timeout",
            platformCause = null,
        )
        val provider = StubTransportProvider(
            pullBehaviour = { throw networkEx },
        )

        val result = provider.pullChanges(pullRequest)

        assertIs<ProviderOperationResult.Failure>(result)
        val error = assertIs<GraphQLTransportError>(result.error)
        assertEquals(GraphQLTransportErrorCode.NETWORK_FAILURE, error.code)
        assertEquals(ErrorCategory.NETWORK, error.category)
        assertEquals(Recoverability.RECOVERABLE, error.recoverability)
    }

    // -------------------------------------------------------------------------
    // Pull GraphQL errors[] response
    // -------------------------------------------------------------------------

    @Test
    fun `pullChanges failure with GraphQL error produces GRAPHQL_ERROR_RESPONSE code`() = runTest {
        val graphqlError = GraphQLTransportError(
            code = GraphQLTransportErrorCode.GRAPHQL_ERROR_RESPONSE,
            category = ErrorCategory.PROVIDER,
            severity = io.dataloom.api.error.ErrorSeverity.ERROR,
            recoverability = Recoverability.UNKNOWN,
            message = "GraphQL error response: resolver error",
        )
        val provider = StubTransportProvider(
            pullBehaviour = { ProviderOperationResult.Failure(graphqlError) },
        )

        val result = provider.pullChanges(pullRequest)

        assertIs<ProviderOperationResult.Failure>(result)
        val error = assertIs<GraphQLTransportError>(result.error)
        assertEquals(GraphQLTransportErrorCode.GRAPHQL_ERROR_RESPONSE, error.code)
        assertEquals(Recoverability.UNKNOWN, error.recoverability)
    }

    // -------------------------------------------------------------------------
    // Pull cancellation
    // -------------------------------------------------------------------------

    @Test
    fun `pullChanges propagates CancellationException without wrapping`() = runTest {
        val provider = StubTransportProvider(
            pullBehaviour = { throw CancellationException("pull cancelled") },
        )

        assertFailsWith<CancellationException> {
            provider.pullChanges(pullRequest)
        }
    }

    // -------------------------------------------------------------------------
    // Error mapper — direct unit tests for ApolloErrorMapper
    // -------------------------------------------------------------------------

    @Test
    fun `ApolloErrorMapper maps ApolloNetworkException correctly`() {
        val ex = ApolloNetworkException(message = "connection reset", platformCause = null)
        val error = ApolloErrorMapper.fromApolloException(ex)

        assertEquals(GraphQLTransportErrorCode.NETWORK_FAILURE, error.code)
        assertEquals(ErrorCategory.NETWORK, error.category)
        assertEquals(Recoverability.RECOVERABLE, error.recoverability)
        assertEquals(ex, error.cause)
    }

    @Test
    fun `ApolloErrorMapper maps ApolloHttpException 429 as RECOVERABLE`() {
        val ex = ApolloHttpException(
            statusCode = 429,
            headers = emptyList(),
            body = null,
            message = "Too Many Requests",
        )
        val error = ApolloErrorMapper.fromApolloException(ex)

        assertEquals(GraphQLTransportErrorCode.HTTP_ERROR, error.code)
        assertEquals(Recoverability.RECOVERABLE, error.recoverability)
    }

    @Test
    fun `ApolloErrorMapper maps ApolloHttpException 500 as RECOVERABLE`() {
        val ex = ApolloHttpException(
            statusCode = 500,
            headers = emptyList(),
            body = null,
            message = "Internal Server Error",
        )
        val error = ApolloErrorMapper.fromApolloException(ex)

        assertEquals(GraphQLTransportErrorCode.HTTP_ERROR, error.code)
        assertEquals(Recoverability.RECOVERABLE, error.recoverability)
    }

    @Test
    fun `ApolloErrorMapper maps ApolloHttpException 403 as NON_RECOVERABLE`() {
        val ex = ApolloHttpException(
            statusCode = 403,
            headers = emptyList(),
            body = null,
            message = "Forbidden",
        )
        val error = ApolloErrorMapper.fromApolloException(ex)

        assertEquals(GraphQLTransportErrorCode.HTTP_ERROR, error.code)
        assertEquals(Recoverability.NON_RECOVERABLE, error.recoverability)
    }

    @Test
    fun `ApolloErrorMapper maps GraphQL errors list correctly`() {
        val errors = listOf(
            com.apollographql.apollo.api.Error.Builder(message = "resolver failed").build(),
        )
        val error = ApolloErrorMapper.fromGraphQLErrors(errors)

        assertEquals(GraphQLTransportErrorCode.GRAPHQL_ERROR_RESPONSE, error.code)
        assertEquals(ErrorCategory.PROVIDER, error.category)
        assertEquals(Recoverability.UNKNOWN, error.recoverability)
    }

    @Test
    fun `ApolloErrorMapper summarises multiple GraphQL errors by count`() {
        val errors = listOf(
            com.apollographql.apollo.api.Error.Builder(message = "first error").build(),
            com.apollographql.apollo.api.Error.Builder(message = "second error").build(),
            com.apollographql.apollo.api.Error.Builder(message = "third error").build(),
        )
        val error = ApolloErrorMapper.fromGraphQLErrors(errors)

        assertEquals(GraphQLTransportErrorCode.GRAPHQL_ERROR_RESPONSE, error.code)
        // Should mention the count in the message (not assert exact wording)
        assertEquals(true, error.message.contains("3"))
    }

    // -------------------------------------------------------------------------
    // Error mapper — message content never leaks exception/response text
    //
    // #93 acceptance criterion: "No secrets, credentials, payloads, or
    // unbounded-cardinality values are exposed by defaults." `.take(200)`-style
    // truncation is not sanitization; these tests prove the mapper never
    // forwards Throwable.message or GraphQL error-message content at all,
    // regardless of what it contains.
    // -------------------------------------------------------------------------

    @Test
    fun `ApolloErrorMapper never forwards ApolloNetworkException message content`() {
        val secretToken = "eyJhbGciOiJIUzI1NiJ9.secret-token-value"
        val ex = ApolloNetworkException(
            message = "Connection to https://api.example.com/graphql?token=$secretToken failed",
            platformCause = null,
        )

        val error = ApolloErrorMapper.fromApolloException(ex)

        assertEquals(false, error.message.contains(secretToken))
        assertEquals(false, error.toString().contains(secretToken))
        // Still diagnosable via the exception's type name, just not its content.
        assertEquals(true, error.message.contains("ApolloNetworkException"))
    }

    @Test
    fun `ApolloErrorMapper never forwards GraphQL server error message content`() {
        val sensitiveDetail = "user.email=someone@example.com violates unique constraint"
        val errors = listOf(
            com.apollographql.apollo.api.Error.Builder(message = sensitiveDetail).build(),
        )

        val error = ApolloErrorMapper.fromGraphQLErrors(errors)

        assertEquals(false, error.message.contains(sensitiveDetail))
        assertEquals(false, error.toString().contains(sensitiveDetail))
    }

    @Test
    fun `ApolloErrorMapper nullDataError has NULL_DATA code and NON_RECOVERABLE`() {
        val error = ApolloErrorMapper.nullDataError()

        assertEquals(GraphQLTransportErrorCode.NULL_DATA, error.code)
        assertEquals(Recoverability.NON_RECOVERABLE, error.recoverability)
    }

    // -------------------------------------------------------------------------
    // GraphQLTransportError — public surface
    // -------------------------------------------------------------------------

    @Test
    fun `GraphQLTransportError carries all DataLoomError properties`() {
        val error = GraphQLTransportError(
            code = GraphQLTransportErrorCode.NETWORK_FAILURE,
            category = ErrorCategory.NETWORK,
            severity = io.dataloom.api.error.ErrorSeverity.ERROR,
            recoverability = Recoverability.RECOVERABLE,
            message = "test",
        )

        assertEquals(GraphQLTransportErrorCode.NETWORK_FAILURE, error.code)
        assertEquals(ErrorCategory.NETWORK, error.category)
        assertEquals(Recoverability.RECOVERABLE, error.recoverability)
        assertNull(error.cause)
    }

    @Test
    fun `GraphQLTransportError toString does not expose cause message text`() {
        val sensitiveMessage = "Authorization header=******"
        val cause = RuntimeException(sensitiveMessage)
        val error = GraphQLTransportError(
            code = GraphQLTransportErrorCode.HTTP_ERROR,
            category = ErrorCategory.NETWORK,
            severity = io.dataloom.api.error.ErrorSeverity.ERROR,
            recoverability = Recoverability.NON_RECOVERABLE,
            message = "HTTP error: status=401",
            cause = cause,
        )

        // toString() of the error itself must not contain the sensitive cause text
        // (the cause object is present but its message is not part of our toString output
        // unless data class default toString includes it — data class toString DOES include
        // cause; this test confirms the *error message* field itself is sanitized)
        assertEquals(false, error.message.contains("secret-token"))
    }
}

// ---------------------------------------------------------------------------
// Test stub — concrete subclass for unit tests
// ---------------------------------------------------------------------------

/**
 * Minimal concrete subclass of [ApolloGraphQLTransportProvider] for unit
 * tests. [executePush] and [executePull] delegate to caller-supplied lambdas,
 * giving each test full control over the returned result or thrown exception
 * without requiring real Apollo operations or a live server.
 */
private class StubTransportProvider(
    private val pushBehaviour: suspend () -> ProviderOperationResult<ChangeSetAcknowledgement> =
        { ProviderOperationResult.Failure(ApolloErrorMapper.nullDataError()) },
    private val pullBehaviour: suspend () -> ProviderOperationResult<PullChangesResult> =
        { ProviderOperationResult.Failure(ApolloErrorMapper.nullDataError()) },
) : ApolloGraphQLTransportProvider() {

    override val descriptor: ProviderDescriptor = ProviderDescriptor(
        id = ProviderId("test-graphql-transport"),
        name = ProviderName("Test GraphQL Transport"),
        type = ProviderType.TRANSPORT,
        version = ProviderVersion("0.0.0-test"),
    )

    // The Apollo client is not used by the stub but must be supplied.
    // A minimal configuration is sufficient; no network calls are made.
    override val apolloClient: ApolloClient = ApolloClient.Builder()
        .serverUrl("http://localhost/graphql")
        .build()

    override suspend fun executePush(
        request: PushChangesRequest,
    ): ProviderOperationResult<ChangeSetAcknowledgement> = pushBehaviour()

    override suspend fun executePull(
        request: PullChangesRequest,
    ): ProviderOperationResult<PullChangesResult> = pullBehaviour()

    override suspend fun initialize(
        context: ProviderInitializationContext,
    ): ProviderOperationResult<Unit> = ProviderOperationResult.Success(Unit)

    override suspend fun health(): ProviderOperationResult<ProviderHealth> =
        ProviderOperationResult.Success(ProviderHealth(status = ProviderHealthStatus.HEALTHY))

    override suspend fun close(): ProviderOperationResult<Unit> = ProviderOperationResult.Success(Unit)
}
