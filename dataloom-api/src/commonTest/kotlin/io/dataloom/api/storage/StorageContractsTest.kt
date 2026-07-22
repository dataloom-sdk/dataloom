package io.dataloom.api.storage

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
import io.dataloom.api.provider.ProviderInitializationContext
import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.api.provider.ProviderType
import io.dataloom.api.provider.ProviderId
import io.dataloom.api.provider.ProviderName
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

class StorageContractsTest {

    // -------------------------------------------------------------------------
    // OutboundChangeReadRequest tests
    // -------------------------------------------------------------------------

    @Test
    fun `outbound read request preserves synchronization request`() {
        val request: SynchronizationRequest = sampleSynchronizationRequest()
        val readRequest: OutboundChangeReadRequest = OutboundChangeReadRequest(request = request)

        assertEquals(request, readRequest.request)
    }

    @Test
    fun `outbound read request supports empty entity type selection`() {
        val readRequest: OutboundChangeReadRequest = OutboundChangeReadRequest(
            request = sampleSynchronizationRequest(),
        )

        assertTrue(readRequest.entityTypes.isEmpty())
    }

    @Test
    fun `outbound read request preserves entity types`() {
        val entityTypes: Set<EntityType> = setOf(EntityType("invoice"), EntityType("payment"))
        val readRequest: OutboundChangeReadRequest = OutboundChangeReadRequest(
            request = sampleSynchronizationRequest(),
            entityTypes = entityTypes,
        )

        assertEquals(entityTypes, readRequest.entityTypes)
    }

    @Test
    fun `outbound read request defensively copies source entity type set`() {
        val source: MutableSet<EntityType> = mutableSetOf(EntityType("invoice"))
        val readRequest: OutboundChangeReadRequest = OutboundChangeReadRequest(
            request = sampleSynchronizationRequest(),
            entityTypes = source,
        )

        source += EntityType("payment")

        assertEquals(setOf(EntityType("invoice")), readRequest.entityTypes)
    }

    @Test
    fun `outbound read request accepts positive max events`() {
        val readRequest: OutboundChangeReadRequest = OutboundChangeReadRequest(
            request = sampleSynchronizationRequest(),
            maxEvents = 10,
        )

        assertEquals(10, readRequest.maxEvents)
    }

    @Test
    fun `outbound read request accepts null max events`() {
        val readRequest: OutboundChangeReadRequest = OutboundChangeReadRequest(
            request = sampleSynchronizationRequest(),
            maxEvents = null,
        )

        assertEquals(null, readRequest.maxEvents)
    }

    @Test
    fun `outbound read request rejects zero max events`() {
        assertFailsWith<IllegalArgumentException> {
            OutboundChangeReadRequest(
                request = sampleSynchronizationRequest(),
                maxEvents = 0,
            )
        }
    }

    @Test
    fun `outbound read request rejects negative max events`() {
        assertFailsWith<IllegalArgumentException> {
            OutboundChangeReadRequest(
                request = sampleSynchronizationRequest(),
                maxEvents = -1,
            )
        }
    }

    @Test
    fun `equal outbound read requests compare as equal`() {
        val request: SynchronizationRequest = sampleSynchronizationRequest()
        val entityTypes: Set<EntityType> = setOf(EntityType("invoice"))

        val first: OutboundChangeReadRequest = OutboundChangeReadRequest(
            request = request,
            entityTypes = entityTypes,
            maxEvents = 5,
        )
        val second: OutboundChangeReadRequest = OutboundChangeReadRequest(
            request = request,
            entityTypes = entityTypes,
            maxEvents = 5,
        )

        assertEquals(first, second)
        assertEquals(first.hashCode(), second.hashCode())
    }

    // -------------------------------------------------------------------------
    // OutboundChangeReadResult tests
    // -------------------------------------------------------------------------

    @Test
    fun `no changes result is representable`() {
        assertEquals(OutboundChangeReadResult.NoChanges, OutboundChangeReadResult.NoChanges)
    }

    @Test
    fun `changes result preserves change set`() {
        val changeSet: ChangeSet = sampleChangeSet()
        val result: OutboundChangeReadResult.Changes = OutboundChangeReadResult.Changes(
            changeSet = changeSet,
            hasMore = false,
        )

        assertEquals(changeSet, result.changeSet)
    }

    @Test
    fun `changes result preserves hasMore`() {
        val result: OutboundChangeReadResult.Changes = OutboundChangeReadResult.Changes(
            changeSet = sampleChangeSet(),
            hasMore = true,
        )

        assertTrue(result.hasMore)
    }

    @Test
    fun `no changes and changes results remain distinct`() {
        val noChanges: OutboundChangeReadResult = OutboundChangeReadResult.NoChanges
        val changes: OutboundChangeReadResult = OutboundChangeReadResult.Changes(
            changeSet = sampleChangeSet(),
            hasMore = false,
        )

        assertNotEquals(noChanges, changes)
    }

    // -------------------------------------------------------------------------
    // InboundChangeApplyRequest tests
    // -------------------------------------------------------------------------

    @Test
    fun `inbound apply request preserves synchronization request`() {
        val request: SynchronizationRequest = sampleSynchronizationRequest()
        val applyRequest: InboundChangeApplyRequest = InboundChangeApplyRequest(
            request = request,
            changeSet = sampleChangeSet(),
        )

        assertEquals(request, applyRequest.request)
    }

