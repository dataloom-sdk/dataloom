package io.dataloom.api.transport

import io.dataloom.api.change.ChangeEvent
import io.dataloom.api.change.ChangeSet
import io.dataloom.api.change.EntityReference
import io.dataloom.api.context.ExecutionContext
import io.dataloom.api.error.DataLoomError
import io.dataloom.api.error.ErrorCategory
import io.dataloom.api.error.ErrorCode
import io.dataloom.api.error.ErrorSeverity
import io.dataloom.api.error.Recoverability
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
import io.dataloom.api.provider.ProviderName
import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.api.provider.ProviderType
import io.dataloom.api.provider.ProviderVersion
import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class TransportContractsTest {

    @Test
    fun `push request preserves synchronization request`() {
        val request: SynchronizationRequest = sampleSynchronizationRequest()
        val pushRequest: PushChangesRequest = PushChangesRequest(
            request = request,
            changeSet = sampleChangeSet(),
        )

        assertEquals(request, pushRequest.request)
    }

    @Test
    fun `push request preserves change set`() {
        val changeSet: ChangeSet = sampleChangeSet()
        val pushRequest: PushChangesRequest = PushChangesRequest(
            request = sampleSynchronizationRequest(),
            changeSet = changeSet,
        )

        assertEquals(changeSet, pushRequest.changeSet)
    }

    @Test
    fun `equal push requests compare as equal`() {
        val request: SynchronizationRequest = sampleSynchronizationRequest()
        val changeSet: ChangeSet = sampleChangeSet()

        val first: PushChangesRequest = PushChangesRequest(request = request, changeSet = changeSet)
        val second: PushChangesRequest = PushChangesRequest(request = request, changeSet = changeSet)

        assertEquals(first, second)
        assertEquals(first.hashCode(), second.hashCode())
    }

    @Test
    fun `push request construction causes no transport operation`() {
        val pushRequest: PushChangesRequest = PushChangesRequest(
            request = sampleSynchronizationRequest(),
            changeSet = sampleChangeSet(),
        )

        assertEquals("workflow-001", pushRequest.request.workflowId.value)
    }

    @Test
    fun `pull request preserves synchronization request`() {
        val request: SynchronizationRequest = sampleSynchronizationRequest()
        val pullRequest: PullChangesRequest = PullChangesRequest(request = request)

        assertEquals(request, pullRequest.request)
    }

    @Test
    fun `pull request supports empty entity type selection`() {
        val pullRequest: PullChangesRequest = PullChangesRequest(
            request = sampleSynchronizationRequest(),
        )

        assertTrue(pullRequest.entityTypes.isEmpty())
    }

    @Test
    fun `pull request preserves entity types`() {
        val entityTypes: Set<EntityType> = setOf(EntityType("invoice"), EntityType("payment"))
        val pullRequest: PullChangesRequest = PullChangesRequest(
            request = sampleSynchronizationRequest(),
            entityTypes = entityTypes,
        )

        assertEquals(entityTypes, pullRequest.entityTypes)
    }

    @Test
    fun `pull request defensively copies source entity type set`() {
        val source: MutableSet<EntityType> = mutableSetOf(EntityType("invoice"))
        val pullRequest: PullChangesRequest = PullChangesRequest(
            request = sampleSynchronizationRequest(),
            entityTypes = source,
        )

        source += EntityType("payment")

        assertEquals(setOf(EntityType("invoice")), pullRequest.entityTypes)
    }

    @Test
    fun `pull request accepts positive max events`() {
        val pullRequest: PullChangesRequest = PullChangesRequest(
            request = sampleSynchronizationRequest(),
            maxEvents = 10,
        )

        assertEquals(10, pullRequest.maxEvents)
    }

    @Test
    fun `pull request accepts null max events`() {
        val pullRequest: PullChangesRequest = PullChangesRequest(
            request = sampleSynchronizationRequest(),
            maxEvents = null,
        )

        assertEquals(null, pullRequest.maxEvents)
    }

    @Test
    fun `pull request rejects zero max events`() {
        assertFailsWith<IllegalArgumentException> {
            PullChangesRequest(
                request = sampleSynchronizationRequest(),
                maxEvents = 0,
            )
        }
    }

    @Test
    fun `pull request rejects negative max events`() {
        assertFailsWith<IllegalArgumentException> {
            PullChangesRequest(
                request = sampleSynchronizationRequest(),
                maxEvents = -1,
            )
        }
    }

    @Test
    fun `equal pull requests compare as equal`() {
        val request: SynchronizationRequest = sampleSynchronizationRequest()
        val entityTypes: Set<EntityType> = setOf(EntityType("invoice"))

        val first: PullChangesRequest = PullChangesRequest(
            request = request,
            entityTypes = entityTypes,
            maxEvents = 5,
        )
        val second: PullChangesRequest = PullChangesRequest(
            request = request,
            entityTypes = entityTypes,
            maxEvents = 5,
        )

        assertEquals(first, second)
        assertEquals(first.hashCode(), second.hashCode())
    }

    @Test
    fun `no changes result is representable`() {
        assertEquals(PullChangesResult.NoChanges, PullChangesResult.NoChanges)
    }

    @Test
    fun `changes result preserves change set`() {
        val changeSet: ChangeSet = sampleChangeSet()
        val result: PullChangesResult.Changes = PullChangesResult.Changes(
            changeSet = changeSet,
            hasMore = false,
        )

        assertEquals(changeSet, result.changeSet)
    }

    @Test
    fun `changes result preserves hasMore`() {
        val result: PullChangesResult.Changes = PullChangesResult.Changes(
            changeSet = sampleChangeSet(),
            hasMore = true,
        )

        assertTrue(result.hasMore)
    }

    @Test
    fun `no changes and changes results remain distinct`() {
        val noChanges: PullChangesResult = PullChangesResult.NoChanges
        val changes: PullChangesResult = PullChangesResult.Changes(
            changeSet = sampleChangeSet(),
            hasMore = false,
        )

        assertNotEquals(noChanges, changes)
    }

    @Test
    fun `transport provider descriptor uses transport type`() {
        val provider: TransportProvider = FakeTransportProvider(
            pushResult = ProviderOperationResult.Success(Unit),
            pullResult = ProviderOperationResult.Success(PullChangesResult.NoChanges),
        )

        assertEquals(ProviderType.TRANSPORT, provider.descriptor.type)
    }

    @Test
    fun `transport provider can successfully push change set`() {
        val request: PushChangesRequest = PushChangesRequest(
            request = sampleSynchronizationRequest(),
            changeSet = sampleChangeSet(),
        )
        val provider: TransportProvider = FakeTransportProvider(
            pushResult = ProviderOperationResult.Success(Unit),
            pullResult = ProviderOperationResult.Success(PullChangesResult.NoChanges),
        )

        val result: ProviderOperationResult<Unit> = runSuspend {
            provider.pushChanges(request)
        }

        assertIs<ProviderOperationResult.Success<Unit>>(result)
    }

    @Test
    fun `transport provider can return no changes`() {
        val provider: TransportProvider = FakeTransportProvider(
            pushResult = ProviderOperationResult.Success(Unit),
            pullResult = ProviderOperationResult.Success(PullChangesResult.NoChanges),
        )

        val result: ProviderOperationResult<PullChangesResult> = runSuspend {
            provider.pullChanges(PullChangesRequest(request = sampleSynchronizationRequest()))
        }

        assertEquals(
            ProviderOperationResult.Success(PullChangesResult.NoChanges),
            result,
        )
    }

    @Test
    fun `transport provider can return inbound changes`() {
        val changes: PullChangesResult.Changes = PullChangesResult.Changes(
            changeSet = sampleChangeSet(),
            hasMore = true,
        )
        val provider: TransportProvider = FakeTransportProvider(
            pushResult = ProviderOperationResult.Success(Unit),
            pullResult = ProviderOperationResult.Success(changes),
        )

        val result: ProviderOperationResult<PullChangesResult> = runSuspend {
            provider.pullChanges(PullChangesRequest(request = sampleSynchronizationRequest()))
        }

        assertEquals(ProviderOperationResult.Success(changes), result)
    }

    @Test
    fun `transport provider can return dataloom error failure`() {
        val failure: ProviderOperationResult.Failure = ProviderOperationResult.Failure(
            TestDataLoomError(
                code = ErrorCode("DL-TRANSPORT-001"),
                category = ErrorCategory.PROVIDER,
                severity = ErrorSeverity.ERROR,
                recoverability = Recoverability.RECOVERABLE,
                message = "Connectivity failure.",
                cause = null,
            ),
        )
        val provider: TransportProvider = FakeTransportProvider(
            pushResult = failure,
            pullResult = failure,
        )

        val result: ProviderOperationResult<PullChangesResult> = runSuspend {
            provider.pullChanges(PullChangesRequest(request = sampleSynchronizationRequest()))
        }

        assertEquals(failure, result)
    }

    private fun sampleSynchronizationRequest(): SynchronizationRequest = SynchronizationRequest(
        workflowId = WorkflowId("workflow-001"),
        sessionId = SynchronizationSessionId("session-001"),
        direction = SynchronizationDirection.BIDIRECTIONAL,
        mode = SynchronizationMode.DELTA,
        context = ExecutionContext(
            executionId = ExecutionId("execution-001"),
            correlationId = CorrelationId("corr-001"),
        ),
    )

    private fun sampleChangeSet(): ChangeSet = ChangeSet(
        id = ChangeSetId("changeset-001"),
        events = listOf(
            ChangeEvent(
                id = ChangeEventId("event-001"),
                entity = EntityReference(
                    type = EntityType("invoice"),
                    id = EntityId("entity-001"),
                ),
                operation = ChangeOperation.UPDATE,
            ),
        ),
    )

    private class FakeTransportProvider(
        private val pushResult: ProviderOperationResult<Unit>,
        private val pullResult: ProviderOperationResult<PullChangesResult>,
    ) : TransportProvider {
        override val descriptor: ProviderDescriptor = ProviderDescriptor(
            id = ProviderId("provider.transport.fake"),
            name = ProviderName("Fake Transport Provider"),
            type = ProviderType.TRANSPORT,
            version = ProviderVersion("1.0.0"),
        )

        override suspend fun initialize(
            context: io.dataloom.api.provider.ProviderInitializationContext,
        ): ProviderOperationResult<Unit> = ProviderOperationResult.Success(Unit)

        override suspend fun health(): ProviderOperationResult<ProviderHealth> {
            return ProviderOperationResult.Success(
                ProviderHealth(
                    status = ProviderHealthStatus.HEALTHY,
                ),
            )
        }

        override suspend fun close(): ProviderOperationResult<Unit> = ProviderOperationResult.Success(Unit)

        override suspend fun pushChanges(
            request: PushChangesRequest,
        ): ProviderOperationResult<Unit> = pushResult

        override suspend fun pullChanges(
            request: PullChangesRequest,
        ): ProviderOperationResult<PullChangesResult> = pullResult
    }

    private data class TestDataLoomError(
        override val code: ErrorCode,
        override val category: ErrorCategory,
        override val severity: ErrorSeverity,
        override val recoverability: Recoverability,
        override val message: String,
        override val cause: Throwable?,
    ) : DataLoomError

    private fun <T> runSuspend(block: suspend () -> T): T {
        var continuationResult: Result<T>? = null
        block.startCoroutine(
            object : Continuation<T> {
                override val context: CoroutineContext = EmptyCoroutineContext

                override fun resumeWith(result: Result<T>) {
                    continuationResult = result
                }
            },
        )
        return continuationResult?.getOrThrow()
            ?: error("Suspend block did not complete synchronously in test.")
    }
}
