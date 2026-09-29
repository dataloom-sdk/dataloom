package io.dataloom.transport.graphql

import com.apollographql.apollo.ApolloClient
import com.apollographql.apollo.api.http.HttpHeader
import com.apollographql.apollo.exception.ApolloHttpException
import io.dataloom.api.change.ChangeEvent
import io.dataloom.api.change.ChangeSet
import io.dataloom.api.change.EntityReference
import io.dataloom.api.context.ExecutionContext
import io.dataloom.api.error.RetryAfterParser
import io.dataloom.api.error.RetryDelayHintCarrier
import io.dataloom.api.identifier.ChangeEventId
import io.dataloom.api.identifier.ChangeSetId
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
import io.dataloom.api.provider.ProviderId
import io.dataloom.api.provider.ProviderInitializationContext
import io.dataloom.api.provider.ProviderName
import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.api.provider.ProviderType
import io.dataloom.api.provider.ProviderVersion
import io.dataloom.api.synchronization.ChangeSetAcknowledgement
import io.dataloom.api.time.DataLoomClock
import io.dataloom.api.time.DataLoomInstant
import io.dataloom.api.transport.PullChangesRequest
import io.dataloom.api.transport.PullChangesResult
import io.dataloom.api.transport.PushChangesRequest
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * Table-driven coverage of the GraphQL-over-HTTP adapter's `Retry-After`
 * handling, driving the real [ApolloGraphQLTransportProvider.pushChanges]
 * path against a thrown [ApolloHttpException] so the produced error is
 * proven to reach [RetryDelayHintCarrier] on the public provider surface, not
 * just [ApolloErrorMapper] in isolation.
 */
class ApolloRetryAfterTest {
    private class FixedClock(private val epochMilliseconds: Long) : DataLoomClock {
        override fun now(): DataLoomInstant = DataLoomInstant(epochMilliseconds)
    }

    private suspend fun pushFailure(
        statusCode: Int,
        headerLines: List<Pair<String, String>>,
        clock: DataLoomClock?,
    ) = StubTransportProvider(
        pushBehaviour = {
            throw ApolloHttpException(
                statusCode = statusCode,
                headers = headerLines.map { (name, value) -> HttpHeader(name, value) },
                body = null,
                message = "HTTP $statusCode",
            )
        },
        clock = clock,
    ).pushChanges(samplePushRequest).let { (it as ProviderOperationResult.Failure).error }

    @Test
    fun `delta seconds`() = runTest {
        val error = pushFailure(429, listOf("Retry-After" to "3"), clock = null)
        val hint = assertIs<RetryDelayHintCarrier>(error)
        assertEquals(3_000L, hint.retryDelayHint.delayMilliseconds)
    }

    @Test
    fun `http date in the future`() = runTest {
        val error = pushFailure(
            503,
            listOf("Retry-After" to "Sun, 06 Nov 1994 08:49:37 GMT"),
            clock = FixedClock(784_111_777_000L - 30_000L),
        )
        val hint = assertIs<RetryDelayHintCarrier>(error)
        assertEquals(30_000L, hint.retryDelayHint.delayMilliseconds)
    }

    @Test
    fun `http date in the past clamps to zero`() = runTest {
        val error = pushFailure(
            429,
            listOf("Retry-After" to "Sun, 06 Nov 1994 08:49:37 GMT"),
            clock = FixedClock(784_111_777_000L + 60_000L),
        )
        val hint = assertIs<RetryDelayHintCarrier>(error)
        assertEquals(0L, hint.retryDelayHint.delayMilliseconds)
    }

    @Test
    fun `http date without a configured clock produces no hint`() = runTest {
        val error = pushFailure(429, listOf("Retry-After" to "Sun, 06 Nov 1994 08:49:37 GMT"), clock = null)
        assertNull(error as? RetryDelayHintCarrier)
        assertIs<GraphQLTransportError>(error)
    }

    @Test
    fun `malformed value produces no hint`() = runTest {
        val error = pushFailure(429, listOf("Retry-After" to "not-a-delay"), clock = null)
        assertNull(error as? RetryDelayHintCarrier)
        assertIs<GraphQLTransportError>(error)
    }

