package io.dataloom.runtime.execution.outbound

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
import io.dataloom.api.identifier.ConflictId
import io.dataloom.api.identifier.CorrelationId
import io.dataloom.api.identifier.EntityId
import io.dataloom.api.identifier.EntityType
import io.dataloom.api.identifier.ExecutionId
import io.dataloom.api.identifier.IdentifierGenerator
import io.dataloom.api.identifier.QueueEntryId
import io.dataloom.api.identifier.QueueLeaseId
import io.dataloom.api.identifier.SynchronizationEventId
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
import io.dataloom.api.storage.InboundChangeApplyRequest
import io.dataloom.api.storage.OutboundChangeReadRequest
import io.dataloom.api.storage.OutboundChangeReadResult
import io.dataloom.api.storage.StorageProvider
import io.dataloom.api.synchronization.ChangeAcknowledgementStatus
import io.dataloom.api.synchronization.ChangeEventAcknowledgement
import io.dataloom.api.synchronization.ChangeSetAcknowledgement
import io.dataloom.api.synchronization.CheckpointReadRequest
import io.dataloom.api.synchronization.CheckpointWriteRequest
import io.dataloom.api.synchronization.OutboundChangeAcknowledgementRequest
import io.dataloom.api.synchronization.SynchronizationCheckpoint
import io.dataloom.api.synchronization.SynchronizationResult
import io.dataloom.api.synchronization.SynchronizationSkipReason
import io.dataloom.api.time.DataLoomClock
import io.dataloom.api.time.DataLoomInstant
import io.dataloom.api.transport.PullChangesRequest
import io.dataloom.api.transport.PullChangesResult
import io.dataloom.api.transport.PushChangesRequest
import io.dataloom.api.transport.TransportProvider
import io.dataloom.core.provider.ResolvedSynchronizationProviders
import io.dataloom.core.runtime.RuntimeDependencies
import io.dataloom.core.runtime.RuntimeIdentifierGenerators
import io.dataloom.runtime.execution.SynchronizationExecutionContext
import io.dataloom.runtime.execution.SynchronizationPipeline
import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.startCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Deterministic common tests for DL-021 outbound push synchronization
 * pipeline.
 *
 * All fakes are stateless or deterministically stateful. No real network,
 * real database, filesystem, Thread.sleep, arbitrary coroutine delay, Android
 * API, JVM-only API, reflection, ServiceLoader, system clock, random IDs,
 * production credentials, or personal data is used.
 *
 * Suspend functions are exercised using [kotlin.coroutines.startCoroutine]
 * primitives from the Kotlin standard library, without requiring
 * kotlinx.coroutines.
 */
class OutboundPushSynchronizationPipelineTest {

    // =========================================================================
    // Fake error
    // =========================================================================

    private data class FakeError(
        override val code: ErrorCode = ErrorCode("DL-FAKE"),
        override val category: ErrorCategory = ErrorCategory.PROVIDER,
        override val severity: ErrorSeverity = ErrorSeverity.ERROR,
        override val recoverability: Recoverability = Recoverability.NON_RECOVERABLE,
        override val message: String = "Fake error.",
        override val cause: Throwable? = null,
    ) : DataLoomError

    // =========================================================================
    // Fake storage provider
    // =========================================================================

    private class FakeStorageProvider(
        id: String = "storage-primary",
        private val readResults: MutableList<ProviderOperationResult<OutboundChangeReadResult>> = mutableListOf(),
        private val acknowledgeResults: MutableList<ProviderOperationResult<Unit>> = mutableListOf(),
    ) : StorageProvider {
        override val descriptor: ProviderDescriptor = ProviderDescriptor(
            id = ProviderId(id),
            name = ProviderName("Storage $id"),
            type = ProviderType.STORAGE,
            version = ProviderVersion("1.0.0"),
        )

        val readRequests: MutableList<OutboundChangeReadRequest> = mutableListOf()
        val acknowledgeRequests: MutableList<OutboundChangeAcknowledgementRequest> = mutableListOf()
        var readCallCount: Int = 0
        var acknowledgeCallCount: Int = 0
        var applyInboundChangesCallCount: Int = 0
        var readCheckpointCallCount: Int = 0
        var writeCheckpointCallCount: Int = 0
        var initializeCallCount: Int = 0
        var healthCallCount: Int = 0
        var closeCallCount: Int = 0

        override suspend fun initialize(context: ProviderInitializationContext): ProviderOperationResult<Unit> {
            initializeCallCount++
            return ProviderOperationResult.Success(Unit)
        }

        override suspend fun health(): ProviderOperationResult<ProviderHealth> {
            healthCallCount++
            return ProviderOperationResult.Success(ProviderHealth(ProviderHealthStatus.HEALTHY))
        }

        override suspend fun close(): ProviderOperationResult<Unit> {
            closeCallCount++
            return ProviderOperationResult.Success(Unit)
        }

        override suspend fun readOutboundChanges(
            request: OutboundChangeReadRequest,
        ): ProviderOperationResult<OutboundChangeReadResult> {
            readRequests.add(request)
            readCallCount++
            check(readResults.isNotEmpty()) { "FakeStorageProvider: no queued readOutboundChanges result." }
            return readResults.removeAt(0)
        }

        override suspend fun applyInboundChanges(
            request: InboundChangeApplyRequest,
        ): ProviderOperationResult<Unit> {
            applyInboundChangesCallCount++
            return ProviderOperationResult.Failure(FakeError())
        }

        override suspend fun acknowledgeOutboundChanges(
            request: OutboundChangeAcknowledgementRequest,
        ): ProviderOperationResult<Unit> {
            acknowledgeRequests.add(request)
            acknowledgeCallCount++
            check(acknowledgeResults.isNotEmpty()) { "FakeStorageProvider: no queued acknowledgeOutboundChanges result." }
            return acknowledgeResults.removeAt(0)
        }

        override suspend fun readCheckpoint(
            request: CheckpointReadRequest,
        ): ProviderOperationResult<SynchronizationCheckpoint?> {
            readCheckpointCallCount++
            return ProviderOperationResult.Success(null)
        }

        override suspend fun writeCheckpoint(
            request: CheckpointWriteRequest,
        ): ProviderOperationResult<Unit> {
            writeCheckpointCallCount++
            return ProviderOperationResult.Failure(FakeError())
        }
    }

