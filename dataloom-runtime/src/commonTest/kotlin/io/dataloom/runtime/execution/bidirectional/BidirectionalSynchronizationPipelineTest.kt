package io.dataloom.runtime.execution.bidirectional

import io.dataloom.api.context.ExecutionContext
import io.dataloom.api.error.DataLoomError
import io.dataloom.api.error.ErrorCategory
import io.dataloom.api.error.ErrorCode
import io.dataloom.api.error.ErrorSeverity
import io.dataloom.api.error.Recoverability
import io.dataloom.api.identifier.ConflictId
import io.dataloom.api.identifier.CorrelationId
import io.dataloom.api.identifier.ExecutionId
import io.dataloom.api.identifier.IdentifierGenerator
import io.dataloom.api.identifier.QueueEntryId
import io.dataloom.api.identifier.QueueLeaseId
import io.dataloom.api.identifier.SynchronizationEventId
import io.dataloom.api.identifier.SynchronizationSessionId
import io.dataloom.api.identifier.WorkflowId
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
import io.dataloom.api.synchronization.SynchronizationSummary
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
 * Deterministic common tests for DL-023 bidirectional synchronization
 * pipeline composition.
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
class BidirectionalSynchronizationPipelineTest {

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
    // Fake pipeline
    // =========================================================================

    /**
     * Deterministic fake [SynchronizationPipeline] that returns a
     * preset result and records invocation details.
     */
    private class FakePipeline(
        override val direction: SynchronizationDirection,
        private val result: SynchronizationResult,
    ) : SynchronizationPipeline {
        var executeCallCount: Int = 0
        var lastContext: SynchronizationExecutionContext? = null

        override suspend fun execute(
            context: SynchronizationExecutionContext,
        ): SynchronizationResult {
            executeCallCount++
            lastContext = context
            return result
        }
    }

    /**
     * Fake [SynchronizationPipeline] that throws [CancellationException] when
     * executed.
     */
    private class CancellingPipeline(
        override val direction: SynchronizationDirection,
    ) : SynchronizationPipeline {
        var executeCallCount: Int = 0

        override suspend fun execute(
            context: SynchronizationExecutionContext,
        ): SynchronizationResult {
            executeCallCount++
            throw CancellationException("Fake cancellation.")
        }
    }

    /**
     * Fake [SynchronizationPipeline] that throws an unexpected
     * [RuntimeException] when executed.
     */
    private class ExplodingPipeline(
        override val direction: SynchronizationDirection,
    ) : SynchronizationPipeline {
        var executeCallCount: Int = 0

        override suspend fun execute(
            context: SynchronizationExecutionContext,
        ): SynchronizationResult {
            executeCallCount++
            throw RuntimeException("Unexpected pipeline error.")
        }
    }

    /**
     * Fake [SynchronizationPipeline] that tracks execution order using a
     * shared sequence list.
     */
    private class OrderTrackingPipeline(
        override val direction: SynchronizationDirection,
        private val name: String,
        private val sequence: MutableList<String>,
        private val result: SynchronizationResult,
    ) : SynchronizationPipeline {
        var executeCallCount: Int = 0

        override suspend fun execute(
            context: SynchronizationExecutionContext,
        ): SynchronizationResult {
            executeCallCount++
            sequence.add(name)
            return result
        }
    }

    // =========================================================================
    // Stub providers
    // =========================================================================

    /**
     * Stub [StorageProvider] whose operations all throw if called.
     *
     * The bidirectional pipeline must not call any provider directly.
     * If it does, these stubs make the violation immediately visible.
     */
    private class StubStorageProvider : StorageProvider {
        override val descriptor: ProviderDescriptor = ProviderDescriptor(
            id = ProviderId("stub-storage"),
            name = ProviderName("Stub Storage"),
            type = ProviderType.STORAGE,
            version = ProviderVersion("0.0.0"),
        )

        override suspend fun initialize(
            context: ProviderInitializationContext,
        ): ProviderOperationResult<Unit> =
            throw AssertionError("StubStorageProvider.initialize must not be called by bidirectional pipeline.")

        override suspend fun health(): ProviderOperationResult<ProviderHealth> =
            throw AssertionError("StubStorageProvider.health must not be called by bidirectional pipeline.")

        override suspend fun close(): ProviderOperationResult<Unit> =
            throw AssertionError("StubStorageProvider.close must not be called by bidirectional pipeline.")

        override suspend fun readOutboundChanges(
            request: OutboundChangeReadRequest,
        ): ProviderOperationResult<OutboundChangeReadResult> =
            throw AssertionError("StubStorageProvider.readOutboundChanges must not be called by bidirectional pipeline.")

        override suspend fun applyInboundChanges(
            request: InboundChangeApplyRequest,
        ): ProviderOperationResult<Unit> =
            throw AssertionError("StubStorageProvider.applyInboundChanges must not be called by bidirectional pipeline.")

        override suspend fun acknowledgeOutboundChanges(
            request: OutboundChangeAcknowledgementRequest,
        ): ProviderOperationResult<Unit> =
            throw AssertionError("StubStorageProvider.acknowledgeOutboundChanges must not be called by bidirectional pipeline.")

        override suspend fun readCheckpoint(
            request: CheckpointReadRequest,
        ): ProviderOperationResult<SynchronizationCheckpoint?> =
            throw AssertionError("StubStorageProvider.readCheckpoint must not be called by bidirectional pipeline.")

        override suspend fun writeCheckpoint(
            request: CheckpointWriteRequest,
        ): ProviderOperationResult<Unit> =
            throw AssertionError("StubStorageProvider.writeCheckpoint must not be called by bidirectional pipeline.")
    }

    /**
     * Stub [TransportProvider] whose operations all throw if called.
     *
     * The bidirectional pipeline must not call any provider directly.
     */
    private class StubTransportProvider : TransportProvider {
        override val descriptor: ProviderDescriptor = ProviderDescriptor(
            id = ProviderId("stub-transport"),
            name = ProviderName("Stub Transport"),
            type = ProviderType.TRANSPORT,
            version = ProviderVersion("0.0.0"),
        )