    @Test
    fun `negative value produces no hint`() = runTest {
        val error = pushFailure(429, listOf("Retry-After" to "-5"), clock = null)
        assertNull(error as? RetryDelayHintCarrier)
    }

    @Test
    fun `huge value clamps to the maximum delay`() = runTest {
        val error = pushFailure(429, listOf("Retry-After" to "99999999999999999999999"), clock = null)
        val hint = assertIs<RetryDelayHintCarrier>(error)
        assertEquals(RetryAfterParser.MAXIMUM_DELAY_MILLISECONDS, hint.retryDelayHint.delayMilliseconds)
    }

    @Test
    fun `missing header produces no hint`() = runTest {
        val error = pushFailure(429, emptyList(), clock = null)
        assertNull(error as? RetryDelayHintCarrier)
    }

    @Test
    fun `multiple headers use the first value`() = runTest {
        val error = pushFailure(429, listOf("Retry-After" to "3", "Retry-After" to "300"), clock = null)
        val hint = assertIs<RetryDelayHintCarrier>(error)
        assertEquals(3_000L, hint.retryDelayHint.delayMilliseconds)
    }

    @Test
    fun `header name is case insensitive`() = runTest {
        val error = pushFailure(429, listOf("retry-after" to "3"), clock = null)
        val hint = assertIs<RetryDelayHintCarrier>(error)
        assertEquals(3_000L, hint.retryDelayHint.delayMilliseconds)
    }

    @Test
    fun `503 without Retry-After produces no hint`() = runTest {
        val error = pushFailure(503, emptyList(), clock = null)
        assertNull(error as? RetryDelayHintCarrier)
    }

    @Test
    fun `500 never produces a hint even with the header present`() = runTest {
        val error = pushFailure(500, listOf("Retry-After" to "3"), clock = null)
        assertNull(error as? RetryDelayHintCarrier)
    }

    private val samplePushRequest: PushChangesRequest = PushChangesRequest(
        request = SynchronizationRequest(
            workflowId = WorkflowId("workflow-1"),
            sessionId = SynchronizationSessionId("session-1"),
            direction = SynchronizationDirection.PUSH,
            mode = SynchronizationMode.DELTA,
            context = ExecutionContext(
                executionId = ExecutionId("execution-1"),
                correlationId = CorrelationId("correlation-1"),
            ),
        ),
        changeSet = ChangeSet(
            id = ChangeSetId("change-set-1"),
            events = listOf(
                ChangeEvent(
                    id = ChangeEventId("event-1"),
                    entity = EntityReference(type = EntityType("widget"), id = EntityId("widget-1")),
                    operation = ChangeOperation.UPDATE,
                ),
            ),
        ),
    )
}

private class StubTransportProvider(
    private val pushBehaviour: suspend () -> ProviderOperationResult<ChangeSetAcknowledgement>,
    override val clock: DataLoomClock?,
) : ApolloGraphQLTransportProvider() {

    override val descriptor: ProviderDescriptor = ProviderDescriptor(
        id = ProviderId("test-graphql-retry-after-transport"),
        name = ProviderName("Test GraphQL Retry-After Transport"),
        type = ProviderType.TRANSPORT,
        version = ProviderVersion("0.0.0-test"),
    )

    // The Apollo client is not used by the stub but must be supplied.
    override val apolloClient: ApolloClient = ApolloClient.Builder()
        .serverUrl("http://localhost/graphql")
        .build()

    override suspend fun executePush(
        request: PushChangesRequest,
    ): ProviderOperationResult<ChangeSetAcknowledgement> = pushBehaviour()

    override suspend fun executePull(
        request: PullChangesRequest,
    ): ProviderOperationResult<PullChangesResult> = error("Not used.")

    override suspend fun initialize(
        context: ProviderInitializationContext,
    ): ProviderOperationResult<Unit> = ProviderOperationResult.Success(Unit)

    override suspend fun health(): ProviderOperationResult<ProviderHealth> =
        ProviderOperationResult.Success(ProviderHealth(status = ProviderHealthStatus.HEALTHY))

    override suspend fun close(): ProviderOperationResult<Unit> = ProviderOperationResult.Success(Unit)
}