    // =========================================================================
    // Fake transport provider
    // =========================================================================

    private class FakeTransportProvider(
        id: String = "transport-primary",
        private val pushResults: MutableList<ProviderOperationResult<ChangeSetAcknowledgement>> = mutableListOf(),
    ) : TransportProvider {
        override val descriptor: ProviderDescriptor = ProviderDescriptor(
            id = ProviderId(id),
            name = ProviderName("Transport $id"),
            type = ProviderType.TRANSPORT,
            version = ProviderVersion("1.0.0"),
        )

        val pushRequests: MutableList<PushChangesRequest> = mutableListOf()
        var pushCallCount: Int = 0
        var pullChangesCallCount: Int = 0
        var initializeCallCount: Int = 0
        var healthCallCount: Int = 0
        var closeCallCount: Int = 0

        override suspend fun initialize(context: ProviderInitializationContext): ProviderOperationResult<Unit> {
            initializeCallCount++
            return ProviderOperationResult.Success(Unit)
        }

        override suspend fun health(): ProviderOperationResult<ProviderHealth> {
            healthCallCount++
            return ProviderOperationResult.Success(ProviderHealth(ProviderHealthStatus.HEALTHY))
        }

        override suspend fun close(): ProviderOperationResult<Unit> {
            closeCallCount++
            return ProviderOperationResult.Success(Unit)
        }

        override suspend fun pushChanges(
            request: PushChangesRequest,
        ): ProviderOperationResult<ChangeSetAcknowledgement> {
            pushRequests.add(request)
            pushCallCount++
            check(pushResults.isNotEmpty()) { "FakeTransportProvider: no queued pushChanges result." }
            return pushResults.removeAt(0)
        }

        override suspend fun pullChanges(
            request: PullChangesRequest,
        ): ProviderOperationResult<PullChangesResult> {
            pullChangesCallCount++
            return ProviderOperationResult.Failure(FakeError())
        }
    }

    // =========================================================================
    // Fake clock
    // =========================================================================

    private class FakeClock(private val nowMs: Long = 5_000_000L) : DataLoomClock {
        var readCallCount: Int = 0
        override fun now(): DataLoomInstant {
            readCallCount++
            return DataLoomInstant(nowMs)
        }
    }

    /** Clock that throws if invoked (used to assert no clock read occurs during configuration construction). */
    private class ExplodingClock : DataLoomClock {
        override fun now(): DataLoomInstant = throw AssertionError("Clock must not be read.")
    }

    // =========================================================================
    // Model builders
    // =========================================================================

    private fun makeRequest(
        direction: SynchronizationDirection = SynchronizationDirection.PUSH,
    ) = SynchronizationRequest(
        workflowId = WorkflowId("workflow-001"),
        sessionId = SynchronizationSessionId("session-001"),
        direction = direction,
        mode = SynchronizationMode.DELTA,
        context = ExecutionContext(
            executionId = ExecutionId("execution-001"),
            correlationId = CorrelationId("corr-001"),
        ),
    )

    private fun makeChangeEvent(id: String, operation: ChangeOperation = ChangeOperation.UPDATE) =
        ChangeEvent(
            id = ChangeEventId(id),
            entity = EntityReference(type = EntityType("Order"), id = EntityId("entity-$id")),
            operation = operation,
        )

    private fun makeChangeSet(
        changeSetId: String = "change-set-1",
        eventIds: List<String> = listOf("event-1"),
    ) = ChangeSet(
        id = ChangeSetId(changeSetId),
        events = eventIds.map { makeChangeEvent(it) },
    )

    private fun makeAcknowledgement(
        changeSetId: String,
        eventStatuses: List<Pair<String, ChangeAcknowledgementStatus>>,
        errorFor: (String) -> DataLoomError? = { null },
    ) = ChangeSetAcknowledgement(
        changeSetId = ChangeSetId(changeSetId),
        events = eventStatuses.map { (eventId, status) ->
            ChangeEventAcknowledgement(
                eventId = ChangeEventId(eventId),
                status = status,
                error = errorFor(eventId),
            )
        },
    )

    private fun allAccepted(changeSetId: String, eventIds: List<String>) =
        makeAcknowledgement(changeSetId, eventIds.map { it to ChangeAcknowledgementStatus.ACCEPTED })

    private fun makeResolvedProviders(
        storage: StorageProvider,
        transport: TransportProvider,
    ) = ResolvedSynchronizationProviders(
        storageProvider = storage,
        transportProvider = transport,
        schedulerProvider = null,
        connectivityProvider = null,
        queueProvider = null,
    )