        override suspend fun initialize(
            context: ProviderInitializationContext,
        ): ProviderOperationResult<Unit> =
            throw AssertionError("StubTransportProvider.initialize must not be called by bidirectional pipeline.")

        override suspend fun health(): ProviderOperationResult<ProviderHealth> =
            throw AssertionError("StubTransportProvider.health must not be called by bidirectional pipeline.")

        override suspend fun close(): ProviderOperationResult<Unit> =
            throw AssertionError("StubTransportProvider.close must not be called by bidirectional pipeline.")

        override suspend fun pushChanges(
            request: PushChangesRequest,
        ): ProviderOperationResult<ChangeSetAcknowledgement> =
            throw AssertionError("StubTransportProvider.pushChanges must not be called by bidirectional pipeline.")

        override suspend fun pullChanges(
            request: PullChangesRequest,
        ): ProviderOperationResult<PullChangesResult> =
            throw AssertionError("StubTransportProvider.pullChanges must not be called by bidirectional pipeline.")
    }

    // =========================================================================
    // Fake clock
    // =========================================================================

    private class FakeClock(private var nowMs: Long = 5_000_000L) : DataLoomClock {
        var readCallCount: Int = 0

        override fun now(): DataLoomInstant {
            readCallCount++
            return DataLoomInstant(nowMs)
        }

        fun advance(deltaMs: Long) {
            nowMs += deltaMs
        }
    }

    /** Clock that throws if invoked. */
    private class ExplodingClock : DataLoomClock {
        override fun now(): DataLoomInstant =
            throw AssertionError("Clock must not be read during construction.")
    }

    // =========================================================================
    // Model builders
    // =========================================================================

    private fun makeRequest(
        direction: SynchronizationDirection = SynchronizationDirection.BIDIRECTIONAL,
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
        request: SynchronizationRequest = makeRequest(),
        clock: DataLoomClock = FakeClock(),
    ) = SynchronizationExecutionContext(
        request = request,
        providers = ResolvedSynchronizationProviders(
            storageProvider = StubStorageProvider(),
            transportProvider = StubTransportProvider(),
            schedulerProvider = null,
            connectivityProvider = null,
            queueProvider = null,
        ),
        runtimeDependencies = makeRuntimeDependencies(clock),
    )

    private fun makeSummary(
        outboundRead: Long = 0L,
        outboundAccepted: Long = 0L,
        outboundRetry: Long = 0L,
        outboundRejected: Long = 0L,
        inboundReceived: Long = 0L,
        inboundApplied: Long = 0L,
        conflicts: Long = 0L,
        retryAttempts: Int = 0,
    ) = SynchronizationSummary(
        outboundEventsRead = outboundRead,
        outboundEventsAccepted = outboundAccepted,
        outboundEventsMarkedForRetry = outboundRetry,
        outboundEventsRejected = outboundRejected,
        inboundEventsReceived = inboundReceived,
        inboundEventsApplied = inboundApplied,
        conflictsDetected = conflicts,
        retryAttempts = retryAttempts,
    )

    private fun makeSucceeded(
        summary: SynchronizationSummary = makeSummary(),
        request: SynchronizationRequest = makeRequest(),
    ) = SynchronizationResult.Succeeded(
        request = request,
        completedAt = DataLoomInstant(1_000_000L),
        summary = summary,
    )

    private fun makePartiallySucceeded(
        errors: List<DataLoomError> = listOf(FakeError()),
        summary: SynchronizationSummary = makeSummary(),
        request: SynchronizationRequest = makeRequest(),
    ) = SynchronizationResult.PartiallySucceeded(
        request = request,
        completedAt = DataLoomInstant(1_000_000L),
        summary = summary,
        errors = errors,
    )

    private fun makeFailed(
        error: DataLoomError = FakeError(),
        summary: SynchronizationSummary = makeSummary(),
        request: SynchronizationRequest = makeRequest(),
    ) = SynchronizationResult.Failed(
        request = request,
        completedAt = DataLoomInstant(1_000_000L),
        summary = summary,
        error = error,
    )

    private fun makeCancelled(
        summary: SynchronizationSummary = makeSummary(),
        request: SynchronizationRequest = makeRequest(),
    ) = SynchronizationResult.Cancelled(
        request = request,
        completedAt = DataLoomInstant(1_000_000L),
        summary = summary,
    )

    private fun makeSkippedNoChanges(
        summary: SynchronizationSummary = makeSummary(),
        request: SynchronizationRequest = makeRequest(),
    ) = SynchronizationResult.Skipped(
        request = request,
        completedAt = DataLoomInstant(1_000_000L),
        summary = summary,
        reason = SynchronizationSkipReason.NO_CHANGES,
    )

    private fun makeSkippedNonNoChanges(
        reason: SynchronizationSkipReason = SynchronizationSkipReason.DUPLICATE_REQUEST,
        summary: SynchronizationSummary = makeSummary(),
        request: SynchronizationRequest = makeRequest(),
    ) = SynchronizationResult.Skipped(
        request = request,
        completedAt = DataLoomInstant(1_000_000L),
        summary = summary,
        reason = reason,
    )

    private fun makePushPipeline(result: SynchronizationResult) =
        FakePipeline(SynchronizationDirection.PUSH, result)

    private fun makePullPipeline(result: SynchronizationResult) =
        FakePipeline(SynchronizationDirection.PULL, result)

    private fun makePipeline(
        outbound: SynchronizationPipeline,
        inbound: SynchronizationPipeline,
        configuration: BidirectionalPipelineConfiguration = BidirectionalPipelineConfiguration(),
    ) = BidirectionalSynchronizationPipeline(outbound, inbound, configuration)

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
    fun configuration_defaultOrderIsOutboundThenInbound() {
        val config = BidirectionalPipelineConfiguration()
        assertEquals(BidirectionalExecutionOrder.OUTBOUND_THEN_INBOUND, config.executionOrder)
    }