    @Test
    fun `inbound apply request preserves change set`() {
        val changeSet: ChangeSet = sampleChangeSet()
        val applyRequest: InboundChangeApplyRequest = InboundChangeApplyRequest(
            request = sampleSynchronizationRequest(),
            changeSet = changeSet,
        )

        assertEquals(changeSet, applyRequest.changeSet)
    }

    @Test
    fun `equal inbound apply requests compare as equal`() {
        val request: SynchronizationRequest = sampleSynchronizationRequest()
        val changeSet: ChangeSet = sampleChangeSet()

        val first: InboundChangeApplyRequest = InboundChangeApplyRequest(
            request = request,
            changeSet = changeSet,
        )
        val second: InboundChangeApplyRequest = InboundChangeApplyRequest(
            request = request,
            changeSet = changeSet,
        )

        assertEquals(first, second)
        assertEquals(first.hashCode(), second.hashCode())
    }

    @Test
    fun `inbound apply request construction causes no storage action`() {
        val applyRequest: InboundChangeApplyRequest = InboundChangeApplyRequest(
            request = sampleSynchronizationRequest(),
            changeSet = sampleChangeSet(),
        )

        assertEquals("workflow-001", applyRequest.request.workflowId.value)
    }

    // -------------------------------------------------------------------------
    // StorageProvider tests
    // -------------------------------------------------------------------------

    @Test
    fun `storage provider descriptor uses storage type`() {
        val provider: StorageProvider = FakeStorageProvider(
            readResult = ProviderOperationResult.Success(OutboundChangeReadResult.NoChanges),
            applyResult = ProviderOperationResult.Success(Unit),
        )

        assertEquals(ProviderType.STORAGE, provider.descriptor.type)
    }

    @Test
    fun `storage provider can return no changes`() {
        val provider: StorageProvider = FakeStorageProvider(
            readResult = ProviderOperationResult.Success(OutboundChangeReadResult.NoChanges),
            applyResult = ProviderOperationResult.Success(Unit),
        )

        val result: ProviderOperationResult<OutboundChangeReadResult> = runSuspend {
            provider.readOutboundChanges(
                OutboundChangeReadRequest(request = sampleSynchronizationRequest()),
            )
        }

        assertEquals(
            ProviderOperationResult.Success(OutboundChangeReadResult.NoChanges),
            result,
        )
    }

    @Test
    fun `storage provider can return outbound change set`() {
        val changes: OutboundChangeReadResult.Changes = OutboundChangeReadResult.Changes(
            changeSet = sampleChangeSet(),
            hasMore = true,
        )
        val provider: StorageProvider = FakeStorageProvider(
            readResult = ProviderOperationResult.Success(changes),
            applyResult = ProviderOperationResult.Success(Unit),
        )

        val result: ProviderOperationResult<OutboundChangeReadResult> = runSuspend {
            provider.readOutboundChanges(
                OutboundChangeReadRequest(request = sampleSynchronizationRequest()),
            )
        }

        assertEquals(ProviderOperationResult.Success(changes), result)
    }

    @Test
    fun `storage provider can report successful inbound application`() {
        val provider: StorageProvider = FakeStorageProvider(
            readResult = ProviderOperationResult.Success(OutboundChangeReadResult.NoChanges),
            applyResult = ProviderOperationResult.Success(Unit),
        )

        val result: ProviderOperationResult<Unit> = runSuspend {
            provider.applyInboundChanges(
                InboundChangeApplyRequest(
                    request = sampleSynchronizationRequest(),
                    changeSet = sampleChangeSet(),
                ),
            )
        }

        assertIs<ProviderOperationResult.Success<Unit>>(result)
    }

    @Test
    fun `storage provider can return dataloom error failure`() {
        val failure: ProviderOperationResult.Failure = ProviderOperationResult.Failure(
            TestDataLoomError(
                code = ErrorCode("DL-STORAGE-001"),
                category = ErrorCategory.PROVIDER,
                severity = ErrorSeverity.ERROR,
                recoverability = Recoverability.RECOVERABLE,
                message = "Storage read failure.",
                cause = null,
            ),
        )
        val provider: StorageProvider = FakeStorageProvider(
            readResult = failure,
            applyResult = failure,
        )

        val result: ProviderOperationResult<OutboundChangeReadResult> = runSuspend {
            provider.readOutboundChanges(
                OutboundChangeReadRequest(request = sampleSynchronizationRequest()),
            )
        }

        assertEquals(failure, result)
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

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

    private class FakeStorageProvider(
        private val readResult: ProviderOperationResult<OutboundChangeReadResult>,
        private val applyResult: ProviderOperationResult<Unit>,
    ) : StorageProvider {
        override val descriptor: ProviderDescriptor = ProviderDescriptor(
            id = ProviderId("provider.storage.fake"),
            name = ProviderName("Fake Storage Provider"),
            type = ProviderType.STORAGE,
            version = ProviderVersion("1.0.0"),
        )

        override suspend fun initialize(
            context: ProviderInitializationContext,
        ): ProviderOperationResult<Unit> = ProviderOperationResult.Success(Unit)

        override suspend fun health(): ProviderOperationResult<ProviderHealth> =
            ProviderOperationResult.Success(ProviderHealth(status = ProviderHealthStatus.HEALTHY))

        override suspend fun close(): ProviderOperationResult<Unit> =
            ProviderOperationResult.Success(Unit)

        override suspend fun readOutboundChanges(
            request: OutboundChangeReadRequest,
        ): ProviderOperationResult<OutboundChangeReadResult> = readResult

        override suspend fun applyInboundChanges(
            request: InboundChangeApplyRequest,
        ): ProviderOperationResult<Unit> = applyResult
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