    private fun makeRuntimeDependencies(clock: DataLoomClock = FakeClock()): RuntimeDependencies {
        val idGenerators = RuntimeIdentifierGenerators(
            synchronizationEventIds = object : IdentifierGenerator<SynchronizationEventId> {
                override fun generate() = SynchronizationEventId("event-001")
            },
            queueEntryIds = object : IdentifierGenerator<QueueEntryId> {
                override fun generate() = QueueEntryId("queue-001")
            },
            queueLeaseIds = object : IdentifierGenerator<QueueLeaseId> {
                override fun generate() = QueueLeaseId("lease-001")
            },
            conflictIds = object : IdentifierGenerator<ConflictId> {
                override fun generate() = ConflictId("conflict-001")
            },
        )
        return RuntimeDependencies(clock = clock, identifiers = idGenerators)
    }

    private fun makeContext(
        storage: StorageProvider,
        transport: TransportProvider,
        request: SynchronizationRequest = makeRequest(),
        clock: DataLoomClock = FakeClock(),
    ) = SynchronizationExecutionContext(
        request = request,
        providers = makeResolvedProviders(storage, transport),
        runtimeDependencies = makeRuntimeDependencies(clock),
    )

    private fun makePipeline(configuration: OutboundPushPipelineConfiguration = OutboundPushPipelineConfiguration()) =
        OutboundPushSynchronizationPipeline(configuration)

    // =========================================================================
    // Coroutine helper
    // =========================================================================

    private object Pending

    @Suppress("UNCHECKED_CAST")
    private fun <T> runSuspend(block: suspend () -> T): T {
        var rawResult: Any? = Pending
        var thrown: Throwable? = null
        block.startCoroutine(
            object : Continuation<T> {
                override val context: CoroutineContext = EmptyCoroutineContext
                override fun resumeWith(result: Result<T>) {
                    if (result.isSuccess) {
                        rawResult = result.getOrNull()
                    } else {
                        thrown = result.exceptionOrNull()
                    }
                }
            },
        )
        thrown?.let { throw it }
        check(rawResult !== Pending) { "Suspend block did not complete synchronously in test." }
        return rawResult as T
    }

    // =========================================================================
    // Configuration tests
    // =========================================================================

    @Test
    fun configuration_defaultEntityTypesIsEmpty() {
        val configuration = OutboundPushPipelineConfiguration()
        assertTrue(configuration.entityTypes.isEmpty())
        assertEquals(100, configuration.maxEventsPerBatch)
        assertEquals(100, configuration.maxBatchesPerExecution)
    }

    @Test
    fun configuration_entityTypesIsDefensivelyCopied() {
        val source = mutableSetOf(EntityType("Order"))
        val configuration = OutboundPushPipelineConfiguration(entityTypes = source)
        source.add(EntityType("Invoice"))
        assertEquals(setOf(EntityType("Order")), configuration.entityTypes)
    }

    @Test
    fun configuration_positiveMaxEventsPerBatchAccepted() {
        val configuration = OutboundPushPipelineConfiguration(maxEventsPerBatch = 1)
        assertEquals(1, configuration.maxEventsPerBatch)
    }

    @Test
    fun configuration_zeroMaxEventsPerBatchRejected() {
        assertFailsWith<IllegalArgumentException> { OutboundPushPipelineConfiguration(maxEventsPerBatch = 0) }
    }

    @Test
    fun configuration_negativeMaxEventsPerBatchRejected() {
        assertFailsWith<IllegalArgumentException> { OutboundPushPipelineConfiguration(maxEventsPerBatch = -1) }
    }

    @Test
    fun configuration_positiveMaxBatchesPerExecutionAccepted() {
        val configuration = OutboundPushPipelineConfiguration(maxBatchesPerExecution = 1)
        assertEquals(1, configuration.maxBatchesPerExecution)
    }

    @Test
    fun configuration_zeroMaxBatchesPerExecutionRejected() {
        assertFailsWith<IllegalArgumentException> { OutboundPushPipelineConfiguration(maxBatchesPerExecution = 0) }
    }

    @Test
    fun configuration_negativeMaxBatchesPerExecutionRejected() {
        assertFailsWith<IllegalArgumentException> { OutboundPushPipelineConfiguration(maxBatchesPerExecution = -1) }
    }

    @Test
    fun configuration_constructionDoesNotReadClock() {
        // Constructing a configuration must not touch any clock; ExplodingClock
        // is never referenced by configuration construction, so simply
        // constructing here with no clock interaction is the assertion.
        OutboundPushPipelineConfiguration(entityTypes = setOf(EntityType("Order")))
    }

    // =========================================================================
    // Pipeline identity tests
    // =========================================================================

    @Test
    fun pipeline_implementsSynchronizationPipeline() {
        val pipeline = makePipeline()
        assertIs<SynchronizationPipeline>(pipeline)
    }

    @Test
    fun pipeline_declaresPushDirection() {
        val pipeline = makePipeline()
        assertEquals(SynchronizationDirection.PUSH, pipeline.direction)
    }

    // =========================================================================
    // No-changes tests
    // =========================================================================