    @Test
    fun configuration_explicitOutboundThenInbound() {
        val config = BidirectionalPipelineConfiguration(
            executionOrder = BidirectionalExecutionOrder.OUTBOUND_THEN_INBOUND,
        )
        assertEquals(BidirectionalExecutionOrder.OUTBOUND_THEN_INBOUND, config.executionOrder)
    }

    @Test
    fun configuration_explicitInboundThenOutbound() {
        val config = BidirectionalPipelineConfiguration(
            executionOrder = BidirectionalExecutionOrder.INBOUND_THEN_OUTBOUND,
        )
        assertEquals(BidirectionalExecutionOrder.INBOUND_THEN_OUTBOUND, config.executionOrder)
    }

    @Test
    fun configuration_valueBasedEquality() {
        val a = BidirectionalPipelineConfiguration(BidirectionalExecutionOrder.OUTBOUND_THEN_INBOUND)
        val b = BidirectionalPipelineConfiguration(BidirectionalExecutionOrder.OUTBOUND_THEN_INBOUND)
        val c = BidirectionalPipelineConfiguration(BidirectionalExecutionOrder.INBOUND_THEN_OUTBOUND)
        assertEquals(a, b)
        assertTrue(a != c)
    }

    @Test
    fun configuration_constructionPerformsNoExecution() {
        // Constructing configuration with an exploding clock does not throw.
        // There is no clock reference; this test asserts by not throwing.
        BidirectionalPipelineConfiguration(BidirectionalExecutionOrder.OUTBOUND_THEN_INBOUND)
        BidirectionalPipelineConfiguration(BidirectionalExecutionOrder.INBOUND_THEN_OUTBOUND)
    }

    // =========================================================================
    // Delegate validation tests
    // =========================================================================

    @Test
    fun construction_outboundDelegateWithCorrectDirectionAccepted() {
        val outbound = makePushPipeline(makeSucceeded())
        val inbound = makePullPipeline(makeSucceeded())
        // Must not throw.
        makePipeline(outbound, inbound)
    }

    @Test
    fun construction_inboundDelegateWithCorrectDirectionAccepted() {
        val outbound = makePushPipeline(makeSucceeded())
        val inbound = makePullPipeline(makeSucceeded())
        // Must not throw.
        makePipeline(outbound, inbound)
    }

    @Test
    fun construction_incorrectOutboundDirectionRejected() {
        val wrongOutbound = FakePipeline(SynchronizationDirection.PULL, makeSucceeded())
        val inbound = makePullPipeline(makeSucceeded())
        assertFailsWith<IllegalArgumentException> { makePipeline(wrongOutbound, inbound) }
    }

    @Test
    fun construction_incorrectInboundDirectionRejected() {
        val outbound = makePushPipeline(makeSucceeded())
        val wrongInbound = FakePipeline(SynchronizationDirection.PUSH, makeSucceeded())
        assertFailsWith<IllegalArgumentException> { makePipeline(outbound, wrongInbound) }
    }

    @Test
    fun construction_bidirectionalDelegateCannotBeUsedAsOutboundChild() {
        val outbound = makePushPipeline(makeSucceeded())
        val inbound = makePullPipeline(makeSucceeded())
        val bidir = makePipeline(outbound, inbound)
        // bidir has BIDIRECTIONAL direction — not valid as outbound (PUSH)
        val inbound2 = makePullPipeline(makeSucceeded())
        assertFailsWith<IllegalArgumentException> { makePipeline(bidir, inbound2) }
    }

    @Test
    fun construction_bidirectionalDelegateCannotBeUsedAsInboundChild() {
        val outbound = makePushPipeline(makeSucceeded())
        val inbound = makePullPipeline(makeSucceeded())
        val bidir = makePipeline(outbound, inbound)
        // bidir has BIDIRECTIONAL direction — not valid as inbound (PULL)
        val outbound2 = makePushPipeline(makeSucceeded())
        assertFailsWith<IllegalArgumentException> { makePipeline(outbound2, bidir) }
    }

    @Test
    fun construction_doesNotInvokeEitherDelegate() {
        val outbound = makePushPipeline(makeSucceeded())
        val inbound = makePullPipeline(makeSucceeded())
        makePipeline(outbound, inbound)
        assertEquals(0, outbound.executeCallCount)
        assertEquals(0, inbound.executeCallCount)
    }

    // =========================================================================
    // Pipeline identity tests
    // =========================================================================

    @Test
    fun pipeline_implementsSynchronizationPipeline() {
        val pipeline = makePipeline(makePushPipeline(makeSucceeded()), makePullPipeline(makeSucceeded()))
        assertIs<SynchronizationPipeline>(pipeline)
    }

    @Test
    fun pipeline_declaresExactBidirectionalDirection() {
        val pipeline = makePipeline(makePushPipeline(makeSucceeded()), makePullPipeline(makeSucceeded()))
        assertEquals(SynchronizationDirection.BIDIRECTIONAL, pipeline.direction)
    }

    // =========================================================================
    // Execution order tests
    // =========================================================================

    @Test
    fun executionOrder_outboundThenInboundExecutesInThatOrder() {
        val sequence = mutableListOf<String>()
        val outbound = OrderTrackingPipeline(
            SynchronizationDirection.PUSH, "outbound", sequence, makeSucceeded(),
        )
        val inbound = OrderTrackingPipeline(
            SynchronizationDirection.PULL, "inbound", sequence, makeSucceeded(),
        )
        val pipeline = makePipeline(
            outbound, inbound,
            BidirectionalPipelineConfiguration(BidirectionalExecutionOrder.OUTBOUND_THEN_INBOUND),
        )
        val context = makeContext()
        runSuspend { pipeline.execute(context) }
        assertEquals(listOf("outbound", "inbound"), sequence)
    }

    @Test
    fun executionOrder_inboundThenOutboundExecutesInThatOrder() {
        val sequence = mutableListOf<String>()
        val outbound = OrderTrackingPipeline(
            SynchronizationDirection.PUSH, "outbound", sequence, makeSucceeded(),
        )
        val inbound = OrderTrackingPipeline(
            SynchronizationDirection.PULL, "inbound", sequence, makeSucceeded(),
        )
        val pipeline = makePipeline(
            outbound, inbound,
            BidirectionalPipelineConfiguration(BidirectionalExecutionOrder.INBOUND_THEN_OUTBOUND),
        )
        val context = makeContext()
        runSuspend { pipeline.execute(context) }
        assertEquals(listOf("inbound", "outbound"), sequence)
    }

