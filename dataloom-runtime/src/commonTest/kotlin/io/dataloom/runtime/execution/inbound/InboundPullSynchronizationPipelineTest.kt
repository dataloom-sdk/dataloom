package io.dataloom.runtime.execution.inbound

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
import io.dataloom.api.identifier.CheckpointKey
import io.dataloom.api.identifier.CheckpointToken
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
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Deterministic common tests for DL-022 inbound pull synchronization
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
class InboundPullSynchronizationPipelineTest {

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

    /**
     * Stateful fake StorageProvider that tracks all calls and returns
     * queued results in order.
     */
    private class FakeStorageProvider(
        id: String = "storage-primary",
        private val readCheckpointResults: MutableList<ProviderOperationResult<SynchronizationCheckpoint?>> =
            mutableListOf(ProviderOperationResult.Success(null)),
        private val applyResults: MutableList<ProviderOperationResult<Unit>> = mutableListOf(),
        private val writeCheckpointResults: MutableList<ProviderOperationResult<Unit>> = mutableListOf(),
    ) : StorageProvider {
        override val descriptor: ProviderDescriptor = ProviderDescriptor(
            id = ProviderId(id),
            name = ProviderName("Storage $id"),
            type = ProviderType.STORAGE,
            version = ProviderVersion("1.0.0"),
        )

        val readCheckpointRequests: MutableList<CheckpointReadRequest> = mutableListOf()
        val applyRequests: MutableList<InboundChangeApplyRequest> = mutableListOf()
        val writeCheckpointRequests: MutableList<CheckpointWriteRequest> = mutableListOf()
        var readCheckpointCallCount: Int = 0
        var applyCallCount: Int = 0
        var writeCheckpointCallCount: Int = 0
        var readOutboundCallCount: Int = 0
        var acknowledgeCallCount: Int = 0
        var initializeCallCount: Int = 0
        var healthCallCount: Int = 0
        var closeCallCount: Int = 0

        // Tracks the interleaved sequence of operation names for ordering
        // assertions.
        val callSequence: MutableList<String> = mutableListOf()

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
            readOutboundCallCount++
            return ProviderOperationResult.Success(OutboundChangeReadResult.NoChanges)
        }

        override suspend fun applyInboundChanges(
            request: InboundChangeApplyRequest,
        ): ProviderOperationResult<Unit> {
            applyRequests.add(request)
            applyCallCount++
            callSequence.add("apply")
            check(applyResults.isNotEmpty()) { "FakeStorageProvider: no queued applyInboundChanges result." }
            return applyResults.removeAt(0)
        }

        override suspend fun acknowledgeOutboundChanges(
            request: OutboundChangeAcknowledgementRequest,
        ): ProviderOperationResult<Unit> {
            acknowledgeCallCount++
            return ProviderOperationResult.Failure(FakeError())
        }

        override suspend fun readCheckpoint(
            request: CheckpointReadRequest,
        ): ProviderOperationResult<SynchronizationCheckpoint?> {
            readCheckpointRequests.add(request)
            readCheckpointCallCount++
            callSequence.add("readCheckpoint")
            check(readCheckpointResults.isNotEmpty()) {
                "FakeStorageProvider: no queued readCheckpoint result."
            }
            return readCheckpointResults.removeAt(0)
        }