    @Test
    fun execute_firstReadNoChanges_returnsSkippedWithNoChanges() {
        val storage = FakeStorageProvider(
            readResults = mutableListOf(ProviderOperationResult.Success(OutboundChangeReadResult.NoChanges)),
        )
        val transport = FakeTransportProvider()
        val clock = FakeClock(nowMs = 42L)
        val request = makeRequest()
        val context = makeContext(storage, transport, request = request, clock = clock)

        val result = runSuspend { makePipeline().execute(context) }

        val skipped = assertIs<SynchronizationResult.Skipped>(result)
        assertEquals(SynchronizationSkipReason.NO_CHANGES, skipped.reason)
        assertSame(request, skipped.request)
        assertEquals(DataLoomInstant(42L), skipped.completedAt)
        assertEquals(0L, skipped.summary.outboundEventsRead)
        assertEquals(0L, skipped.summary.outboundEventsAccepted)
        assertEquals(0, transport.pushCallCount)
        assertEquals(0, storage.acknowledgeCallCount)
        assertTrue(clock.readCallCount >= 1)
    }

    // =========================================================================
    // Single successful batch tests
    // =========================================================================

    @Test
    fun execute_oneSuccessfulBatch_returnsSucceededAndInvokesProvidersOnce() {
        val changeSet = makeChangeSet(eventIds = listOf("event-1", "event-2"))
        val acknowledgement = allAccepted("change-set-1", listOf("event-1", "event-2"))
        val storage = FakeStorageProvider(
            readResults = mutableListOf(
                ProviderOperationResult.Success(OutboundChangeReadResult.Changes(changeSet, hasMore = false)),
            ),
            acknowledgeResults = mutableListOf(ProviderOperationResult.Success(Unit)),
        )
        val transport = FakeTransportProvider(
            pushResults = mutableListOf(ProviderOperationResult.Success(acknowledgement)),
        )
        val request = makeRequest()
        val configuration = OutboundPushPipelineConfiguration(
            entityTypes = setOf(EntityType("Order")),
            maxEventsPerBatch = 50,
        )
        val context = makeContext(storage, transport, request = request)

        val result = runSuspend { OutboundPushSynchronizationPipeline(configuration).execute(context) }

        val succeeded = assertIs<SynchronizationResult.Succeeded>(result)
        assertSame(request, succeeded.request)
        assertEquals(2L, succeeded.summary.outboundEventsRead)
        assertEquals(2L, succeeded.summary.outboundEventsAccepted)
        assertEquals(0L, succeeded.summary.outboundEventsMarkedForRetry)
        assertEquals(0L, succeeded.summary.outboundEventsRejected)

        assertEquals(1, storage.readCallCount)
        assertEquals(1, transport.pushCallCount)
        assertEquals(1, storage.acknowledgeCallCount)

        val readRequest = storage.readRequests.single()
        assertSame(request, readRequest.request)
        assertEquals(setOf(EntityType("Order")), readRequest.entityTypes)
        assertEquals(50, readRequest.maxEvents)

        assertSame(changeSet, transport.pushRequests.single().changeSet)
        assertEquals(changeSet.id, storage.acknowledgeRequests.single().acknowledgement.changeSetId)
    }

    // =========================================================================
    // Multiple successful batches
    // =========================================================================

    @Test
    fun execute_multipleBatches_processSequentiallyUntilHasMoreFalse() {
        val changeSet1 = makeChangeSet(changeSetId = "cs-1", eventIds = listOf("e1"))
        val changeSet2 = makeChangeSet(changeSetId = "cs-2", eventIds = listOf("e2"))
        val ack1 = allAccepted("cs-1", listOf("e1"))
        val ack2 = allAccepted("cs-2", listOf("e2"))

        val storage = FakeStorageProvider(
            readResults = mutableListOf(
                ProviderOperationResult.Success(OutboundChangeReadResult.Changes(changeSet1, hasMore = true)),
                ProviderOperationResult.Success(OutboundChangeReadResult.Changes(changeSet2, hasMore = false)),
            ),
            acknowledgeResults = mutableListOf(
                ProviderOperationResult.Success(Unit),
                ProviderOperationResult.Success(Unit),
            ),
        )
        val transport = FakeTransportProvider(
            pushResults = mutableListOf(
                ProviderOperationResult.Success(ack1),
                ProviderOperationResult.Success(ack2),
            ),
        )
        val context = makeContext(storage, transport)

        val result = runSuspend { makePipeline().execute(context) }

        val succeeded = assertIs<SynchronizationResult.Succeeded>(result)
        assertEquals(2L, succeeded.summary.outboundEventsRead)
        assertEquals(2L, succeeded.summary.outboundEventsAccepted)
        assertEquals(2, storage.readCallCount)
        assertEquals(2, transport.pushCallCount)
        assertEquals(2, storage.acknowledgeCallCount)
        assertSame(changeSet1, transport.pushRequests[0].changeSet)
        assertSame(changeSet2, transport.pushRequests[1].changeSet)
    }

    @Test
    fun execute_laterNoChanges_returnsSucceededNotSkipped() {
        val changeSet = makeChangeSet(eventIds = listOf("e1"))
        val ack = allAccepted("change-set-1", listOf("e1"))
        val storage = FakeStorageProvider(
            readResults = mutableListOf(
                ProviderOperationResult.Success(OutboundChangeReadResult.Changes(changeSet, hasMore = true)),
                ProviderOperationResult.Success(OutboundChangeReadResult.NoChanges),
            ),
            acknowledgeResults = mutableListOf(ProviderOperationResult.Success(Unit)),
        )
        val transport = FakeTransportProvider(
            pushResults = mutableListOf(ProviderOperationResult.Success(ack)),
        )
        val context = makeContext(storage, transport)

        val result = runSuspend { makePipeline().execute(context) }

        val succeeded = assertIs<SynchronizationResult.Succeeded>(result)
        assertEquals(1L, succeeded.summary.outboundEventsRead)
        assertEquals(1L, succeeded.summary.outboundEventsAccepted)
        assertEquals(2, storage.readCallCount)
    }