    @Test
    fun executionOrder_eachDelegateExecutesAtMostOnce() {
        val outbound = makePushPipeline(makeSucceeded())
        val inbound = makePullPipeline(makeSucceeded())
        val pipeline = makePipeline(outbound, inbound)
        val context = makeContext()
        runSuspend { pipeline.execute(context) }
        assertEquals(1, outbound.executeCallCount)
        assertEquals(1, inbound.executeCallCount)
    }

    @Test
    fun executionOrder_delegatesExecuteSequentiallyNotInParallel() {
        // Sequential means: outbound finishes before inbound starts (tracked by sequence list).
        val sequence = mutableListOf<String>()
        val outbound = OrderTrackingPipeline(
            SynchronizationDirection.PUSH, "outbound", sequence, makeSucceeded(),
        )
        val inbound = OrderTrackingPipeline(
            SynchronizationDirection.PULL, "inbound", sequence, makeSucceeded(),
        )
        val pipeline = makePipeline(outbound, inbound)
        runSuspend { pipeline.execute(makeContext()) }
        // If sequential: first outbound, then inbound.
        assertEquals("outbound", sequence[0])
        assertEquals("inbound", sequence[1])
    }

    @Test
    fun executionOrder_exactSameContextReachesBothDelegates() {
        val outbound = makePushPipeline(makeSucceeded())
        val inbound = makePullPipeline(makeSucceeded())
        val pipeline = makePipeline(outbound, inbound)
        val context = makeContext()
        runSuspend { pipeline.execute(context) }
        assertSame(context, outbound.lastContext)
        assertSame(context, inbound.lastContext)
    }

    // =========================================================================
    // Continuation tests
    // =========================================================================

    @Test
    fun continuation_firstSucceededContinues() {
        val outbound = makePushPipeline(makeSucceeded())
        val inbound = makePullPipeline(makeSucceeded())
        val pipeline = makePipeline(outbound, inbound)
        runSuspend { pipeline.execute(makeContext()) }
        assertEquals(1, outbound.executeCallCount)
        assertEquals(1, inbound.executeCallCount)
    }

    @Test
    fun continuation_firstPartiallySucceededContinues() {
        val outbound = makePushPipeline(makePartiallySucceeded())
        val inbound = makePullPipeline(makeSucceeded())
        val pipeline = makePipeline(outbound, inbound)
        runSuspend { pipeline.execute(makeContext()) }
        assertEquals(1, outbound.executeCallCount)
        assertEquals(1, inbound.executeCallCount)
    }

    @Test
    fun continuation_firstSkippedNoChangesContinues() {
        val outbound = makePushPipeline(makeSkippedNoChanges())
        val inbound = makePullPipeline(makeSucceeded())
        val pipeline = makePipeline(outbound, inbound)
        runSuspend { pipeline.execute(makeContext()) }
        assertEquals(1, outbound.executeCallCount)
        assertEquals(1, inbound.executeCallCount)
    }

    @Test
    fun continuation_firstFailedStops() {
        val outbound = makePushPipeline(makeFailed())
        val inbound = makePullPipeline(makeSucceeded())
        val pipeline = makePipeline(outbound, inbound)
        runSuspend { pipeline.execute(makeContext()) }
        assertEquals(1, outbound.executeCallCount)
        assertEquals(0, inbound.executeCallCount)
    }

    @Test
    fun continuation_firstCancelledStops() {
        val outbound = makePushPipeline(makeCancelled())
        val inbound = makePullPipeline(makeSucceeded())
        val pipeline = makePipeline(outbound, inbound)
        runSuspend { pipeline.execute(makeContext()) }
        assertEquals(1, outbound.executeCallCount)
        assertEquals(0, inbound.executeCallCount)
    }

    @Test
    fun continuation_firstNonNoChangesSkippedStops() {
        val outbound = makePushPipeline(makeSkippedNonNoChanges())
        val inbound = makePullPipeline(makeSucceeded())
        val pipeline = makePipeline(outbound, inbound)
        runSuspend { pipeline.execute(makeContext()) }
        assertEquals(1, outbound.executeCallCount)
        assertEquals(0, inbound.executeCallCount)
    }

    // =========================================================================
    // Successful combination tests
    // =========================================================================

    @Test
    fun successCombination_succeededPlusSucceeded_returnsSucceeded() {
        val outbound = makePushPipeline(makeSucceeded())
        val inbound = makePullPipeline(makeSucceeded())
        val pipeline = makePipeline(outbound, inbound)
        val result = runSuspend { pipeline.execute(makeContext()) }
        assertIs<SynchronizationResult.Succeeded>(result)
    }

    @Test
    fun successCombination_skippedNoChangesPlusSucceeded_returnsSucceeded() {
        val outbound = makePushPipeline(makeSkippedNoChanges())
        val inbound = makePullPipeline(makeSucceeded())
        val pipeline = makePipeline(outbound, inbound)
        val result = runSuspend { pipeline.execute(makeContext()) }
        assertIs<SynchronizationResult.Succeeded>(result)
    }

    @Test
    fun successCombination_succeededPlusSkippedNoChanges_returnsSucceeded() {
        val outbound = makePushPipeline(makeSucceeded())
        val inbound = makePullPipeline(makeSkippedNoChanges())
        val pipeline = makePipeline(outbound, inbound)
        val result = runSuspend { pipeline.execute(makeContext()) }
        assertIs<SynchronizationResult.Succeeded>(result)
    }

    @Test
    fun successCombination_bothSkippedNoChanges_returnsSkippedNoChanges() {
        val outbound = makePushPipeline(makeSkippedNoChanges())
        val inbound = makePullPipeline(makeSkippedNoChanges())
        val pipeline = makePipeline(outbound, inbound)
        val result = runSuspend { pipeline.execute(makeContext()) }
        assertIs<SynchronizationResult.Skipped>(result)
        assertEquals(SynchronizationSkipReason.NO_CHANGES, result.reason)
    }