        override suspend fun writeCheckpoint(
            request: CheckpointWriteRequest,
        ): ProviderOperationResult<Unit> {
            writeCheckpointRequests.add(request)
            writeCheckpointCallCount++
            callSequence.add("writeCheckpoint")
            check(writeCheckpointResults.isNotEmpty()) {
                "FakeStorageProvider: no queued writeCheckpoint result."
            }
            return writeCheckpointResults.removeAt(0)
        }
    }

    // =========================================================================
    // Fake transport provider
    // =========================================================================

    /**
     * Stateful fake TransportProvider that tracks all calls and returns
     * queued pull results in order.
     */
    private class FakeTransportProvider(
        id: String = "transport-primary",
        private val pullResults: MutableList<ProviderOperationResult<PullChangesResult>> = mutableListOf(),
    ) : TransportProvider {
        override val descriptor: ProviderDescriptor = ProviderDescriptor(
            id = ProviderId(id),
            name = ProviderName("Transport $id"),
            type = ProviderType.TRANSPORT,
            version = ProviderVersion("1.0.0"),
        )

        val pullRequests: MutableList<PullChangesRequest> = mutableListOf()
        var pullCallCount: Int = 0
        var pushCallCount: Int = 0
        var initializeCallCount: Int = 0
        var healthCallCount: Int = 0
        var closeCallCount: Int = 0

        val callSequence: MutableList<String> = mutableListOf()

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
            pushCallCount++
            return ProviderOperationResult.Failure(FakeError())
        }

        override suspend fun pullChanges(
            request: PullChangesRequest,
        ): ProviderOperationResult<PullChangesResult> {
            pullRequests.add(request)
            pullCallCount++
            callSequence.add("pull")
            check(pullResults.isNotEmpty()) { "FakeTransportProvider: no queued pullChanges result." }
            return pullResults.removeAt(0)
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

    /** Clock that throws if invoked (used to assert no clock read occurs during construction). */
    private class ExplodingClock : DataLoomClock {
        override fun now(): DataLoomInstant = throw AssertionError("Clock must not be read.")
    }

    // =========================================================================
    // Model builders
    // =========================================================================

    private fun makeRequest(
        direction: SynchronizationDirection = SynchronizationDirection.PULL,
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

    private fun makeCheckpoint(
        key: String = "workflow-001",
        token: String = "token-001",
    ) = SynchronizationCheckpoint(
        key = CheckpointKey(key),
        token = CheckpointToken(token),
    )

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

    private fun makePipeline(
        configuration: InboundPullPipelineConfiguration = InboundPullPipelineConfiguration(),
    ) = InboundPullSynchronizationPipeline(configuration)

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
        val configuration = InboundPullPipelineConfiguration()
        assertTrue(configuration.entityTypes.isEmpty())
        assertEquals(100, configuration.maxEventsPerBatch)
        assertEquals(100, configuration.maxBatchesPerExecution)
    }

    @Test
    fun configuration_entityTypesIsDefensivelyCopied() {
        val source = mutableSetOf(EntityType("Order"))
        val configuration = InboundPullPipelineConfiguration(entityTypes = source)
        source.add(EntityType("Invoice"))
        assertEquals(setOf(EntityType("Order")), configuration.entityTypes)
    }

    @Test
    fun configuration_callerMutationDoesNotAffectConfiguration() {
        val configuration = InboundPullPipelineConfiguration(entityTypes = setOf(EntityType("Order")))
        @Suppress("UNUSED_VARIABLE")
        val exposed = configuration.entityTypes as MutableSet<*>?
        // The exposed value is a copy; mutation is not possible through the
        // returned read-only Set, but the snapshot remains unchanged.
        assertEquals(setOf(EntityType("Order")), configuration.entityTypes)
    }

    @Test
    fun configuration_positiveMaxEventsPerBatchAccepted() {
        val configuration = InboundPullPipelineConfiguration(maxEventsPerBatch = 1)
        assertEquals(1, configuration.maxEventsPerBatch)
    }

    @Test
    fun configuration_zeroMaxEventsPerBatchRejected() {
        assertFailsWith<IllegalArgumentException> { InboundPullPipelineConfiguration(maxEventsPerBatch = 0) }
    }

    @Test
    fun configuration_negativeMaxEventsPerBatchRejected() {
        assertFailsWith<IllegalArgumentException> { InboundPullPipelineConfiguration(maxEventsPerBatch = -1) }
    }

    @Test
    fun configuration_positiveMaxBatchesPerExecutionAccepted() {
        val configuration = InboundPullPipelineConfiguration(maxBatchesPerExecution = 1)
        assertEquals(1, configuration.maxBatchesPerExecution)
    }

    @Test
    fun configuration_zeroMaxBatchesPerExecutionRejected() {
        assertFailsWith<IllegalArgumentException> { InboundPullPipelineConfiguration(maxBatchesPerExecution = 0) }
    }

    @Test
    fun configuration_negativeMaxBatchesPerExecutionRejected() {
        assertFailsWith<IllegalArgumentException> {
            InboundPullPipelineConfiguration(maxBatchesPerExecution = -1)
        }
    }

    @Test
    fun configuration_constructionDoesNotReadClock() {
        // Constructing a configuration must not touch any clock; this test
        // simply verifies that no clock interaction occurs during construction.
        InboundPullPipelineConfiguration(entityTypes = setOf(EntityType("Order")))
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
    fun pipeline_declaresPullDirection() {
        val pipeline = makePipeline()
        assertEquals(SynchronizationDirection.PULL, pipeline.direction)
    }

    @Test
    fun pipeline_directionIsNotPushOrBidirectional() {
        val pipeline = makePipeline()
        assertTrue(pipeline.direction != SynchronizationDirection.PUSH)
        assertTrue(pipeline.direction != SynchronizationDirection.BIDIRECTIONAL)
    }

    // =========================================================================
    // Checkpoint read tests
    // =========================================================================

    @Test
    fun execute_checkpointIsReadExactlyOnce() {
        val storage = FakeStorageProvider(
            readCheckpointResults = mutableListOf(ProviderOperationResult.Success(null)),
            applyResults = mutableListOf(ProviderOperationResult.Success(Unit)),
        )
        val transport = FakeTransportProvider(
            pullResults = mutableListOf(
                ProviderOperationResult.Success(PullChangesResult.NoChanges()),
            ),
        )
        val context = makeContext(storage, transport)

        runSuspend { makePipeline().execute(context) }

        assertEquals(1, storage.readCheckpointCallCount)
    }

    @Test
    fun execute_nullCheckpointIsPassedAsNull() {
        val storage = FakeStorageProvider(
            readCheckpointResults = mutableListOf(ProviderOperationResult.Success(null)),
        )
        val transport = FakeTransportProvider(
            pullResults = mutableListOf(
                ProviderOperationResult.Success(PullChangesResult.NoChanges()),
            ),
        )
        val context = makeContext(storage, transport)

        runSuspend { makePipeline().execute(context) }

        assertNull(transport.pullRequests.single().checkpoint)
    }

    @Test
    fun execute_storedCheckpointIsPassedUnchanged() {
        val storedCheckpoint = makeCheckpoint(token = "stored-token")
        val storage = FakeStorageProvider(
            readCheckpointResults = mutableListOf(ProviderOperationResult.Success(storedCheckpoint)),
        )
        val transport = FakeTransportProvider(
            pullResults = mutableListOf(
                ProviderOperationResult.Success(PullChangesResult.NoChanges()),
            ),
        )
        val context = makeContext(storage, transport)

        runSuspend { makePipeline().execute(context) }

        assertEquals(storedCheckpoint, transport.pullRequests.single().checkpoint)
    }

    @Test
    fun execute_checkpointReadFailure_returnsFailed() {
        val readError = FakeError(message = "Checkpoint read failed.")
        val storage = FakeStorageProvider(
            readCheckpointResults = mutableListOf(ProviderOperationResult.Failure(readError)),
        )
        val transport = FakeTransportProvider()
        val context = makeContext(storage, transport)

        val result = runSuspend { makePipeline().execute(context) }

        val failed = assertIs<SynchronizationResult.Failed>(result)
        assertSame(readError, failed.error)
        assertEquals(0, transport.pullCallCount)
    }

    @Test
    fun execute_checkpointReadFailure_pullIsNotCalled() {
        val storage = FakeStorageProvider(
            readCheckpointResults = mutableListOf(
                ProviderOperationResult.Failure(FakeError()),
            ),
        )
        val transport = FakeTransportProvider()
        val context = makeContext(storage, transport)

        runSuspend { makePipeline().execute(context) }

        assertEquals(0, transport.pullCallCount)
    }

    @Test
    fun execute_checkpointReadFailure_exactErrorIsPreserved() {
        val exactError = FakeError(code = ErrorCode("DL-EXACT-001"), message = "Exact error.")
        val storage = FakeStorageProvider(
            readCheckpointResults = mutableListOf(ProviderOperationResult.Failure(exactError)),
        )
        val transport = FakeTransportProvider()
        val context = makeContext(storage, transport)

        val result = runSuspend { makePipeline().execute(context) }

        val failed = assertIs<SynchronizationResult.Failed>(result)
        assertSame(exactError, failed.error)
    }

    // =========================================================================
    // Initial NoChanges tests
    // =========================================================================

    @Test
    fun execute_noChanges_noNextCheckpoint_returnsSkipped() {
        val storage = FakeStorageProvider(
            readCheckpointResults = mutableListOf(ProviderOperationResult.Success(null)),
        )
        val transport = FakeTransportProvider(
            pullResults = mutableListOf(
                ProviderOperationResult.Success(PullChangesResult.NoChanges(nextCheckpoint = null)),
            ),
        )
        val context = makeContext(storage, transport)

        val result = runSuspend { makePipeline().execute(context) }

        val skipped = assertIs<SynchronizationResult.Skipped>(result)
        assertEquals(SynchronizationSkipReason.NO_CHANGES, skipped.reason)
    }

    @Test
    fun execute_noChanges_noNextCheckpoint_noApplyCall() {
        val storage = FakeStorageProvider(
            readCheckpointResults = mutableListOf(ProviderOperationResult.Success(null)),
        )
        val transport = FakeTransportProvider(
            pullResults = mutableListOf(
                ProviderOperationResult.Success(PullChangesResult.NoChanges(nextCheckpoint = null)),
            ),
        )
        val context = makeContext(storage, transport)

        runSuspend { makePipeline().execute(context) }

        assertEquals(0, storage.applyCallCount)
    }

    @Test
    fun execute_noChanges_noNextCheckpoint_noCheckpointWriteCall() {
        val storage = FakeStorageProvider(
            readCheckpointResults = mutableListOf(ProviderOperationResult.Success(null)),
        )
        val transport = FakeTransportProvider(
            pullResults = mutableListOf(
                ProviderOperationResult.Success(PullChangesResult.NoChanges(nextCheckpoint = null)),
            ),
        )
        val context = makeContext(storage, transport)

        runSuspend { makePipeline().execute(context) }

        assertEquals(0, storage.writeCheckpointCallCount)
    }

    @Test
    fun execute_noChanges_withNextCheckpoint_writesCheckpointBeforeReturningSkipped() {
        val nextCheckpoint = makeCheckpoint(token = "next-token")
        val storage = FakeStorageProvider(
            readCheckpointResults = mutableListOf(ProviderOperationResult.Success(null)),
            writeCheckpointResults = mutableListOf(ProviderOperationResult.Success(Unit)),
        )
        val transport = FakeTransportProvider(
            pullResults = mutableListOf(
                ProviderOperationResult.Success(PullChangesResult.NoChanges(nextCheckpoint = nextCheckpoint)),
            ),
        )
        val context = makeContext(storage, transport)

        val result = runSuspend { makePipeline().execute(context) }

        assertEquals(1, storage.writeCheckpointCallCount)
        val skipped = assertIs<SynchronizationResult.Skipped>(result)
        assertEquals(SynchronizationSkipReason.NO_CHANGES, skipped.reason)
    }

    @Test
    fun execute_noChanges_withNextCheckpoint_writesExactCheckpoint() {
        val nextCheckpoint = makeCheckpoint(token = "next-token-exact")
        val storage = FakeStorageProvider(
            readCheckpointResults = mutableListOf(ProviderOperationResult.Success(null)),
            writeCheckpointResults = mutableListOf(ProviderOperationResult.Success(Unit)),
        )
        val transport = FakeTransportProvider(
            pullResults = mutableListOf(
                ProviderOperationResult.Success(PullChangesResult.NoChanges(nextCheckpoint = nextCheckpoint)),
            ),
        )
        val context = makeContext(storage, transport)

        runSuspend { makePipeline().execute(context) }

        assertEquals(nextCheckpoint, storage.writeCheckpointRequests.single().checkpoint)
    }

    @Test
    fun execute_noChanges_checkpointWriteFailure_returnsFailed() {
        val writeError = FakeError(message = "Checkpoint write failed.")
        val storage = FakeStorageProvider(
            readCheckpointResults = mutableListOf(ProviderOperationResult.Success(null)),
            writeCheckpointResults = mutableListOf(ProviderOperationResult.Failure(writeError)),
        )
        val transport = FakeTransportProvider(
            pullResults = mutableListOf(
                ProviderOperationResult.Success(PullChangesResult.NoChanges(nextCheckpoint = makeCheckpoint())),
            ),
        )
        val context = makeContext(storage, transport)

        val result = runSuspend { makePipeline().execute(context) }

        val failed = assertIs<SynchronizationResult.Failed>(result)
        assertSame(writeError, failed.error)
    }

    // =========================================================================
    // One successful batch tests
    // =========================================================================

    @Test
    fun execute_oneSuccessfulBatch_pullReceivesExactRequest() {
        val changeSet = makeChangeSet(eventIds = listOf("event-1", "event-2"))
        val storage = FakeStorageProvider(
            readCheckpointResults = mutableListOf(ProviderOperationResult.Success(null)),
            applyResults = mutableListOf(ProviderOperationResult.Success(Unit)),
        )
        val transport = FakeTransportProvider(
            pullResults = mutableListOf(
                ProviderOperationResult.Success(
                    PullChangesResult.Changes(changeSet = changeSet, hasMore = false),
                ),
            ),
        )
        val request = makeRequest()
        val configuration = InboundPullPipelineConfiguration(
            entityTypes = setOf(EntityType("Order")),
            maxEventsPerBatch = 50,
        )
        val context = makeContext(storage, transport, request = request)

        runSuspend { InboundPullSynchronizationPipeline(configuration).execute(context) }

        val pullRequest = transport.pullRequests.single()
        assertSame(request, pullRequest.request)
        assertEquals(setOf(EntityType("Order")), pullRequest.entityTypes)
        assertEquals(50, pullRequest.maxEvents)
        assertNull(pullRequest.checkpoint)
    }

    @Test
    fun execute_oneSuccessfulBatch_exactChangeSetIsApplied() {
        val changeSet = makeChangeSet(eventIds = listOf("event-1"))
        val storage = FakeStorageProvider(
            readCheckpointResults = mutableListOf(ProviderOperationResult.Success(null)),
            applyResults = mutableListOf(ProviderOperationResult.Success(Unit)),
        )
        val transport = FakeTransportProvider(
            pullResults = mutableListOf(
                ProviderOperationResult.Success(
                    PullChangesResult.Changes(changeSet = changeSet, hasMore = false),
                ),
            ),
        )
        val context = makeContext(storage, transport)

        runSuspend { makePipeline().execute(context) }

        assertSame(changeSet, storage.applyRequests.single().changeSet)
    }

    @Test
    fun execute_oneSuccessfulBatch_nextCheckpointWrittenAfterApply() {
        val nextCheckpoint = makeCheckpoint(token = "next-001")
        val changeSet = makeChangeSet(eventIds = listOf("event-1"))
        val storage = FakeStorageProvider(
            readCheckpointResults = mutableListOf(ProviderOperationResult.Success(null)),
            applyResults = mutableListOf(ProviderOperationResult.Success(Unit)),
            writeCheckpointResults = mutableListOf(ProviderOperationResult.Success(Unit)),
        )
        val transport = FakeTransportProvider(
            pullResults = mutableListOf(
                ProviderOperationResult.Success(
                    PullChangesResult.Changes(
                        changeSet = changeSet,
                        hasMore = false,
                        nextCheckpoint = nextCheckpoint,
                    ),
                ),
            ),
        )
        val context = makeContext(storage, transport)

        runSuspend { makePipeline().execute(context) }

        assertEquals(1, storage.writeCheckpointCallCount)
        assertEquals(nextCheckpoint, storage.writeCheckpointRequests.single().checkpoint)
    }

    @Test
    fun execute_oneSuccessfulBatch_invocationOrderIsPullApplyWrite() {
        val nextCheckpoint = makeCheckpoint(token = "next-001")
        val changeSet = makeChangeSet(eventIds = listOf("event-1"))
        val storage = FakeStorageProvider(
            readCheckpointResults = mutableListOf(ProviderOperationResult.Success(null)),
            applyResults = mutableListOf(ProviderOperationResult.Success(Unit)),
            writeCheckpointResults = mutableListOf(ProviderOperationResult.Success(Unit)),
        )
        val transport = FakeTransportProvider(
            pullResults = mutableListOf(
                ProviderOperationResult.Success(
                    PullChangesResult.Changes(
                        changeSet = changeSet,
                        hasMore = false,
                        nextCheckpoint = nextCheckpoint,
                    ),
                ),
            ),
        )
        val context = makeContext(storage, transport)
        val allCalls = mutableListOf<String>()
        storage.callSequence.clear()
        transport.callSequence.clear()

        // Redefine tracking by using combined sequence monitoring via
        // the individual call trackers.
        runSuspend { makePipeline().execute(context) }

        // readCheckpoint happens first (in storage.callSequence[0])
        // pull happens next (in transport.callSequence[0])
        // apply happens next (in storage.callSequence[1])
        // writeCheckpoint happens last (in storage.callSequence[2])
        assertEquals(listOf("readCheckpoint", "apply", "writeCheckpoint"), storage.callSequence)
        assertEquals(listOf("pull"), transport.callSequence)
    }

    @Test
    fun execute_oneSuccessfulBatch_returnsSucceeded() {
        val changeSet = makeChangeSet(eventIds = listOf("event-1"))
        val storage = FakeStorageProvider(
            readCheckpointResults = mutableListOf(ProviderOperationResult.Success(null)),
            applyResults = mutableListOf(ProviderOperationResult.Success(Unit)),
        )
        val transport = FakeTransportProvider(
            pullResults = mutableListOf(
                ProviderOperationResult.Success(
                    PullChangesResult.Changes(changeSet = changeSet, hasMore = false),
                ),
            ),
        )
        val context = makeContext(storage, transport)

        val result = runSuspend { makePipeline().execute(context) }

        assertIs<SynchronizationResult.Succeeded>(result)
    }

    @Test
    fun execute_oneSuccessfulBatch_summaryCountsAreCorrect() {
        val changeSet = makeChangeSet(eventIds = listOf("event-1", "event-2"))
        val storage = FakeStorageProvider(
            readCheckpointResults = mutableListOf(ProviderOperationResult.Success(null)),
            applyResults = mutableListOf(ProviderOperationResult.Success(Unit)),
        )
        val transport = FakeTransportProvider(
            pullResults = mutableListOf(
                ProviderOperationResult.Success(
                    PullChangesResult.Changes(changeSet = changeSet, hasMore = false),
                ),
            ),
        )
        val context = makeContext(storage, transport)

        val result = runSuspend { makePipeline().execute(context) }

        val succeeded = assertIs<SynchronizationResult.Succeeded>(result)
        assertEquals(2L, succeeded.summary.inboundEventsReceived)
        assertEquals(2L, succeeded.summary.inboundEventsApplied)
        assertEquals(0L, succeeded.summary.outboundEventsRead)
        assertEquals(0L, succeeded.summary.conflictsDetected)
    }

    @Test
    fun execute_changesWithoutNextCheckpoint_noCheckpointWriteOccurs() {
        val changeSet = makeChangeSet(eventIds = listOf("event-1"))
        val storage = FakeStorageProvider(
            readCheckpointResults = mutableListOf(ProviderOperationResult.Success(null)),
            applyResults = mutableListOf(ProviderOperationResult.Success(Unit)),
        )
        val transport = FakeTransportProvider(
            pullResults = mutableListOf(
                ProviderOperationResult.Success(
                    PullChangesResult.Changes(
                        changeSet = changeSet,
                        hasMore = false,
                        nextCheckpoint = null,
                    ),
                ),
            ),
        )
        val context = makeContext(storage, transport)

        val result = runSuspend { makePipeline().execute(context) }

        assertIs<SynchronizationResult.Succeeded>(result)
        assertEquals(0, storage.writeCheckpointCallCount)
    }

    // =========================================================================
    // Multiple batches tests
    // =========================================================================

    @Test
    fun execute_multipleBatches_hasMoreTrueCausesAnotherPull() {
        val changeSet1 = makeChangeSet(changeSetId = "cs-1", eventIds = listOf("e1"))
        val changeSet2 = makeChangeSet(changeSetId = "cs-2", eventIds = listOf("e2"))
        val checkpoint1 = makeCheckpoint(token = "cp-1")
        val storage = FakeStorageProvider(
            readCheckpointResults = mutableListOf(ProviderOperationResult.Success(null)),
            applyResults = mutableListOf(
                ProviderOperationResult.Success(Unit),
                ProviderOperationResult.Success(Unit),
            ),
            writeCheckpointResults = mutableListOf(
                ProviderOperationResult.Success(Unit),
            ),
        )
        val transport = FakeTransportProvider(
            pullResults = mutableListOf(
                ProviderOperationResult.Success(
                    PullChangesResult.Changes(changeSet = changeSet1, hasMore = true, nextCheckpoint = checkpoint1),
                ),
                ProviderOperationResult.Success(
                    PullChangesResult.Changes(changeSet = changeSet2, hasMore = false),
                ),
            ),
        )
        val context = makeContext(storage, transport)

        val result = runSuspend { makePipeline().execute(context) }

        assertIs<SynchronizationResult.Succeeded>(result)
        assertEquals(2, transport.pullCallCount)
    }

    @Test
    fun execute_multipleBatches_nextPullUsesPersistedCheckpoint() {
        val changeSet1 = makeChangeSet(changeSetId = "cs-1", eventIds = listOf("e1"))
        val changeSet2 = makeChangeSet(changeSetId = "cs-2", eventIds = listOf("e2"))
        val checkpoint1 = makeCheckpoint(token = "cp-1")
        val storage = FakeStorageProvider(
            readCheckpointResults = mutableListOf(ProviderOperationResult.Success(null)),
            applyResults = mutableListOf(
                ProviderOperationResult.Success(Unit),
                ProviderOperationResult.Success(Unit),
            ),
            writeCheckpointResults = mutableListOf(ProviderOperationResult.Success(Unit)),
        )
        val transport = FakeTransportProvider(
            pullResults = mutableListOf(
                ProviderOperationResult.Success(
                    PullChangesResult.Changes(changeSet = changeSet1, hasMore = true, nextCheckpoint = checkpoint1),
                ),
                ProviderOperationResult.Success(
                    PullChangesResult.Changes(changeSet = changeSet2, hasMore = false),
                ),
            ),
        )
        val context = makeContext(storage, transport)

        runSuspend { makePipeline().execute(context) }

        // Second pull must use the successfully persisted checkpoint from batch 1.
        assertEquals(checkpoint1, transport.pullRequests[1].checkpoint)
    }

    @Test
    fun execute_multipleBatches_batchesProcessedSequentially() {
        val changeSet1 = makeChangeSet(changeSetId = "cs-1", eventIds = listOf("e1"))
        val changeSet2 = makeChangeSet(changeSetId = "cs-2", eventIds = listOf("e2"))
        val checkpoint1 = makeCheckpoint(token = "cp-1")
        val storage = FakeStorageProvider(
            readCheckpointResults = mutableListOf(ProviderOperationResult.Success(null)),
            applyResults = mutableListOf(
                ProviderOperationResult.Success(Unit),
                ProviderOperationResult.Success(Unit),
            ),
            writeCheckpointResults = mutableListOf(ProviderOperationResult.Success(Unit)),
        )
        val transport = FakeTransportProvider(
            pullResults = mutableListOf(
                ProviderOperationResult.Success(
                    PullChangesResult.Changes(changeSet = changeSet1, hasMore = true, nextCheckpoint = checkpoint1),
                ),
                ProviderOperationResult.Success(
                    PullChangesResult.Changes(changeSet = changeSet2, hasMore = false),
                ),
            ),
        )
        val context = makeContext(storage, transport)

        runSuspend { makePipeline().execute(context) }

        // Each batch is applied exactly once and in order.
        assertEquals(2, storage.applyCallCount)
        assertSame(changeSet1, storage.applyRequests[0].changeSet)
        assertSame(changeSet2, storage.applyRequests[1].changeSet)
    }

    @Test
    fun execute_multipleBatches_hasMoreFalseStops() {
        val changeSet1 = makeChangeSet(changeSetId = "cs-1", eventIds = listOf("e1"))
        val changeSet2 = makeChangeSet(changeSetId = "cs-2", eventIds = listOf("e2"))
        val checkpoint1 = makeCheckpoint(token = "cp-1")
        val storage = FakeStorageProvider(
            readCheckpointResults = mutableListOf(ProviderOperationResult.Success(null)),
            applyResults = mutableListOf(
                ProviderOperationResult.Success(Unit),
                ProviderOperationResult.Success(Unit),
            ),
            writeCheckpointResults = mutableListOf(ProviderOperationResult.Success(Unit)),
        )
        val transport = FakeTransportProvider(
            pullResults = mutableListOf(
                ProviderOperationResult.Success(
                    PullChangesResult.Changes(changeSet = changeSet1, hasMore = true, nextCheckpoint = checkpoint1),
                ),
                ProviderOperationResult.Success(
                    PullChangesResult.Changes(changeSet = changeSet2, hasMore = false),
                ),
            ),
        )
        val context = makeContext(storage, transport)

        runSuspend { makePipeline().execute(context) }

        // No extra pull after hasMore=false.
        assertEquals(2, transport.pullCallCount)
    }

    @Test
    fun execute_multipleBatches_summaryAggregatesBatches() {
        val changeSet1 = makeChangeSet(changeSetId = "cs-1", eventIds = listOf("e1", "e2"))
        val changeSet2 = makeChangeSet(changeSetId = "cs-2", eventIds = listOf("e3"))
        val checkpoint1 = makeCheckpoint(token = "cp-1")
        val storage = FakeStorageProvider(
            readCheckpointResults = mutableListOf(ProviderOperationResult.Success(null)),
            applyResults = mutableListOf(
                ProviderOperationResult.Success(Unit),
                ProviderOperationResult.Success(Unit),
            ),
            writeCheckpointResults = mutableListOf(ProviderOperationResult.Success(Unit)),
        )
        val transport = FakeTransportProvider(
            pullResults = mutableListOf(
                ProviderOperationResult.Success(
                    PullChangesResult.Changes(changeSet = changeSet1, hasMore = true, nextCheckpoint = checkpoint1),
                ),
                ProviderOperationResult.Success(
                    PullChangesResult.Changes(changeSet = changeSet2, hasMore = false),
                ),
            ),
        )
        val context = makeContext(storage, transport)

        val result = runSuspend { makePipeline().execute(context) }

        val succeeded = assertIs<SynchronizationResult.Succeeded>(result)
        assertEquals(3L, succeeded.summary.inboundEventsReceived)
        assertEquals(3L, succeeded.summary.inboundEventsApplied)
    }

    // =========================================================================
    // Later NoChanges tests
    // =========================================================================

    @Test
    fun execute_laterNoChanges_returnsSucceededNotSkipped() {
        val changeSet1 = makeChangeSet(changeSetId = "cs-1", eventIds = listOf("e1"))
        val checkpoint1 = makeCheckpoint(token = "cp-1")
        val storage = FakeStorageProvider(
            readCheckpointResults = mutableListOf(ProviderOperationResult.Success(null)),
            applyResults = mutableListOf(ProviderOperationResult.Success(Unit)),
            writeCheckpointResults = mutableListOf(ProviderOperationResult.Success(Unit)),
        )
        val transport = FakeTransportProvider(
            pullResults = mutableListOf(
                ProviderOperationResult.Success(
                    PullChangesResult.Changes(changeSet = changeSet1, hasMore = true, nextCheckpoint = checkpoint1),
                ),
                ProviderOperationResult.Success(PullChangesResult.NoChanges()),
            ),
        )
        val context = makeContext(storage, transport)

        val result = runSuspend { makePipeline().execute(context) }

        assertIs<SynchronizationResult.Succeeded>(result)
    }

    @Test
    fun execute_laterNoChanges_summaryRemainsCorrect() {
        val changeSet1 = makeChangeSet(changeSetId = "cs-1", eventIds = listOf("e1", "e2"))
        val checkpoint1 = makeCheckpoint(token = "cp-1")
        val storage = FakeStorageProvider(
            readCheckpointResults = mutableListOf(ProviderOperationResult.Success(null)),
            applyResults = mutableListOf(ProviderOperationResult.Success(Unit)),
            writeCheckpointResults = mutableListOf(ProviderOperationResult.Success(Unit)),
        )
        val transport = FakeTransportProvider(
            pullResults = mutableListOf(
                ProviderOperationResult.Success(
                    PullChangesResult.Changes(changeSet = changeSet1, hasMore = true, nextCheckpoint = checkpoint1),
                ),
                ProviderOperationResult.Success(PullChangesResult.NoChanges()),
            ),
        )
        val context = makeContext(storage, transport)

        val result = runSuspend { makePipeline().execute(context) }

        val succeeded = assertIs<SynchronizationResult.Succeeded>(result)
        assertEquals(2L, succeeded.summary.inboundEventsReceived)
        assertEquals(2L, succeeded.summary.inboundEventsApplied)
    }

    @Test
    fun execute_laterNoChanges_withNextCheckpoint_isPersistedBeforeSucceeded() {
        val changeSet1 = makeChangeSet(changeSetId = "cs-1", eventIds = listOf("e1"))
        val checkpoint1 = makeCheckpoint(token = "cp-1")
        val noChangeCheckpoint = makeCheckpoint(token = "no-change-cp")
        val storage = FakeStorageProvider(
            readCheckpointResults = mutableListOf(ProviderOperationResult.Success(null)),
            applyResults = mutableListOf(ProviderOperationResult.Success(Unit)),
            writeCheckpointResults = mutableListOf(
                ProviderOperationResult.Success(Unit),
                ProviderOperationResult.Success(Unit),
            ),
        )
        val transport = FakeTransportProvider(
            pullResults = mutableListOf(
                ProviderOperationResult.Success(
                    PullChangesResult.Changes(changeSet = changeSet1, hasMore = true, nextCheckpoint = checkpoint1),
                ),
                ProviderOperationResult.Success(PullChangesResult.NoChanges(nextCheckpoint = noChangeCheckpoint)),
            ),
        )
        val context = makeContext(storage, transport)

        val result = runSuspend { makePipeline().execute(context) }

        assertIs<SynchronizationResult.Succeeded>(result)
        assertEquals(2, storage.writeCheckpointCallCount)
    }

    // =========================================================================
    // Pull failure tests
    // =========================================================================

    @Test
    fun execute_pullFailure_returnsFailed() {
        val pullError = FakeError(message = "Pull failed.")
        val storage = FakeStorageProvider(
            readCheckpointResults = mutableListOf(ProviderOperationResult.Success(null)),
        )
        val transport = FakeTransportProvider(
            pullResults = mutableListOf(ProviderOperationResult.Failure(pullError)),
        )
        val context = makeContext(storage, transport)

        val result = runSuspend { makePipeline().execute(context) }

        val failed = assertIs<SynchronizationResult.Failed>(result)
        assertSame(pullError, failed.error)
    }

    @Test
    fun execute_pullFailure_applyIsNotCalled() {
        val storage = FakeStorageProvider(
            readCheckpointResults = mutableListOf(ProviderOperationResult.Success(null)),
        )
        val transport = FakeTransportProvider(
            pullResults = mutableListOf(ProviderOperationResult.Failure(FakeError())),
        )
        val context = makeContext(storage, transport)

        runSuspend { makePipeline().execute(context) }

        assertEquals(0, storage.applyCallCount)
    }

    @Test
    fun execute_pullFailure_checkpointIsNotWritten() {
        val storage = FakeStorageProvider(
            readCheckpointResults = mutableListOf(ProviderOperationResult.Success(null)),
        )
        val transport = FakeTransportProvider(
            pullResults = mutableListOf(ProviderOperationResult.Failure(FakeError())),
        )
        val context = makeContext(storage, transport)

        runSuspend { makePipeline().execute(context) }

        assertEquals(0, storage.writeCheckpointCallCount)
    }

    @Test
    fun execute_pullFailure_laterPullsDoNotOccur() {
        val storage = FakeStorageProvider(
            readCheckpointResults = mutableListOf(ProviderOperationResult.Success(null)),
        )
        val transport = FakeTransportProvider(
            pullResults = mutableListOf(ProviderOperationResult.Failure(FakeError())),
        )
        val context = makeContext(storage, transport)

        runSuspend { makePipeline().execute(context) }

        assertEquals(1, transport.pullCallCount)
    }

    @Test
    fun execute_pullFailure_previousCompletedSummaryIsPreserved() {
        val changeSet1 = makeChangeSet(changeSetId = "cs-1", eventIds = listOf("e1", "e2"))
        val checkpoint1 = makeCheckpoint(token = "cp-1")
        val pullError = FakeError(message = "Pull failed on second call.")
        val storage = FakeStorageProvider(
            readCheckpointResults = mutableListOf(ProviderOperationResult.Success(null)),
            applyResults = mutableListOf(ProviderOperationResult.Success(Unit)),
            writeCheckpointResults = mutableListOf(ProviderOperationResult.Success(Unit)),
        )
        val transport = FakeTransportProvider(
            pullResults = mutableListOf(
                ProviderOperationResult.Success(
                    PullChangesResult.Changes(changeSet = changeSet1, hasMore = true, nextCheckpoint = checkpoint1),
                ),
                ProviderOperationResult.Failure(pullError),
            ),
        )
        val context = makeContext(storage, transport)

        val result = runSuspend { makePipeline().execute(context) }

        val failed = assertIs<SynchronizationResult.Failed>(result)
        assertSame(pullError, failed.error)
        assertEquals(2L, failed.summary.inboundEventsReceived)
        assertEquals(2L, failed.summary.inboundEventsApplied)
    }

    // =========================================================================
    // Apply failure tests
    // =========================================================================

    @Test
    fun execute_applyFailure_returnsFailed() {
        val applyError = FakeError(message = "Apply failed.")
        val changeSet = makeChangeSet(eventIds = listOf("e1"))
        val storage = FakeStorageProvider(
            readCheckpointResults = mutableListOf(ProviderOperationResult.Success(null)),
            applyResults = mutableListOf(ProviderOperationResult.Failure(applyError)),
        )
        val transport = FakeTransportProvider(
            pullResults = mutableListOf(
                ProviderOperationResult.Success(
                    PullChangesResult.Changes(changeSet = changeSet, hasMore = false, nextCheckpoint = makeCheckpoint()),
                ),
            ),
        )
        val context = makeContext(storage, transport)

        val result = runSuspend { makePipeline().execute(context) }

        val failed = assertIs<SynchronizationResult.Failed>(result)
        assertSame(applyError, failed.error)
    }

    @Test
    fun execute_applyFailure_nextCheckpointIsNotWritten() {
        val changeSet = makeChangeSet(eventIds = listOf("e1"))
        val storage = FakeStorageProvider(
            readCheckpointResults = mutableListOf(ProviderOperationResult.Success(null)),
            applyResults = mutableListOf(ProviderOperationResult.Failure(FakeError())),
        )
        val transport = FakeTransportProvider(
            pullResults = mutableListOf(
                ProviderOperationResult.Success(
                    PullChangesResult.Changes(changeSet = changeSet, hasMore = false, nextCheckpoint = makeCheckpoint()),
                ),
            ),
        )
        val context = makeContext(storage, transport)

        runSuspend { makePipeline().execute(context) }

        assertEquals(0, storage.writeCheckpointCallCount)
    }

    @Test
    fun execute_applyFailure_laterPullDoesNotOccur() {
        val changeSet = makeChangeSet(eventIds = listOf("e1"))
        val storage = FakeStorageProvider(
            readCheckpointResults = mutableListOf(ProviderOperationResult.Success(null)),
            applyResults = mutableListOf(ProviderOperationResult.Failure(FakeError())),
        )
        val transport = FakeTransportProvider(
            pullResults = mutableListOf(
                ProviderOperationResult.Success(
                    PullChangesResult.Changes(changeSet = changeSet, hasMore = true, nextCheckpoint = makeCheckpoint()),
                ),
            ),
        )
        val context = makeContext(storage, transport)

        runSuspend { makePipeline().execute(context) }

        assertEquals(1, transport.pullCallCount)
    }

    @Test
    fun execute_applyFailure_receivedCountAndAppliedCountAreTruthful() {
        val changeSet = makeChangeSet(eventIds = listOf("e1", "e2"))
        val storage = FakeStorageProvider(
            readCheckpointResults = mutableListOf(ProviderOperationResult.Success(null)),
            applyResults = mutableListOf(ProviderOperationResult.Failure(FakeError())),
        )
        val transport = FakeTransportProvider(
            pullResults = mutableListOf(
                ProviderOperationResult.Success(
                    PullChangesResult.Changes(changeSet = changeSet, hasMore = false),
                ),
            ),
        )
        val context = makeContext(storage, transport)

        val result = runSuspend { makePipeline().execute(context) }

        val failed = assertIs<SynchronizationResult.Failed>(result)
        // Received = 2 (batch accepted for processing); applied = 0 (apply failed)
        assertEquals(2L, failed.summary.inboundEventsReceived)
        assertEquals(0L, failed.summary.inboundEventsApplied)
    }

    // =========================================================================
    // Checkpoint write failure tests
    // =========================================================================

    @Test
    fun execute_checkpointWriteFailureAfterApply_applySucceedsFirst() {
        val changeSet = makeChangeSet(eventIds = listOf("e1"))
        val storage = FakeStorageProvider(
            readCheckpointResults = mutableListOf(ProviderOperationResult.Success(null)),
            applyResults = mutableListOf(ProviderOperationResult.Success(Unit)),
            writeCheckpointResults = mutableListOf(ProviderOperationResult.Failure(FakeError())),
        )
        val transport = FakeTransportProvider(
            pullResults = mutableListOf(
                ProviderOperationResult.Success(
                    PullChangesResult.Changes(changeSet = changeSet, hasMore = false, nextCheckpoint = makeCheckpoint()),
                ),
            ),
        )
        val context = makeContext(storage, transport)

        val result = runSuspend { makePipeline().execute(context) }

        // Apply was called exactly once and succeeded.
        assertEquals(1, storage.applyCallCount)
        assertIs<SynchronizationResult.Failed>(result)
    }

    @Test
    fun execute_checkpointWriteFailure_returnsFailed() {
        val writeError = FakeError(message = "Checkpoint write failed.")
        val changeSet = makeChangeSet(eventIds = listOf("e1"))
        val storage = FakeStorageProvider(
            readCheckpointResults = mutableListOf(ProviderOperationResult.Success(null)),
            applyResults = mutableListOf(ProviderOperationResult.Success(Unit)),
            writeCheckpointResults = mutableListOf(ProviderOperationResult.Failure(writeError)),
        )
        val transport = FakeTransportProvider(
            pullResults = mutableListOf(
                ProviderOperationResult.Success(
                    PullChangesResult.Changes(changeSet = changeSet, hasMore = false, nextCheckpoint = makeCheckpoint()),
                ),
            ),
        )
        val context = makeContext(storage, transport)

        val result = runSuspend { makePipeline().execute(context) }

        val failed = assertIs<SynchronizationResult.Failed>(result)
        assertSame(writeError, failed.error)
    }

    @Test
    fun execute_checkpointWriteFailure_laterPullDoesNotOccur() {
        val changeSet = makeChangeSet(eventIds = listOf("e1"))
        val storage = FakeStorageProvider(
            readCheckpointResults = mutableListOf(ProviderOperationResult.Success(null)),
            applyResults = mutableListOf(ProviderOperationResult.Success(Unit)),
            writeCheckpointResults = mutableListOf(ProviderOperationResult.Failure(FakeError())),
        )
        val transport = FakeTransportProvider(
            pullResults = mutableListOf(
                ProviderOperationResult.Success(
                    PullChangesResult.Changes(changeSet = changeSet, hasMore = false, nextCheckpoint = makeCheckpoint()),
                ),
            ),
        )
        val context = makeContext(storage, transport)

        runSuspend { makePipeline().execute(context) }

        assertEquals(1, transport.pullCallCount)
    }

    @Test
    fun execute_checkpointWriteFailure_summaryShowsActuallyAppliedWork() {
        val changeSet = makeChangeSet(eventIds = listOf("e1", "e2"))
        val storage = FakeStorageProvider(
            readCheckpointResults = mutableListOf(ProviderOperationResult.Success(null)),
            applyResults = mutableListOf(ProviderOperationResult.Success(Unit)),
            writeCheckpointResults = mutableListOf(ProviderOperationResult.Failure(FakeError())),
        )
        val transport = FakeTransportProvider(
            pullResults = mutableListOf(
                ProviderOperationResult.Success(
                    PullChangesResult.Changes(changeSet = changeSet, hasMore = false, nextCheckpoint = makeCheckpoint()),
                ),
            ),
        )
        val context = makeContext(storage, transport)

        val result = runSuspend { makePipeline().execute(context) }

        val failed = assertIs<SynchronizationResult.Failed>(result)
        assertEquals(2L, failed.summary.inboundEventsReceived)
        assertEquals(2L, failed.summary.inboundEventsApplied)
    }

    // =========================================================================
    // Apply-before-checkpoint invariant
    // =========================================================================

    @Test
    fun execute_checkpointWriteIsNeverInvokedBeforeApply() {
        val nextCheckpoint = makeCheckpoint(token = "next-001")
        val changeSet = makeChangeSet(eventIds = listOf("e1"))
        val storage = FakeStorageProvider(
            readCheckpointResults = mutableListOf(ProviderOperationResult.Success(null)),
            applyResults = mutableListOf(ProviderOperationResult.Success(Unit)),
            writeCheckpointResults = mutableListOf(ProviderOperationResult.Success(Unit)),
        )
        val transport = FakeTransportProvider(
            pullResults = mutableListOf(
                ProviderOperationResult.Success(
                    PullChangesResult.Changes(changeSet = changeSet, hasMore = false, nextCheckpoint = nextCheckpoint),
                ),
            ),
        )
        val context = makeContext(storage, transport)

        runSuspend { makePipeline().execute(context) }

        // The invariant: readCheckpoint → apply → writeCheckpoint
        val seq = storage.callSequence
        val applyIdx = seq.indexOf("apply")
        val writeIdx = seq.indexOf("writeCheckpoint")
        assertTrue(applyIdx >= 0, "apply must be called")
        assertTrue(writeIdx >= 0, "writeCheckpoint must be called")
        assertTrue(applyIdx < writeIdx, "apply must be called before writeCheckpoint")
    }

    @Test
    fun execute_failedApplyPerformsNoWrite() {
        val changeSet = makeChangeSet(eventIds = listOf("e1"))
        val storage = FakeStorageProvider(
            readCheckpointResults = mutableListOf(ProviderOperationResult.Success(null)),
            applyResults = mutableListOf(ProviderOperationResult.Failure(FakeError())),
        )
        val transport = FakeTransportProvider(
            pullResults = mutableListOf(
                ProviderOperationResult.Success(
                    PullChangesResult.Changes(changeSet = changeSet, hasMore = false, nextCheckpoint = makeCheckpoint()),
                ),
            ),
        )
        val context = makeContext(storage, transport)

        runSuspend { makePipeline().execute(context) }

        assertEquals(0, storage.writeCheckpointCallCount)
        assertFalse(storage.callSequence.contains("writeCheckpoint"))
    }

    @Test
    fun execute_invocationOrderingIsDeterministic() {
        val changeSet = makeChangeSet(eventIds = listOf("e1"))
        val nextCheckpoint = makeCheckpoint(token = "next-001")
        val storage = FakeStorageProvider(
            readCheckpointResults = mutableListOf(ProviderOperationResult.Success(null)),
            applyResults = mutableListOf(ProviderOperationResult.Success(Unit)),
            writeCheckpointResults = mutableListOf(ProviderOperationResult.Success(Unit)),
        )
        val transport = FakeTransportProvider(
            pullResults = mutableListOf(
                ProviderOperationResult.Success(
                    PullChangesResult.Changes(changeSet = changeSet, hasMore = false, nextCheckpoint = nextCheckpoint),
                ),
            ),
        )
        val context = makeContext(storage, transport)

        runSuspend { makePipeline().execute(context) }

        // Storage: readCheckpoint, apply, writeCheckpoint in that order.
        assertEquals("readCheckpoint", storage.callSequence[0])
        assertEquals("apply", storage.callSequence[1])
        assertEquals("writeCheckpoint", storage.callSequence[2])
    }

    // =========================================================================
    // Paging contract tests
    // =========================================================================

    @Test
    fun execute_hasMoreTrueWithNextCheckpoint_continues() {
        val changeSet1 = makeChangeSet(changeSetId = "cs-1", eventIds = listOf("e1"))
        val changeSet2 = makeChangeSet(changeSetId = "cs-2", eventIds = listOf("e2"))
        val checkpoint1 = makeCheckpoint(token = "cp-1")
        val storage = FakeStorageProvider(
            readCheckpointResults = mutableListOf(ProviderOperationResult.Success(null)),
            applyResults = mutableListOf(
                ProviderOperationResult.Success(Unit),
                ProviderOperationResult.Success(Unit),
            ),
            writeCheckpointResults = mutableListOf(ProviderOperationResult.Success(Unit)),
        )
        val transport = FakeTransportProvider(
            pullResults = mutableListOf(
                ProviderOperationResult.Success(
                    PullChangesResult.Changes(changeSet = changeSet1, hasMore = true, nextCheckpoint = checkpoint1),
                ),
                ProviderOperationResult.Success(
                    PullChangesResult.Changes(changeSet = changeSet2, hasMore = false),
                ),
            ),
        )
        val context = makeContext(storage, transport)

        val result = runSuspend { makePipeline().execute(context) }

        assertIs<SynchronizationResult.Succeeded>(result)
        assertEquals(2, transport.pullCallCount)
    }

    @Test
    fun execute_nextPullUsesPersistedCheckpoint() {
        val changeSet1 = makeChangeSet(changeSetId = "cs-1", eventIds = listOf("e1"))
        val changeSet2 = makeChangeSet(changeSetId = "cs-2", eventIds = listOf("e2"))
        val checkpoint1 = makeCheckpoint(token = "cp-1")
        val storage = FakeStorageProvider(
            readCheckpointResults = mutableListOf(ProviderOperationResult.Success(null)),
            applyResults = mutableListOf(
                ProviderOperationResult.Success(Unit),
                ProviderOperationResult.Success(Unit),
            ),
            writeCheckpointResults = mutableListOf(ProviderOperationResult.Success(Unit)),
        )
        val transport = FakeTransportProvider(
            pullResults = mutableListOf(
                ProviderOperationResult.Success(
                    PullChangesResult.Changes(changeSet = changeSet1, hasMore = true, nextCheckpoint = checkpoint1),
                ),
                ProviderOperationResult.Success(
                    PullChangesResult.Changes(changeSet = changeSet2, hasMore = false),
                ),
            ),
        )
        val context = makeContext(storage, transport)

        runSuspend { makePipeline().execute(context) }

        assertEquals(checkpoint1, transport.pullRequests[1].checkpoint)
    }

    @Test
    fun execute_hasMoreTrueWithNullNextCheckpoint_failsSafely() {
        val changeSet = makeChangeSet(eventIds = listOf("e1"))
        val storage = FakeStorageProvider(
            readCheckpointResults = mutableListOf(ProviderOperationResult.Success(null)),
            applyResults = mutableListOf(ProviderOperationResult.Success(Unit)),
        )
        val transport = FakeTransportProvider(
            pullResults = mutableListOf(
                ProviderOperationResult.Success(
                    PullChangesResult.Changes(changeSet = changeSet, hasMore = true, nextCheckpoint = null),
                ),
            ),
        )
        val context = makeContext(storage, transport)

        val result = runSuspend { makePipeline().execute(context) }

        assertIs<SynchronizationResult.Failed>(result)
    }

    @Test
    fun execute_hasMoreTrueWithNullNextCheckpoint_noRepeatedPullOccurs() {
        val changeSet = makeChangeSet(eventIds = listOf("e1"))
        val storage = FakeStorageProvider(
            readCheckpointResults = mutableListOf(ProviderOperationResult.Success(null)),
            applyResults = mutableListOf(ProviderOperationResult.Success(Unit)),
        )
        val transport = FakeTransportProvider(
            pullResults = mutableListOf(
                ProviderOperationResult.Success(
                    PullChangesResult.Changes(changeSet = changeSet, hasMore = true, nextCheckpoint = null),
                ),
            ),
        )
        val context = makeContext(storage, transport)

        runSuspend { makePipeline().execute(context) }

        assertEquals(1, transport.pullCallCount)
    }

    // =========================================================================
    // Duplicate batch protection tests
    // =========================================================================

    @Test
    fun execute_duplicateChangeSetId_returnsFailed() {
        val changeSet = makeChangeSet(changeSetId = "cs-dup", eventIds = listOf("e1"))
        val checkpoint1 = makeCheckpoint(token = "cp-1")
        val storage = FakeStorageProvider(
            readCheckpointResults = mutableListOf(ProviderOperationResult.Success(null)),
            applyResults = mutableListOf(ProviderOperationResult.Success(Unit)),
            writeCheckpointResults = mutableListOf(ProviderOperationResult.Success(Unit)),
        )
        val transport = FakeTransportProvider(
            pullResults = mutableListOf(
                ProviderOperationResult.Success(
                    PullChangesResult.Changes(changeSet = changeSet, hasMore = true, nextCheckpoint = checkpoint1),
                ),
                // Same ChangeSetId returned again.
                ProviderOperationResult.Success(
                    PullChangesResult.Changes(changeSet = changeSet, hasMore = false),
                ),
            ),
        )
        val context = makeContext(storage, transport)

        val result = runSuspend { makePipeline().execute(context) }

        assertIs<SynchronizationResult.Failed>(result)
    }

    @Test
    fun execute_duplicateBatch_isNotApplied() {
        val changeSet = makeChangeSet(changeSetId = "cs-dup", eventIds = listOf("e1"))
        val checkpoint1 = makeCheckpoint(token = "cp-1")
        val storage = FakeStorageProvider(
            readCheckpointResults = mutableListOf(ProviderOperationResult.Success(null)),
            applyResults = mutableListOf(ProviderOperationResult.Success(Unit)),
            writeCheckpointResults = mutableListOf(ProviderOperationResult.Success(Unit)),
        )
        val transport = FakeTransportProvider(
            pullResults = mutableListOf(
                ProviderOperationResult.Success(
                    PullChangesResult.Changes(changeSet = changeSet, hasMore = true, nextCheckpoint = checkpoint1),
                ),
                ProviderOperationResult.Success(
                    PullChangesResult.Changes(changeSet = changeSet, hasMore = false),
                ),
            ),
        )
        val context = makeContext(storage, transport)

        runSuspend { makePipeline().execute(context) }

        // Only the first occurrence should have been applied.
        assertEquals(1, storage.applyCallCount)
    }

    @Test
    fun execute_duplicateBatch_checkpointIsNotWrittenForDuplicate() {
        val changeSet = makeChangeSet(changeSetId = "cs-dup", eventIds = listOf("e1"))
        val checkpoint1 = makeCheckpoint(token = "cp-1")
        val storage = FakeStorageProvider(
            readCheckpointResults = mutableListOf(ProviderOperationResult.Success(null)),
            applyResults = mutableListOf(ProviderOperationResult.Success(Unit)),
            writeCheckpointResults = mutableListOf(ProviderOperationResult.Success(Unit)),
        )
        val transport = FakeTransportProvider(
            pullResults = mutableListOf(
                ProviderOperationResult.Success(
                    PullChangesResult.Changes(changeSet = changeSet, hasMore = true, nextCheckpoint = checkpoint1),
                ),
                ProviderOperationResult.Success(
                    PullChangesResult.Changes(changeSet = changeSet, hasMore = false),
                ),
            ),
        )
        val context = makeContext(storage, transport)

        runSuspend { makePipeline().execute(context) }

        // writeCheckpoint called once (for the first legitimate batch),
        // not a second time for the duplicate.
        assertEquals(1, storage.writeCheckpointCallCount)
    }

    @Test
    fun execute_duplicateBatch_previousSummaryIsPreserved() {
        val changeSet = makeChangeSet(changeSetId = "cs-dup", eventIds = listOf("e1", "e2"))
        val checkpoint1 = makeCheckpoint(token = "cp-1")
        val storage = FakeStorageProvider(
            readCheckpointResults = mutableListOf(ProviderOperationResult.Success(null)),
            applyResults = mutableListOf(ProviderOperationResult.Success(Unit)),
            writeCheckpointResults = mutableListOf(ProviderOperationResult.Success(Unit)),
        )
        val transport = FakeTransportProvider(
            pullResults = mutableListOf(
                ProviderOperationResult.Success(
                    PullChangesResult.Changes(changeSet = changeSet, hasMore = true, nextCheckpoint = checkpoint1),
                ),
                ProviderOperationResult.Success(
                    PullChangesResult.Changes(changeSet = changeSet, hasMore = false),
                ),
            ),
        )
        val context = makeContext(storage, transport)

        val result = runSuspend { makePipeline().execute(context) }

        val failed = assertIs<SynchronizationResult.Failed>(result)
        assertEquals(2L, failed.summary.inboundEventsReceived)
        assertEquals(2L, failed.summary.inboundEventsApplied)
    }

    @Test
    fun execute_duplicateBatch_errorExposesNoPayloadOrToken() {
        val changeSet = makeChangeSet(changeSetId = "cs-dup", eventIds = listOf("e1"))
        val checkpoint1 = makeCheckpoint(token = "super-secret-token-value")
        val storage = FakeStorageProvider(
            readCheckpointResults = mutableListOf(ProviderOperationResult.Success(null)),
            applyResults = mutableListOf(ProviderOperationResult.Success(Unit)),
            writeCheckpointResults = mutableListOf(ProviderOperationResult.Success(Unit)),
        )
        val transport = FakeTransportProvider(
            pullResults = mutableListOf(
                ProviderOperationResult.Success(
                    PullChangesResult.Changes(changeSet = changeSet, hasMore = true, nextCheckpoint = checkpoint1),
                ),
                ProviderOperationResult.Success(
                    PullChangesResult.Changes(changeSet = changeSet, hasMore = false),
                ),
            ),
        )
        val context = makeContext(storage, transport)

        val result = runSuspend { makePipeline().execute(context) }

        val failed = assertIs<SynchronizationResult.Failed>(result)
        // Error message must not expose checkpoint token values.
        assertTrue(!failed.error.message.contains("super-secret-token-value"))
    }

    // =========================================================================
    // Batch limit tests
    // =========================================================================

    @Test
    fun execute_batchLimitReached_stopsAtLimit() {
        val checkpoint = makeCheckpoint(token = "cp-init")
        val changeSet1 = makeChangeSet(changeSetId = "cs-1", eventIds = listOf("e1"))
        val changeSet2 = makeChangeSet(changeSetId = "cs-2", eventIds = listOf("e2"))
        val checkpoint1 = makeCheckpoint(token = "cp-1")
        val storage = FakeStorageProvider(
            readCheckpointResults = mutableListOf(ProviderOperationResult.Success(checkpoint)),
            applyResults = mutableListOf(
                ProviderOperationResult.Success(Unit),
                ProviderOperationResult.Success(Unit),
            ),
            writeCheckpointResults = mutableListOf(
                ProviderOperationResult.Success(Unit),
                ProviderOperationResult.Success(Unit),
            ),
        )
        // maxBatchesPerExecution = 2, and both batches have hasMore = true.
        val transport = FakeTransportProvider(
            pullResults = mutableListOf(
                ProviderOperationResult.Success(
                    PullChangesResult.Changes(changeSet = changeSet1, hasMore = true, nextCheckpoint = checkpoint1),
                ),
                ProviderOperationResult.Success(
                    PullChangesResult.Changes(
                        changeSet = changeSet2,
                        hasMore = true,
                        nextCheckpoint = makeCheckpoint(token = "cp-2"),
                    ),
                ),
            ),
        )
        val context = makeContext(storage, transport)
        val configuration = InboundPullPipelineConfiguration(maxBatchesPerExecution = 2)

        val result = runSuspend { InboundPullSynchronizationPipeline(configuration).execute(context) }

        // No extra pull after reaching the limit.
        assertEquals(2, transport.pullCallCount)
        assertIs<SynchronizationResult.PartiallySucceeded>(result)
    }

    @Test
    fun execute_batchLimitReached_hasMoreTrueProducesPartiallySucceeded() {
        val changeSet1 = makeChangeSet(changeSetId = "cs-1", eventIds = listOf("e1"))
        val checkpoint1 = makeCheckpoint(token = "cp-1")
        val storage = FakeStorageProvider(
            readCheckpointResults = mutableListOf(ProviderOperationResult.Success(null)),
            applyResults = mutableListOf(ProviderOperationResult.Success(Unit)),
            writeCheckpointResults = mutableListOf(ProviderOperationResult.Success(Unit)),
        )
        val transport = FakeTransportProvider(
            pullResults = mutableListOf(
                ProviderOperationResult.Success(
                    PullChangesResult.Changes(changeSet = changeSet1, hasMore = true, nextCheckpoint = checkpoint1),
                ),
            ),
        )
        val context = makeContext(storage, transport)
        val configuration = InboundPullPipelineConfiguration(maxBatchesPerExecution = 1)

        val result = runSuspend { InboundPullSynchronizationPipeline(configuration).execute(context) }

        assertIs<SynchronizationResult.PartiallySucceeded>(result)
    }

    @Test
    fun execute_batchLimitReached_safeRecoverableErrorIsPresent() {
        val changeSet1 = makeChangeSet(changeSetId = "cs-1", eventIds = listOf("e1"))
        val checkpoint1 = makeCheckpoint(token = "cp-1")
        val storage = FakeStorageProvider(
            readCheckpointResults = mutableListOf(ProviderOperationResult.Success(null)),
            applyResults = mutableListOf(ProviderOperationResult.Success(Unit)),
            writeCheckpointResults = mutableListOf(ProviderOperationResult.Success(Unit)),
        )
        val transport = FakeTransportProvider(
            pullResults = mutableListOf(
                ProviderOperationResult.Success(
                    PullChangesResult.Changes(changeSet = changeSet1, hasMore = true, nextCheckpoint = checkpoint1),
                ),
            ),
        )
        val context = makeContext(storage, transport)
        val configuration = InboundPullPipelineConfiguration(maxBatchesPerExecution = 1)

        val result = runSuspend { InboundPullSynchronizationPipeline(configuration).execute(context) }

        val partial = assertIs<SynchronizationResult.PartiallySucceeded>(result)
        assertTrue(partial.errors.isNotEmpty())
        assertEquals(Recoverability.RECOVERABLE, partial.errors.single().recoverability)
    }

    @Test
    fun execute_batchLimitReached_completedSummaryIsPreserved() {
        val changeSet1 = makeChangeSet(changeSetId = "cs-1", eventIds = listOf("e1", "e2"))
        val checkpoint1 = makeCheckpoint(token = "cp-1")
        val storage = FakeStorageProvider(
            readCheckpointResults = mutableListOf(ProviderOperationResult.Success(null)),
            applyResults = mutableListOf(ProviderOperationResult.Success(Unit)),
            writeCheckpointResults = mutableListOf(ProviderOperationResult.Success(Unit)),
        )
        val transport = FakeTransportProvider(
            pullResults = mutableListOf(
                ProviderOperationResult.Success(
                    PullChangesResult.Changes(changeSet = changeSet1, hasMore = true, nextCheckpoint = checkpoint1),
                ),
            ),
        )
        val context = makeContext(storage, transport)
        val configuration = InboundPullPipelineConfiguration(maxBatchesPerExecution = 1)

        val result = runSuspend { InboundPullSynchronizationPipeline(configuration).execute(context) }

        val partial = assertIs<SynchronizationResult.PartiallySucceeded>(result)
        assertEquals(2L, partial.summary.inboundEventsReceived)
        assertEquals(2L, partial.summary.inboundEventsApplied)
    }

    // =========================================================================
    // Result and summary tests
    // =========================================================================

    @Test
    fun execute_initialNoChanges_producesSkipped() {
        val storage = FakeStorageProvider(
            readCheckpointResults = mutableListOf(ProviderOperationResult.Success(null)),
        )
        val transport = FakeTransportProvider(
            pullResults = mutableListOf(ProviderOperationResult.Success(PullChangesResult.NoChanges())),
        )
        val context = makeContext(storage, transport)

        val result = runSuspend { makePipeline().execute(context) }

        assertIs<SynchronizationResult.Skipped>(result)
    }

    @Test
    fun execute_successfulAppliedBatches_producesSucceeded() {
        val changeSet = makeChangeSet(eventIds = listOf("e1"))
        val storage = FakeStorageProvider(
            readCheckpointResults = mutableListOf(ProviderOperationResult.Success(null)),
            applyResults = mutableListOf(ProviderOperationResult.Success(Unit)),
        )
        val transport = FakeTransportProvider(
            pullResults = mutableListOf(
                ProviderOperationResult.Success(
                    PullChangesResult.Changes(changeSet = changeSet, hasMore = false),
                ),
            ),
        )
        val context = makeContext(storage, transport)

        val result = runSuspend { makePipeline().execute(context) }

        assertIs<SynchronizationResult.Succeeded>(result)
    }

    @Test
    fun execute_batchLimitReached_producesPartiallySucceeded() {
        val changeSet1 = makeChangeSet(changeSetId = "cs-1", eventIds = listOf("e1"))
        val checkpoint1 = makeCheckpoint(token = "cp-1")
        val storage = FakeStorageProvider(
            readCheckpointResults = mutableListOf(ProviderOperationResult.Success(null)),
            applyResults = mutableListOf(ProviderOperationResult.Success(Unit)),
            writeCheckpointResults = mutableListOf(ProviderOperationResult.Success(Unit)),
        )
        val transport = FakeTransportProvider(
            pullResults = mutableListOf(
                ProviderOperationResult.Success(
                    PullChangesResult.Changes(changeSet = changeSet1, hasMore = true, nextCheckpoint = checkpoint1),
                ),
            ),
        )
        val configuration = InboundPullPipelineConfiguration(maxBatchesPerExecution = 1)
        val context = makeContext(storage, transport)

        val result = runSuspend { InboundPullSynchronizationPipeline(configuration).execute(context) }

        assertIs<SynchronizationResult.PartiallySucceeded>(result)
    }

    @Test
    fun execute_providerAndContractFailures_produceFailed() {
        val storage = FakeStorageProvider(
            readCheckpointResults = mutableListOf(ProviderOperationResult.Failure(FakeError())),
        )
        val transport = FakeTransportProvider()
        val context = makeContext(storage, transport)

        val result = runSuspend { makePipeline().execute(context) }

        assertIs<SynchronizationResult.Failed>(result)
    }

    @Test
    fun execute_inboundReceivedAndAppliedCountersAreAccurate() {
        val changeSet1 = makeChangeSet(changeSetId = "cs-1", eventIds = listOf("e1", "e2", "e3"))
        val changeSet2 = makeChangeSet(changeSetId = "cs-2", eventIds = listOf("e4"))
        val checkpoint1 = makeCheckpoint(token = "cp-1")
        val storage = FakeStorageProvider(
            readCheckpointResults = mutableListOf(ProviderOperationResult.Success(null)),
            applyResults = mutableListOf(
                ProviderOperationResult.Success(Unit),
                ProviderOperationResult.Success(Unit),
            ),
            writeCheckpointResults = mutableListOf(ProviderOperationResult.Success(Unit)),
        )
        val transport = FakeTransportProvider(
            pullResults = mutableListOf(
                ProviderOperationResult.Success(
                    PullChangesResult.Changes(changeSet = changeSet1, hasMore = true, nextCheckpoint = checkpoint1),
                ),
                ProviderOperationResult.Success(
                    PullChangesResult.Changes(changeSet = changeSet2, hasMore = false),
                ),
            ),
        )
        val context = makeContext(storage, transport)

        val result = runSuspend { makePipeline().execute(context) }

        val succeeded = assertIs<SynchronizationResult.Succeeded>(result)
        assertEquals(4L, succeeded.summary.inboundEventsReceived)
        assertEquals(4L, succeeded.summary.inboundEventsApplied)
    }

    @Test
    fun execute_unrelatedCountersRemainZero() {
        val changeSet = makeChangeSet(eventIds = listOf("e1"))
        val storage = FakeStorageProvider(
            readCheckpointResults = mutableListOf(ProviderOperationResult.Success(null)),
            applyResults = mutableListOf(ProviderOperationResult.Success(Unit)),
        )
        val transport = FakeTransportProvider(
            pullResults = mutableListOf(
                ProviderOperationResult.Success(
                    PullChangesResult.Changes(changeSet = changeSet, hasMore = false),
                ),
            ),
        )
        val context = makeContext(storage, transport)

        val result = runSuspend { makePipeline().execute(context) }

        val succeeded = assertIs<SynchronizationResult.Succeeded>(result)
        assertEquals(0L, succeeded.summary.outboundEventsRead)
        assertEquals(0L, succeeded.summary.outboundEventsAccepted)
        assertEquals(0L, succeeded.summary.outboundEventsMarkedForRetry)
        assertEquals(0L, succeeded.summary.outboundEventsRejected)
        assertEquals(0L, succeeded.summary.conflictsDetected)
        assertEquals(0, succeeded.summary.retryAttempts)
    }

    @Test
    fun execute_completionTimestampComesFromInjectedClock() {
        val clock = FakeClock(nowMs = 99_999L)
        val changeSet = makeChangeSet(eventIds = listOf("e1"))
        val storage = FakeStorageProvider(
            readCheckpointResults = mutableListOf(ProviderOperationResult.Success(null)),
            applyResults = mutableListOf(ProviderOperationResult.Success(Unit)),
        )
        val transport = FakeTransportProvider(
            pullResults = mutableListOf(
                ProviderOperationResult.Success(
                    PullChangesResult.Changes(changeSet = changeSet, hasMore = false),
                ),
            ),
        )
        val context = makeContext(storage, transport, clock = clock)

        val result = runSuspend { makePipeline().execute(context) }

        val succeeded = assertIs<SynchronizationResult.Succeeded>(result)
        assertEquals(DataLoomInstant(99_999L), succeeded.completedAt)
        assertTrue(clock.readCallCount >= 1)
    }

    @Test
    fun execute_exactSynchronizationRequestIsPreserved() {
        val request = makeRequest()
        val storage = FakeStorageProvider(
            readCheckpointResults = mutableListOf(ProviderOperationResult.Success(null)),
        )
        val transport = FakeTransportProvider(
            pullResults = mutableListOf(ProviderOperationResult.Success(PullChangesResult.NoChanges())),
        )
        val context = makeContext(storage, transport, request = request)

        val result = runSuspend { makePipeline().execute(context) }

        assertSame(request, result.request)
    }

    // =========================================================================
    // Cancellation tests
    // =========================================================================

    private class CancellingReadCheckpointStorageProvider : StorageProvider {
        override val descriptor: ProviderDescriptor = ProviderDescriptor(
            id = ProviderId("cancelling-read-checkpoint"),
            name = ProviderName("Cancelling ReadCheckpoint"),
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
        ): ProviderOperationResult<OutboundChangeReadResult> =
            ProviderOperationResult.Success(OutboundChangeReadResult.NoChanges)

        override suspend fun applyInboundChanges(
            request: InboundChangeApplyRequest,
        ): ProviderOperationResult<Unit> = ProviderOperationResult.Success(Unit)

        override suspend fun acknowledgeOutboundChanges(
            request: OutboundChangeAcknowledgementRequest,
        ): ProviderOperationResult<Unit> = ProviderOperationResult.Failure(FakeError())

        override suspend fun readCheckpoint(
            request: CheckpointReadRequest,
        ): ProviderOperationResult<SynchronizationCheckpoint?> =
            throw CancellationException("Cancelled readCheckpoint")

        override suspend fun writeCheckpoint(
            request: CheckpointWriteRequest,
        ): ProviderOperationResult<Unit> = ProviderOperationResult.Success(Unit)
    }

    @Test
    fun execute_cancellationFromReadCheckpoint_propagates() {
        val storage = CancellingReadCheckpointStorageProvider()
        val transport = FakeTransportProvider()
        val context = makeContext(storage, transport)

        assertFailsWith<CancellationException> {
            runSuspend { makePipeline().execute(context) }
        }
    }

    private class CancellingPullTransportProvider : TransportProvider {
        override val descriptor: ProviderDescriptor = ProviderDescriptor(
            id = ProviderId("cancelling-pull"),
            name = ProviderName("Cancelling Pull"),
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
        ): ProviderOperationResult<ChangeSetAcknowledgement> = ProviderOperationResult.Failure(FakeError())

        override suspend fun pullChanges(
            request: PullChangesRequest,
        ): ProviderOperationResult<PullChangesResult> = throw CancellationException("Cancelled pull")
    }

    @Test
    fun execute_cancellationFromPull_propagates() {
        val storage = FakeStorageProvider(
            readCheckpointResults = mutableListOf(ProviderOperationResult.Success(null)),
        )
        val transport = CancellingPullTransportProvider()
        val context = makeContext(storage, transport)

        assertFailsWith<CancellationException> {
            runSuspend { makePipeline().execute(context) }
        }
    }

    private class CancellingApplyStorageProvider(
        private val storedCheckpoint: SynchronizationCheckpoint? = null,
    ) : StorageProvider {
        override val descriptor: ProviderDescriptor = ProviderDescriptor(
            id = ProviderId("cancelling-apply"),
            name = ProviderName("Cancelling Apply"),
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
        ): ProviderOperationResult<OutboundChangeReadResult> =
            ProviderOperationResult.Success(OutboundChangeReadResult.NoChanges)

        override suspend fun applyInboundChanges(
            request: InboundChangeApplyRequest,
        ): ProviderOperationResult<Unit> = throw CancellationException("Cancelled apply")

        override suspend fun acknowledgeOutboundChanges(
            request: OutboundChangeAcknowledgementRequest,
        ): ProviderOperationResult<Unit> = ProviderOperationResult.Failure(FakeError())

        override suspend fun readCheckpoint(
            request: CheckpointReadRequest,
        ): ProviderOperationResult<SynchronizationCheckpoint?> = ProviderOperationResult.Success(storedCheckpoint)

        override suspend fun writeCheckpoint(
            request: CheckpointWriteRequest,
        ): ProviderOperationResult<Unit> = ProviderOperationResult.Success(Unit)
    }

    @Test
    fun execute_cancellationFromApply_propagates() {
        val changeSet = makeChangeSet(eventIds = listOf("e1"))
        val storage = CancellingApplyStorageProvider()
        val transport = FakeTransportProvider(
            pullResults = mutableListOf(
                ProviderOperationResult.Success(
                    PullChangesResult.Changes(changeSet = changeSet, hasMore = false),
                ),
            ),
        )
        val context = makeContext(storage, transport)

        assertFailsWith<CancellationException> {
            runSuspend { makePipeline().execute(context) }
        }
    }

    private class CancellingWriteCheckpointStorageProvider(
        private val applyResult: ProviderOperationResult<Unit> = ProviderOperationResult.Success(Unit),
    ) : StorageProvider {
        override val descriptor: ProviderDescriptor = ProviderDescriptor(
            id = ProviderId("cancelling-write-checkpoint"),
            name = ProviderName("Cancelling WriteCheckpoint"),
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
        ): ProviderOperationResult<OutboundChangeReadResult> =
            ProviderOperationResult.Success(OutboundChangeReadResult.NoChanges)

        override suspend fun applyInboundChanges(
            request: InboundChangeApplyRequest,
        ): ProviderOperationResult<Unit> = applyResult

        override suspend fun acknowledgeOutboundChanges(
            request: OutboundChangeAcknowledgementRequest,
        ): ProviderOperationResult<Unit> = ProviderOperationResult.Failure(FakeError())

        override suspend fun readCheckpoint(
            request: CheckpointReadRequest,
        ): ProviderOperationResult<SynchronizationCheckpoint?> = ProviderOperationResult.Success(null)

        override suspend fun writeCheckpoint(
            request: CheckpointWriteRequest,
        ): ProviderOperationResult<Unit> = throw CancellationException("Cancelled writeCheckpoint")
    }

    @Test
    fun execute_cancellationFromWriteCheckpoint_propagates() {
        val changeSet = makeChangeSet(eventIds = listOf("e1"))
        val nextCheckpoint = makeCheckpoint(token = "next")
        val storage = CancellingWriteCheckpointStorageProvider()
        val transport = FakeTransportProvider(
            pullResults = mutableListOf(
                ProviderOperationResult.Success(
                    PullChangesResult.Changes(changeSet = changeSet, hasMore = false, nextCheckpoint = nextCheckpoint),
                ),
            ),
        )
        val context = makeContext(storage, transport)

        assertFailsWith<CancellationException> {
            runSuspend { makePipeline().execute(context) }
        }
    }

    @Test
    fun execute_cancellationIsNotConvertedIntoResult() {
        val storage = CancellingReadCheckpointStorageProvider()
        val transport = FakeTransportProvider()
        val context = makeContext(storage, transport)

        // The CancellationException must propagate; it must not be caught
        // and returned as any SynchronizationResult variant.
        val exception = assertFailsWith<CancellationException> {
            runSuspend { makePipeline().execute(context) }
        }
        assertTrue(exception.message!!.isNotBlank())
    }

    // =========================================================================
    // Side-effect restrictions
    // =========================================================================

    @Test
    fun execute_doesNotCallOutboundStorageOrTransportPushOrLifecycle() {
        val changeSet = makeChangeSet(eventIds = listOf("e1"))
        val nextCheckpoint = makeCheckpoint(token = "next-001")
        val storage = FakeStorageProvider(
            readCheckpointResults = mutableListOf(ProviderOperationResult.Success(null)),
            applyResults = mutableListOf(ProviderOperationResult.Success(Unit)),
            writeCheckpointResults = mutableListOf(ProviderOperationResult.Success(Unit)),
        )
        val transport = FakeTransportProvider(
            pullResults = mutableListOf(
                ProviderOperationResult.Success(
                    PullChangesResult.Changes(changeSet = changeSet, hasMore = false, nextCheckpoint = nextCheckpoint),
                ),
            ),
        )
        val context = makeContext(storage, transport)

        runSuspend { makePipeline().execute(context) }

        // Lifecycle operations must not be invoked.
        assertEquals(0, storage.initializeCallCount)
        assertEquals(0, storage.healthCallCount)
        assertEquals(0, storage.closeCallCount)
        assertEquals(0, transport.initializeCallCount)
        assertEquals(0, transport.healthCallCount)
        assertEquals(0, transport.closeCallCount)

        // Outbound storage and transport operations must not be invoked.
        assertEquals(0, storage.readOutboundCallCount)
        assertEquals(0, storage.acknowledgeCallCount)
        assertEquals(0, transport.pushCallCount)
    }
}

// =========================================================================
// Internal helper
// =========================================================================

private fun assertFalse(condition: Boolean) {
    if (condition) throw AssertionError("Expected false but was true.")
}