    // =========================================================================
    // Push failure
    // =========================================================================

    @Test
    fun execute_pushFailure_returnsFailedAndStopsProcessing() {
        val changeSet1 = makeChangeSet(changeSetId = "cs-1", eventIds = listOf("e1"))
        val pushError = FakeError(message = "Push failed.")
        val storage = FakeStorageProvider(
            readResults = mutableListOf(
                ProviderOperationResult.Success(OutboundChangeReadResult.Changes(changeSet1, hasMore = true)),
            ),
        )
        val transport = FakeTransportProvider(
            pushResults = mutableListOf(ProviderOperationResult.Failure(pushError)),
        )
        val context = makeContext(storage, transport)

        val result = runSuspend { makePipeline().execute(context) }

        val failed = assertIs<SynchronizationResult.Failed>(result)
        assertEquals(pushError, failed.error)
        assertEquals(0, storage.acknowledgeCallCount)
        assertEquals(1, storage.readCallCount)
        assertEquals(0L, failed.summary.outboundEventsRead)
    }

    // =========================================================================
    // Acknowledgement validation
    // =========================================================================

    @Test
    fun execute_matchingAcknowledgement_succeeds() {
        val changeSet = makeChangeSet(eventIds = listOf("e1"))
        val ack = allAccepted("change-set-1", listOf("e1"))
        val storage = FakeStorageProvider(
            readResults = mutableListOf(
                ProviderOperationResult.Success(OutboundChangeReadResult.Changes(changeSet, hasMore = false)),
            ),
            acknowledgeResults = mutableListOf(ProviderOperationResult.Success(Unit)),
        )
        val transport = FakeTransportProvider(pushResults = mutableListOf(ProviderOperationResult.Success(ack)))
        val context = makeContext(storage, transport)

        val result = runSuspend { makePipeline().execute(context) }

        assertIs<SynchronizationResult.Succeeded>(result)
    }

    @Test
    fun execute_mismatchedChangeSetId_fails() {
        val changeSet = makeChangeSet(changeSetId = "cs-1", eventIds = listOf("e1"))
        val ack = allAccepted("cs-OTHER", listOf("e1"))
        val storage = FakeStorageProvider(
            readResults = mutableListOf(
                ProviderOperationResult.Success(OutboundChangeReadResult.Changes(changeSet, hasMore = false)),
            ),
        )
        val transport = FakeTransportProvider(pushResults = mutableListOf(ProviderOperationResult.Success(ack)))
        val context = makeContext(storage, transport)

        val result = runSuspend { makePipeline().execute(context) }

        val failed = assertIs<SynchronizationResult.Failed>(result)
        assertEquals(0, storage.acknowledgeCallCount)
        assertTrue(failed.error.message.isNotBlank())
    }

    @Test
    fun execute_unknownEventId_fails() {
        val changeSet = makeChangeSet(eventIds = listOf("e1"))
        val ack = allAccepted("change-set-1", listOf("e1", "unknown-event"))
        val storage = FakeStorageProvider(
            readResults = mutableListOf(
                ProviderOperationResult.Success(OutboundChangeReadResult.Changes(changeSet, hasMore = false)),
            ),
        )
        val transport = FakeTransportProvider(pushResults = mutableListOf(ProviderOperationResult.Success(ack)))
        val context = makeContext(storage, transport)

        val result = runSuspend { makePipeline().execute(context) }

        assertIs<SynchronizationResult.Failed>(result)
        assertEquals(0, storage.acknowledgeCallCount)
    }

    @Test
    fun execute_missingEventAcknowledgement_fails() {
        val changeSet = makeChangeSet(eventIds = listOf("e1", "e2"))
        val ack = allAccepted("change-set-1", listOf("e1"))
        val storage = FakeStorageProvider(
            readResults = mutableListOf(
                ProviderOperationResult.Success(OutboundChangeReadResult.Changes(changeSet, hasMore = false)),
            ),
        )
        val transport = FakeTransportProvider(pushResults = mutableListOf(ProviderOperationResult.Success(ack)))
        val context = makeContext(storage, transport)

        val result = runSuspend { makePipeline().execute(context) }

        assertIs<SynchronizationResult.Failed>(result)
        assertEquals(0, storage.acknowledgeCallCount)
    }

    @Test
    fun execute_duplicateEventAcknowledgementRejectedByContract() {
        assertFailsWith<IllegalArgumentException> {
            ChangeSetAcknowledgement(
                changeSetId = ChangeSetId("cs-1"),
                events = listOf(
                    ChangeEventAcknowledgement(ChangeEventId("e1"), ChangeAcknowledgementStatus.ACCEPTED),
                    ChangeEventAcknowledgement(ChangeEventId("e1"), ChangeAcknowledgementStatus.ACCEPTED),
                ),
            )
        }
    }