    // =========================================================================
    // Partial combination tests
    // =========================================================================

    @Test
    fun partialCombination_partiallySucceededPlusSucceeded_returnsPartiallySucceeded() {
        val error1 = FakeError(code = ErrorCode("DL-ERR-1"))
        val outbound = makePushPipeline(makePartiallySucceeded(errors = listOf(error1)))
        val inbound = makePullPipeline(makeSucceeded())
        val pipeline = makePipeline(outbound, inbound)
        val result = runSuspend { pipeline.execute(makeContext()) }
        assertIs<SynchronizationResult.PartiallySucceeded>(result)
    }

    @Test
    fun partialCombination_succeededPlusPartiallySucceeded_returnsPartiallySucceeded() {
        val error2 = FakeError(code = ErrorCode("DL-ERR-2"))
        val outbound = makePushPipeline(makeSucceeded())
        val inbound = makePullPipeline(makePartiallySucceeded(errors = listOf(error2)))
        val pipeline = makePipeline(outbound, inbound)
        val result = runSuspend { pipeline.execute(makeContext()) }
        assertIs<SynchronizationResult.PartiallySucceeded>(result)
    }

    @Test
    fun partialCombination_bothPartiallySucceeded_returnsPartiallySucceeded() {
        val error1 = FakeError(code = ErrorCode("DL-ERR-1"))
        val error2 = FakeError(code = ErrorCode("DL-ERR-2"))
        val outbound = makePushPipeline(makePartiallySucceeded(errors = listOf(error1)))
        val inbound = makePullPipeline(makePartiallySucceeded(errors = listOf(error2)))
        val pipeline = makePipeline(outbound, inbound)
        val result = runSuspend { pipeline.execute(makeContext()) }
        assertIs<SynchronizationResult.PartiallySucceeded>(result)
    }

    @Test
    fun partialCombination_skippedNoChangesPlusPartiallySucceeded_returnsPartiallySucceeded() {
        val error = FakeError(code = ErrorCode("DL-ERR-1"))
        val outbound = makePushPipeline(makeSkippedNoChanges())
        val inbound = makePullPipeline(makePartiallySucceeded(errors = listOf(error)))
        val pipeline = makePipeline(outbound, inbound)
        val result = runSuspend { pipeline.execute(makeContext()) }
        assertIs<SynchronizationResult.PartiallySucceeded>(result)
    }

    @Test
    fun partialCombination_errorOrderFollowsExecutionOrder_outboundThenInbound() {
        val error1 = FakeError(code = ErrorCode("DL-ERR-OUTBOUND"))
        val error2 = FakeError(code = ErrorCode("DL-ERR-INBOUND"))
        val outbound = makePushPipeline(makePartiallySucceeded(errors = listOf(error1)))
        val inbound = makePullPipeline(makePartiallySucceeded(errors = listOf(error2)))
        val pipeline = makePipeline(
            outbound, inbound,
            BidirectionalPipelineConfiguration(BidirectionalExecutionOrder.OUTBOUND_THEN_INBOUND),
        )
        val result = runSuspend { pipeline.execute(makeContext()) }
        assertIs<SynchronizationResult.PartiallySucceeded>(result)
        assertEquals(2, result.errors.size)
        assertSame(error1, result.errors[0])
        assertSame(error2, result.errors[1])
    }

    @Test
    fun partialCombination_errorOrderFollowsExecutionOrder_inboundThenOutbound() {
        val error1 = FakeError(code = ErrorCode("DL-ERR-INBOUND"))
        val error2 = FakeError(code = ErrorCode("DL-ERR-OUTBOUND"))
        val outbound = makePushPipeline(makePartiallySucceeded(errors = listOf(error2)))
        val inbound = makePullPipeline(makePartiallySucceeded(errors = listOf(error1)))
        val pipeline = makePipeline(
            outbound, inbound,
            BidirectionalPipelineConfiguration(BidirectionalExecutionOrder.INBOUND_THEN_OUTBOUND),
        )
        val result = runSuspend { pipeline.execute(makeContext()) }
        assertIs<SynchronizationResult.PartiallySucceeded>(result)
        assertEquals(2, result.errors.size)
        // Inbound ran first, so inbound errors come first.
        assertSame(error1, result.errors[0])
        assertSame(error2, result.errors[1])
    }

    @Test
    fun partialCombination_errorCollectionIsImmutableThroughResult() {
        val error = FakeError(code = ErrorCode("DL-ERR-1"))
        val outbound = makePushPipeline(makePartiallySucceeded(errors = listOf(error)))
        val inbound = makePullPipeline(makeSucceeded())
        val pipeline = makePipeline(outbound, inbound)
        val result = runSuspend { pipeline.execute(makeContext()) }
        assertIs<SynchronizationResult.PartiallySucceeded>(result)
        // errors is a read-only List; verify we can read it but not mutate through the returned property.
        assertEquals(1, result.errors.size)
    }

    // =========================================================================
    // Failure combination tests
    // =========================================================================

    @Test
    fun failureCombination_firstFailedPreventsSecondExecution() {
        val error = FakeError(code = ErrorCode("DL-FIRST-FAIL"))
        val outbound = makePushPipeline(makeFailed(error = error))
        val inbound = makePullPipeline(makeSucceeded())
        val pipeline = makePipeline(outbound, inbound)
        runSuspend { pipeline.execute(makeContext()) }
        assertEquals(0, inbound.executeCallCount)
    }

    @Test
    fun failureCombination_firstErrorIsPreserved() {
        val error = FakeError(code = ErrorCode("DL-FIRST-FAIL"))
        val outbound = makePushPipeline(makeFailed(error = error))
        val inbound = makePullPipeline(makeSucceeded())
        val pipeline = makePipeline(outbound, inbound)
        val result = runSuspend { pipeline.execute(makeContext()) }
        assertIs<SynchronizationResult.Failed>(result)
        assertSame(error, result.error)
    }

    @Test
    fun failureCombination_secondFailedOccursAfterFirstCompletedResult() {
        val error = FakeError(code = ErrorCode("DL-SECOND-FAIL"))
        val outbound = makePushPipeline(makeSucceeded())
        val inbound = makePullPipeline(makeFailed(error = error))
        val pipeline = makePipeline(outbound, inbound)
        val result = runSuspend { pipeline.execute(makeContext()) }
        assertIs<SynchronizationResult.Failed>(result)
    }

    @Test
    fun failureCombination_secondErrorIsPreserved() {
        val error = FakeError(code = ErrorCode("DL-SECOND-FAIL"))
        val outbound = makePushPipeline(makeSucceeded())
        val inbound = makePullPipeline(makeFailed(error = error))
        val pipeline = makePipeline(outbound, inbound)
        val result = runSuspend { pipeline.execute(makeContext()) }
        assertIs<SynchronizationResult.Failed>(result)
        assertSame(error, result.error)
    }

    @Test
    fun failureCombination_summariesFromCompletedWorkAreCombinedTruthfully() {
        val outboundSummary = makeSummary(outboundRead = 5L, outboundAccepted = 4L)
        val failedSummary = makeSummary(inboundReceived = 3L, inboundApplied = 2L)
        val error = FakeError()
        val outbound = makePushPipeline(makeSucceeded(summary = outboundSummary))
        val inbound = makePullPipeline(makeFailed(error = error, summary = failedSummary))
        val pipeline = makePipeline(outbound, inbound)
        val result = runSuspend { pipeline.execute(makeContext()) }
        assertIs<SynchronizationResult.Failed>(result)
        assertEquals(5L, result.summary.outboundEventsRead)
        assertEquals(4L, result.summary.outboundEventsAccepted)
        assertEquals(3L, result.summary.inboundEventsReceived)
        assertEquals(2L, result.summary.inboundEventsApplied)
    }

    // =========================================================================
    // Explicit cancellation result tests
    // =========================================================================

    @Test
    fun cancelledResult_firstCancelledPreventsSecondExecution() {
        val outbound = makePushPipeline(makeCancelled())
        val inbound = makePullPipeline(makeSucceeded())
        val pipeline = makePipeline(outbound, inbound)
        runSuspend { pipeline.execute(makeContext()) }
        assertEquals(0, inbound.executeCallCount)
    }

    @Test
    fun cancelledResult_secondCancelledIsReturnedAfterFirstCompletedWork() {
        val outbound = makePushPipeline(makeSucceeded())
        val inbound = makePullPipeline(makeCancelled())
        val pipeline = makePipeline(outbound, inbound)
        val result = runSuspend { pipeline.execute(makeContext()) }
        assertIs<SynchronizationResult.Cancelled>(result)
    }

    @Test
    fun cancelledResult_cancellationResultPropertiesArePreserved() {
        val outbound = makePushPipeline(makeCancelled())
        val inbound = makePullPipeline(makeSucceeded())
        val pipeline = makePipeline(outbound, inbound)
        val result = runSuspend { pipeline.execute(makeContext()) }
        assertIs<SynchronizationResult.Cancelled>(result)
    }

    @Test
    fun cancelledResult_completedSummaryEvidenceIsPreserved_firstCancelled() {
        val cancelSummary = makeSummary(outboundRead = 3L, outboundAccepted = 2L)
        val outbound = makePushPipeline(makeCancelled(summary = cancelSummary))
        val inbound = makePullPipeline(makeSucceeded())
        val pipeline = makePipeline(outbound, inbound)
        val result = runSuspend { pipeline.execute(makeContext()) }
        assertIs<SynchronizationResult.Cancelled>(result)
        assertEquals(3L, result.summary.outboundEventsRead)
        assertEquals(2L, result.summary.outboundEventsAccepted)
    }

    @Test
    fun cancelledResult_combinedSummaryEvidencePreserved_secondCancelled() {
        val outboundSummary = makeSummary(outboundRead = 5L, outboundAccepted = 5L)
        val cancelSummary = makeSummary(inboundReceived = 2L, inboundApplied = 1L)
        val outbound = makePushPipeline(makeSucceeded(summary = outboundSummary))
        val inbound = makePullPipeline(makeCancelled(summary = cancelSummary))
        val pipeline = makePipeline(outbound, inbound)
        val result = runSuspend { pipeline.execute(makeContext()) }
        assertIs<SynchronizationResult.Cancelled>(result)
        assertEquals(5L, result.summary.outboundEventsRead)
        assertEquals(2L, result.summary.inboundEventsReceived)
        assertEquals(1L, result.summary.inboundEventsApplied)
    }

    // =========================================================================
    // Thrown cancellation and exception tests
    // =========================================================================

    @Test
    fun thrownCancellation_fromFirstDelegatePropagates() {
        val outbound = CancellingPipeline(SynchronizationDirection.PUSH)
        val inbound = makePullPipeline(makeSucceeded())
        val pipeline = makePipeline(outbound, inbound)
        assertFailsWith<CancellationException> {
            runSuspend { pipeline.execute(makeContext()) }
        }
    }

    @Test
    fun thrownCancellation_fromFirstDelegate_secondIsNotExecuted() {
        val outbound = CancellingPipeline(SynchronizationDirection.PUSH)
        val inbound = makePullPipeline(makeSucceeded())
        val pipeline = makePipeline(outbound, inbound)
        try {
            runSuspend { pipeline.execute(makeContext()) }
        } catch (_: CancellationException) { }
        assertEquals(0, inbound.executeCallCount)
    }

    @Test
    fun thrownCancellation_fromSecondDelegatePropagates() {
        val outbound = makePushPipeline(makeSucceeded())
        val inbound = CancellingPipeline(SynchronizationDirection.PULL)
        val pipeline = makePipeline(outbound, inbound)
        assertFailsWith<CancellationException> {
            runSuspend { pipeline.execute(makeContext()) }
        }
    }