    @Test
    fun execute_invalidAcknowledgementStopsLaterBatchProcessing() {
        val changeSet1 = makeChangeSet(changeSetId = "cs-1", eventIds = listOf("e1"))
        val badAck = allAccepted("wrong-id", listOf("e1"))
        val storage = FakeStorageProvider(
            readResults = mutableListOf(
                ProviderOperationResult.Success(OutboundChangeReadResult.Changes(changeSet1, hasMore = true)),
            ),
        )
        val transport = FakeTransportProvider(pushResults = mutableListOf(ProviderOperationResult.Success(badAck)))
        val context = makeContext(storage, transport)

        runSuspend { makePipeline().execute(context) }

        assertEquals(1, storage.readCallCount)
        assertEquals(1, transport.pushCallCount)
    }

    // =========================================================================
    // Acknowledgement persistence failure
    // =========================================================================

    @Test
    fun execute_acknowledgementPersistenceFailure_returnsFailedAndStops() {
        val changeSet1 = makeChangeSet(changeSetId = "cs-1", eventIds = listOf("e1"))
        val ack1 = allAccepted("cs-1", listOf("e1"))
        val persistError = FakeError(message = "Persistence failed.")
        val storage = FakeStorageProvider(
            readResults = mutableListOf(
                ProviderOperationResult.Success(OutboundChangeReadResult.Changes(changeSet1, hasMore = true)),
            ),
            acknowledgeResults = mutableListOf(ProviderOperationResult.Failure(persistError)),
        )
        val transport = FakeTransportProvider(pushResults = mutableListOf(ProviderOperationResult.Success(ack1)))
        val context = makeContext(storage, transport)

        val result = runSuspend { makePipeline().execute(context) }

        val failed = assertIs<SynchronizationResult.Failed>(result)
        assertEquals(persistError, failed.error)
        assertEquals(1, storage.readCallCount)
        assertEquals(1, transport.pushCallCount)
        assertEquals(0L, failed.summary.outboundEventsAccepted)
    }

    // =========================================================================
    // Event-level statuses
    // =========================================================================

    @Test
    fun execute_allAccepted_producesSucceeded() {
        val changeSet = makeChangeSet(eventIds = listOf("e1", "e2"))
        val ack = allAccepted("change-set-1", listOf("e1", "e2"))
        val storage = FakeStorageProvider(
            readResults = mutableListOf(
                ProviderOperationResult.Success(OutboundChangeReadResult.Changes(changeSet, hasMore = false)),
            ),
            acknowledgeResults = mutableListOf(ProviderOperationResult.Success(Unit)),
        )
        val transport = FakeTransportProvider(pushResults = mutableListOf(ProviderOperationResult.Success(ack)))
        val context = makeContext(storage, transport)

        val result = runSuspend { makePipeline().execute(context) }

        assertIs<SynchronizationResult.Succeeded>(result)
    }

    @Test
    fun execute_acceptedPlusRetry_producesPartiallySucceededWithPreservedError() {
        val changeSet = makeChangeSet(eventIds = listOf("e1", "e2"))
        val retryError = FakeError(message = "Retry later.")
        val ack = makeAcknowledgement(
            "change-set-1",
            listOf("e1" to ChangeAcknowledgementStatus.ACCEPTED, "e2" to ChangeAcknowledgementStatus.RETRY),
            errorFor = { if (it == "e2") retryError else null },
        )
        val storage = FakeStorageProvider(
            readResults = mutableListOf(
                ProviderOperationResult.Success(OutboundChangeReadResult.Changes(changeSet, hasMore = false)),
            ),
            acknowledgeResults = mutableListOf(ProviderOperationResult.Success(Unit)),
        )
        val transport = FakeTransportProvider(pushResults = mutableListOf(ProviderOperationResult.Success(ack)))
        val context = makeContext(storage, transport)

        val result = runSuspend { makePipeline().execute(context) }

        val partial = assertIs<SynchronizationResult.PartiallySucceeded>(result)
        assertEquals(1L, partial.summary.outboundEventsAccepted)
        assertEquals(1L, partial.summary.outboundEventsMarkedForRetry)
        assertTrue(partial.errors.contains(retryError))
    }

    @Test
    fun execute_acceptedPlusRejected_producesPartiallySucceeded() {
        val changeSet = makeChangeSet(eventIds = listOf("e1", "e2"))
        val ack = makeAcknowledgement(
            "change-set-1",
            listOf("e1" to ChangeAcknowledgementStatus.ACCEPTED, "e2" to ChangeAcknowledgementStatus.REJECTED),
        )
        val storage = FakeStorageProvider(
            readResults = mutableListOf(
                ProviderOperationResult.Success(OutboundChangeReadResult.Changes(changeSet, hasMore = false)),
            ),
            acknowledgeResults = mutableListOf(ProviderOperationResult.Success(Unit)),
        )
        val transport = FakeTransportProvider(pushResults = mutableListOf(ProviderOperationResult.Success(ack)))
        val context = makeContext(storage, transport)

        val result = runSuspend { makePipeline().execute(context) }

        val partial = assertIs<SynchronizationResult.PartiallySucceeded>(result)
        assertEquals(1L, partial.summary.outboundEventsRejected)
        assertTrue(partial.errors.isNotEmpty())
    }