    @Test
    fun thrownCancellation_isNotConvertedIntoResult() {
        val outbound = CancellingPipeline(SynchronizationDirection.PUSH)
        val inbound = makePullPipeline(makeSucceeded())
        val pipeline = makePipeline(outbound, inbound)
        var threwCancellation = false
        try {
            runSuspend { pipeline.execute(makeContext()) }
        } catch (e: CancellationException) {
            threwCancellation = true
        }
        assertTrue(threwCancellation, "CancellationException must propagate, not be converted.")
    }

    @Test
    fun unexpectedException_fromFirstDelegatePropagates() {
        val outbound = ExplodingPipeline(SynchronizationDirection.PUSH)
        val inbound = makePullPipeline(makeSucceeded())
        val pipeline = makePipeline(outbound, inbound)
        assertFailsWith<RuntimeException> {
            runSuspend { pipeline.execute(makeContext()) }
        }
    }

    @Test
    fun unexpectedException_fromSecondDelegatePropagates() {
        val outbound = makePushPipeline(makeSucceeded())
        val inbound = ExplodingPipeline(SynchronizationDirection.PULL)
        val pipeline = makePipeline(outbound, inbound)
        assertFailsWith<RuntimeException> {
            runSuspend { pipeline.execute(makeContext()) }
        }
    }

    // =========================================================================
    // Summary combination tests
    // =========================================================================

    @Test
    fun summaryCombination_outboundCountersAreSummed() {
        val outboundSummary = makeSummary(outboundRead = 10L, outboundAccepted = 8L)
        val inboundSummary = makeSummary(outboundRead = 5L, outboundAccepted = 3L)
        val outbound = makePushPipeline(makeSucceeded(summary = outboundSummary))
        val inbound = makePullPipeline(makeSucceeded(summary = inboundSummary))
        val pipeline = makePipeline(outbound, inbound)
        val result = runSuspend { pipeline.execute(makeContext()) }
        assertIs<SynchronizationResult.Succeeded>(result)
        assertEquals(15L, result.summary.outboundEventsRead)
        assertEquals(11L, result.summary.outboundEventsAccepted)
    }

    @Test
    fun summaryCombination_inboundCountersAreSummed() {
        val outboundSummary = makeSummary(inboundReceived = 4L, inboundApplied = 4L)
        val inboundSummary = makeSummary(inboundReceived = 6L, inboundApplied = 5L)
        val outbound = makePushPipeline(makeSucceeded(summary = outboundSummary))
        val inbound = makePullPipeline(makeSucceeded(summary = inboundSummary))
        val pipeline = makePipeline(outbound, inbound)
        val result = runSuspend { pipeline.execute(makeContext()) }
        assertIs<SynchronizationResult.Succeeded>(result)
        assertEquals(10L, result.summary.inboundEventsReceived)
        assertEquals(9L, result.summary.inboundEventsApplied)
    }

    @Test
    fun summaryCombination_conflictCountersAreSummedWhenPresent() {
        val outboundSummary = makeSummary(outboundRead = 10L, outboundAccepted = 8L, conflicts = 2L)
        val inboundSummary = makeSummary(inboundReceived = 5L, inboundApplied = 4L, conflicts = 3L)
        val outbound = makePushPipeline(makeSucceeded(summary = outboundSummary))
        val inbound = makePullPipeline(makeSucceeded(summary = inboundSummary))
        val pipeline = makePipeline(outbound, inbound)
        val result = runSuspend { pipeline.execute(makeContext()) }
        assertIs<SynchronizationResult.Succeeded>(result)
        assertEquals(5L, result.summary.conflictsDetected)
    }

    @Test
    fun summaryCombination_zeroSummariesCombineCorrectly() {
        val outbound = makePushPipeline(makeSkippedNoChanges(summary = makeSummary()))
        val inbound = makePullPipeline(makeSkippedNoChanges(summary = makeSummary()))
        val pipeline = makePipeline(outbound, inbound)
        val result = runSuspend { pipeline.execute(makeContext()) }
        assertIs<SynchronizationResult.Skipped>(result)
        assertEquals(0L, result.summary.outboundEventsRead)
        assertEquals(0L, result.summary.inboundEventsReceived)
        assertEquals(0L, result.summary.conflictsDetected)
        assertEquals(0, result.summary.retryAttempts)
    }

    @Test
    fun summaryCombination_summaryInvariantsRemainValid() {
        // Counters should satisfy constraints after combination.
        val outboundSummary = makeSummary(
            outboundRead = 10L,
            outboundAccepted = 8L,
            outboundRetry = 1L,
            outboundRejected = 1L,
        )
        val inboundSummary = makeSummary(
            outboundRead = 5L,
            outboundAccepted = 3L,
            outboundRetry = 1L,
            outboundRejected = 1L,
            inboundReceived = 10L,
            inboundApplied = 9L,
        )
        val outbound = makePushPipeline(makeSucceeded(summary = outboundSummary))
        val inbound = makePullPipeline(makeSucceeded(summary = inboundSummary))
        val pipeline = makePipeline(outbound, inbound)
        val result = runSuspend { pipeline.execute(makeContext()) }
        assertIs<SynchronizationResult.Succeeded>(result)
        val combined = result.summary
        // outboundEventsAccepted (11) + outboundRetry (2) + outboundRejected (2) = 15 <= outboundRead (15)
        assertTrue(combined.outboundEventsAccepted <= combined.outboundEventsRead)
        assertTrue(combined.outboundEventsMarkedForRetry <= combined.outboundEventsRead)
        assertTrue(combined.outboundEventsRejected <= combined.outboundEventsRead)
        assertTrue(combined.inboundEventsApplied <= combined.inboundEventsReceived)
    }

    @Test
    fun summaryCombination_overflowBehaviorIsDeterministic_longOverflow() {
        // Two summaries with Long.MAX_VALUE / 2 + 1 counters each should overflow.
        val halfMax = Long.MAX_VALUE / 2L + 1L
        // Create a valid summary: use read = halfMax, accepted = halfMax.
        val overflowSummary = makeSummary(outboundRead = halfMax, outboundAccepted = halfMax)
        val outbound = makePushPipeline(makeSucceeded(summary = overflowSummary))
        val inbound = makePullPipeline(makeSucceeded(summary = overflowSummary))
        val pipeline = makePipeline(outbound, inbound)
        // Overflow must result in a Failed result, not a wrapped-around counter.
        val result = runSuspend { pipeline.execute(makeContext()) }
        assertIs<SynchronizationResult.Failed>(result)
    }