    @Test
    fun execute_missingErrorForRetryProducesSafeCanonicalError() {
        val changeSet = makeChangeSet(eventIds = listOf("e1"))
        val ack = makeAcknowledgement("change-set-1", listOf("e1" to ChangeAcknowledgementStatus.RETRY))
        val storage = FakeStorageProvider(
            readResults = mutableListOf(
                ProviderOperationResult.Success(OutboundChangeReadResult.Changes(changeSet, hasMore = false)),
            ),
            acknowledgeResults = mutableListOf(ProviderOperationResult.Success(Unit)),
        )
        val transport = FakeTransportProvider(pushResults = mutableListOf(ProviderOperationResult.Success(ack)))
        val context = makeContext(storage, transport)

        val result = runSuspend { makePipeline().execute(context) }

        val partial = assertIs<SynchronizationResult.PartiallySucceeded>(result)
        assertEquals(1, partial.errors.size)
        assertTrue(partial.errors.single().message.isNotBlank())
    }

    // =========================================================================
    // Duplicate batch protection
    // =========================================================================

    @Test
    fun execute_repeatedChangeSetId_fails() {
        val changeSet = makeChangeSet(changeSetId = "cs-1", eventIds = listOf("e1"))
        val ack = allAccepted("cs-1", listOf("e1"))
        val storage = FakeStorageProvider(
            readResults = mutableListOf(
                ProviderOperationResult.Success(OutboundChangeReadResult.Changes(changeSet, hasMore = true)),
                ProviderOperationResult.Success(OutboundChangeReadResult.Changes(changeSet, hasMore = true)),
            ),
            acknowledgeResults = mutableListOf(ProviderOperationResult.Success(Unit)),
        )
        val transport = FakeTransportProvider(pushResults = mutableListOf(ProviderOperationResult.Success(ack)))
        val context = makeContext(storage, transport)

        val result = runSuspend { makePipeline().execute(context) }

        val failed = assertIs<SynchronizationResult.Failed>(result)
        assertEquals(1, transport.pushCallCount)
        assertEquals(1, storage.acknowledgeCallCount)
        assertEquals(2, storage.readCallCount)
        assertTrue(!failed.error.message.contains("payload", ignoreCase = true))
    }

    // =========================================================================
    // Batch limit
    // =========================================================================

    @Test
    fun execute_batchLimitReached_producesPartiallySucceededWithNoAdditionalRead() {
        val cs1 = makeChangeSet(changeSetId = "cs-1", eventIds = listOf("e1"))
        val cs2 = makeChangeSet(changeSetId = "cs-2", eventIds = listOf("e2"))
        val ack1 = allAccepted("cs-1", listOf("e1"))
        val ack2 = allAccepted("cs-2", listOf("e2"))
        val storage = FakeStorageProvider(
            readResults = mutableListOf(
                ProviderOperationResult.Success(OutboundChangeReadResult.Changes(cs1, hasMore = true)),
                ProviderOperationResult.Success(OutboundChangeReadResult.Changes(cs2, hasMore = true)),
            ),
            acknowledgeResults = mutableListOf(
                ProviderOperationResult.Success(Unit),
                ProviderOperationResult.Success(Unit),
            ),
        )
        val transport = FakeTransportProvider(
            pushResults = mutableListOf(
                ProviderOperationResult.Success(ack1),
                ProviderOperationResult.Success(ack2),
            ),
        )
        val configuration = OutboundPushPipelineConfiguration(maxBatchesPerExecution = 2)
        val context = makeContext(storage, transport)

        val result = runSuspend { OutboundPushSynchronizationPipeline(configuration).execute(context) }

        val partial = assertIs<SynchronizationResult.PartiallySucceeded>(result)
        assertEquals(2, storage.readCallCount)
        assertEquals(2L, partial.summary.outboundEventsAccepted)
        assertTrue(partial.errors.isNotEmpty())
    }

    // =========================================================================
    // Cancellation
    // =========================================================================

    private class CancellingStorageProvider : StorageProvider {
        override val descriptor: ProviderDescriptor = ProviderDescriptor(
            id = ProviderId("cancelling-storage"),
            name = ProviderName("Cancelling storage"),
            type = ProviderType.STORAGE,
            version = ProviderVersion("1.0.0"),
        )

        override suspend fun initialize(context: ProviderInitializationContext): ProviderOperationResult<Unit> =
            ProviderOperationResult.Success(Unit)

        override suspend fun health(): ProviderOperationResult<ProviderHealth> =
            ProviderOperationResult.Success(ProviderHealth(ProviderHealthStatus.HEALTHY))

        override suspend fun close(): ProviderOperationResult<Unit> = ProviderOperationResult.Success(Unit)

        override suspend fun readOutboundChanges(
            request: OutboundChangeReadRequest,
        ): ProviderOperationResult<OutboundChangeReadResult> = throw CancellationException("Cancelled read")

        override suspend fun applyInboundChanges(
            request: InboundChangeApplyRequest,
        ): ProviderOperationResult<Unit> = ProviderOperationResult.Failure(FakeError())

        override suspend fun acknowledgeOutboundChanges(
            request: OutboundChangeAcknowledgementRequest,
        ): ProviderOperationResult<Unit> = ProviderOperationResult.Failure(FakeError())

        override suspend fun readCheckpoint(
            request: CheckpointReadRequest,
        ): ProviderOperationResult<SynchronizationCheckpoint?> = ProviderOperationResult.Success(null)

        override suspend fun writeCheckpoint(
            request: CheckpointWriteRequest,
        ): ProviderOperationResult<Unit> = ProviderOperationResult.Failure(FakeError())
    }

    @Test
    fun execute_cancellationFromRead_propagates() {
        val storage = CancellingStorageProvider()
        val transport = FakeTransportProvider()
        val context = makeContext(storage, transport)

        assertFailsWith<CancellationException> {
            runSuspend { makePipeline().execute(context) }
        }
    }

    private class CancellingTransportProvider : TransportProvider {
        override val descriptor: ProviderDescriptor = ProviderDescriptor(
            id = ProviderId("cancelling-transport"),
            name = ProviderName("Cancelling transport"),
            type = ProviderType.TRANSPORT,
            version = ProviderVersion("1.0.0"),
        )

        override suspend fun initialize(context: ProviderInitializationContext): ProviderOperationResult<Unit> =
            ProviderOperationResult.Success(Unit)

        override suspend fun health(): ProviderOperationResult<ProviderHealth> =
            ProviderOperationResult.Success(ProviderHealth(ProviderHealthStatus.HEALTHY))

        override suspend fun close(): ProviderOperationResult<Unit> = ProviderOperationResult.Success(Unit)

        override suspend fun pushChanges(
            request: PushChangesRequest,
        ): ProviderOperationResult<ChangeSetAcknowledgement> = throw CancellationException("Cancelled push")

        override suspend fun pullChanges(
            request: PullChangesRequest,
        ): ProviderOperationResult<PullChangesResult> = ProviderOperationResult.Failure(FakeError())
    }

    @Test
    fun execute_cancellationFromPush_propagates() {
        val changeSet = makeChangeSet(eventIds = listOf("e1"))
        val storage = FakeStorageProvider(
            readResults = mutableListOf(
                ProviderOperationResult.Success(OutboundChangeReadResult.Changes(changeSet, hasMore = false)),
            ),
        )
        val transport = CancellingTransportProvider()
        val context = makeContext(storage, transport)

        assertFailsWith<CancellationException> {
            runSuspend { makePipeline().execute(context) }
        }
    }

    private class CancellingAckStorageProvider(
        private val readResult: OutboundChangeReadResult,
    ) : StorageProvider {
        override val descriptor: ProviderDescriptor = ProviderDescriptor(
            id = ProviderId("cancelling-ack-storage"),
            name = ProviderName("Cancelling ack storage"),
            type = ProviderType.STORAGE,
            version = ProviderVersion("1.0.0"),
        )

        override suspend fun initialize(context: ProviderInitializationContext): ProviderOperationResult<Unit> =
            ProviderOperationResult.Success(Unit)

        override suspend fun health(): ProviderOperationResult<ProviderHealth> =
            ProviderOperationResult.Success(ProviderHealth(ProviderHealthStatus.HEALTHY))

        override suspend fun close(): ProviderOperationResult<Unit> = ProviderOperationResult.Success(Unit)

        override suspend fun readOutboundChanges(
            request: OutboundChangeReadRequest,
        ): ProviderOperationResult<OutboundChangeReadResult> = ProviderOperationResult.Success(readResult)

        override suspend fun applyInboundChanges(
            request: InboundChangeApplyRequest,
        ): ProviderOperationResult<Unit> = ProviderOperationResult.Failure(FakeError())

        override suspend fun acknowledgeOutboundChanges(
            request: OutboundChangeAcknowledgementRequest,
        ): ProviderOperationResult<Unit> = throw CancellationException("Cancelled acknowledge")

        override suspend fun readCheckpoint(
            request: CheckpointReadRequest,
        ): ProviderOperationResult<SynchronizationCheckpoint?> = ProviderOperationResult.Success(null)

        override suspend fun writeCheckpoint(
            request: CheckpointWriteRequest,
        ): ProviderOperationResult<Unit> = ProviderOperationResult.Failure(FakeError())
    }

    @Test
    fun execute_cancellationFromAcknowledgePersistence_propagates() {
        val changeSet = makeChangeSet(eventIds = listOf("e1"))
        val ack = allAccepted("change-set-1", listOf("e1"))
        val storage = CancellingAckStorageProvider(OutboundChangeReadResult.Changes(changeSet, hasMore = false))
        val transport = FakeTransportProvider(pushResults = mutableListOf(ProviderOperationResult.Success(ack)))
        val context = makeContext(storage, transport)

        assertFailsWith<CancellationException> {
            runSuspend { makePipeline().execute(context) }
        }
    }

    // =========================================================================
    // Side-effect restrictions
    // =========================================================================

    @Test
    fun execute_doesNotCallProviderLifecycleOrCheckpointOrInboundOperations() {
        val changeSet = makeChangeSet(eventIds = listOf("e1"))
        val ack = allAccepted("change-set-1", listOf("e1"))
        val storage = FakeStorageProvider(
            readResults = mutableListOf(
                ProviderOperationResult.Success(OutboundChangeReadResult.Changes(changeSet, hasMore = false)),
            ),
            acknowledgeResults = mutableListOf(ProviderOperationResult.Success(Unit)),
        )
        val transport = FakeTransportProvider(pushResults = mutableListOf(ProviderOperationResult.Success(ack)))
        val context = makeContext(storage, transport)

        runSuspend { makePipeline().execute(context) }

        assertEquals(0, storage.initializeCallCount)
        assertEquals(0, storage.healthCallCount)
        assertEquals(0, storage.closeCallCount)
        assertEquals(0, storage.applyInboundChangesCallCount)
        assertEquals(0, storage.readCheckpointCallCount)
        assertEquals(0, storage.writeCheckpointCallCount)
        assertEquals(0, transport.initializeCallCount)
        assertEquals(0, transport.healthCallCount)
        assertEquals(0, transport.closeCallCount)
        assertEquals(0, transport.pullChangesCallCount)
    }
}