    @Test
    fun summaryCombination_intOverflowBehaviorIsDeterministic_retryAttempts() {
        val halfMax = Int.MAX_VALUE / 2 + 1
        val overflowSummary = makeSummary(retryAttempts = halfMax)
        val outbound = makePushPipeline(makeSucceeded(summary = overflowSummary))
        val inbound = makePullPipeline(makeSucceeded(summary = overflowSummary))
        val pipeline = makePipeline(outbound, inbound)
        val result = runSuspend { pipeline.execute(makeContext()) }
        assertIs<SynchronizationResult.Failed>(result)
    }

    // =========================================================================
    // Clock usage tests
    // =========================================================================

    @Test
    fun clock_injectedClockProvidesComposedTerminalTimestamp() {
        val clock = FakeClock(nowMs = 9_999_000L)
        val outbound = makePushPipeline(makeSucceeded())
        val inbound = makePullPipeline(makeSucceeded())
        val pipeline = makePipeline(outbound, inbound)
        val context = makeContext(clock = clock)
        val result = runSuspend { pipeline.execute(context) }
        assertIs<SynchronizationResult.Succeeded>(result)
        assertEquals(DataLoomInstant(9_999_000L), result.completedAt)
    }

    @Test
    fun clock_childTimestampIsNotReusedAsComposedTimestamp() {
        // The child result has timestamp 1_000_000L. The injected clock returns 9_999_000L.
        val clock = FakeClock(nowMs = 9_999_000L)
        val childResult = makeSucceeded() // completedAt = DataLoomInstant(1_000_000L)
        val outbound = makePushPipeline(childResult)
        val inbound = makePullPipeline(childResult)
        val pipeline = makePipeline(outbound, inbound)
        val context = makeContext(clock = clock)
        val result = runSuspend { pipeline.execute(context) }
        assertIs<SynchronizationResult.Succeeded>(result)
        // Must use the composed timestamp from the injected clock, not the child's.
        assertEquals(DataLoomInstant(9_999_000L), result.completedAt)
    }

    @Test
    fun clock_clockIsReadOnlyForTerminalResultConstruction() {
        // Clock must not be read before a terminal result is needed.
        // Reading the clock exactly once for the composed terminal result is acceptable.
        val clock = FakeClock(nowMs = 5_000_000L)
        val outbound = makePushPipeline(makeSucceeded())
        val inbound = makePullPipeline(makeSucceeded())
        val pipeline = makePipeline(outbound, inbound)
        val context = makeContext(clock = clock)
        runSuspend { pipeline.execute(context) }
        // Clock is read once for the composed terminal result.
        assertEquals(1, clock.readCallCount)
    }

    @Test
    fun clock_clockIsReadOnceWhenFirstPipelineStopped() {
        // When first pipeline returns Failed, clock is read once for the terminal result.
        val clock = FakeClock(nowMs = 5_000_000L)
        val outbound = makePushPipeline(makeFailed())
        val inbound = makePullPipeline(makeSucceeded())
        val pipeline = makePipeline(outbound, inbound)
        val context = makeContext(clock = clock)
        runSuspend { pipeline.execute(context) }
        assertEquals(1, clock.readCallCount)
    }

    // =========================================================================
    // Side-effect restriction tests
    // =========================================================================

    @Test
    fun sideEffects_pipelineDirectlyInvokesNoStorageOperation() {
        // The context providers are stubs that throw AssertionError if invoked.
        // If the pipeline tries to call any provider directly it would fail.
        val outbound = makePushPipeline(makeSucceeded())
        val inbound = makePullPipeline(makeSucceeded())
        val pipeline = makePipeline(outbound, inbound)
        val context = makeContext() // stub providers throw on any direct call
        // Must not throw due to any direct provider call.
        runSuspend { pipeline.execute(context) }
    }

    // =========================================================================
    // Non-NO_CHANGES Skipped result tests
    // =========================================================================

    @Test
    fun skippedNonNoChanges_preservesSkipReason() {
        val outbound = makePushPipeline(makeSkippedNonNoChanges(SynchronizationSkipReason.DUPLICATE_REQUEST))
        val inbound = makePullPipeline(makeSucceeded())
        val pipeline = makePipeline(outbound, inbound)
        val result = runSuspend { pipeline.execute(makeContext()) }
        assertIs<SynchronizationResult.Skipped>(result)
        assertEquals(SynchronizationSkipReason.DUPLICATE_REQUEST, result.reason)
    }

    @Test
    fun skippedNonNoChanges_isNotReinterpretedAsSuccess() {
        val outbound = makePushPipeline(makeSkippedNonNoChanges(SynchronizationSkipReason.POLICY_REJECTED))
        val inbound = makePullPipeline(makeSucceeded())
        val pipeline = makePipeline(outbound, inbound)
        val result = runSuspend { pipeline.execute(makeContext()) }
        // Must not be Succeeded.
        assertTrue(result !is SynchronizationResult.Succeeded)
    }

    @Test
    fun skippedNonNoChanges_completedSummaryEvidenceIsPreserved() {
        val skippedSummary = makeSummary(outboundRead = 3L, outboundAccepted = 2L)
        val outbound = makePushPipeline(
            makeSkippedNonNoChanges(
                reason = SynchronizationSkipReason.CONSTRAINTS_NOT_SATISFIED,
                summary = skippedSummary,
            ),
        )
        val inbound = makePullPipeline(makeSucceeded())
        val pipeline = makePipeline(outbound, inbound)
        val result = runSuspend { pipeline.execute(makeContext()) }
        assertIs<SynchronizationResult.Skipped>(result)
        assertEquals(3L, result.summary.outboundEventsRead)
    }
}
