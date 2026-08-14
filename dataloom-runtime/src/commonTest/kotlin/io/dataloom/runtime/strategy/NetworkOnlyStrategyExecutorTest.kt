package io.dataloom.runtime.strategy

import io.dataloom.api.change.ChangeEvent
import io.dataloom.api.change.ChangeSet
import io.dataloom.api.model.ChangeOperation
import io.dataloom.api.connectivity.ConnectivityProvider
import io.dataloom.api.context.ExecutionContext
import io.dataloom.api.error.DataLoomError
import io.dataloom.api.error.ErrorCategory
import io.dataloom.api.error.ErrorCode
import io.dataloom.api.error.ErrorSeverity
import io.dataloom.api.error.Recoverability
import io.dataloom.api.execution.StrategyProviderSet
import io.dataloom.api.identifier.ChangeEventId
import io.dataloom.api.identifier.ChangeSetId
import io.dataloom.api.identifier.CorrelationId
import io.dataloom.api.identifier.EntityId
import io.dataloom.api.identifier.EntityType
import io.dataloom.api.identifier.ExecutionId
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
import io.dataloom.api.queue.QueueProvider
import io.dataloom.api.scheduling.SchedulerProvider
import io.dataloom.api.storage.StorageProvider
import io.dataloom.api.strategy.NetworkOnlyStrategyProfile
import io.dataloom.api.strategy.StrategyConfigurationVersion
import io.dataloom.api.strategy.StrategyConnectivity
import io.dataloom.api.strategy.StrategyDecisionId
import io.dataloom.api.strategy.StrategyOperation
import io.dataloom.api.strategy.StrategyOperationInput
import io.dataloom.api.strategy.StrategyPlanId
import io.dataloom.api.strategy.StrategyProfileId
import io.dataloom.api.strategy.StrategyProviderHealth
import io.dataloom.api.strategy.StrategyRuntimeEvidence
import io.dataloom.api.strategy.StrategySynchronizationRequest
import io.dataloom.api.strategy.StrategyTransportOutput
import io.dataloom.api.synchronization.ChangeAcknowledgementStatus
import io.dataloom.api.synchronization.ChangeEventAcknowledgement
import io.dataloom.api.synchronization.ChangeSetAcknowledgement
import io.dataloom.api.time.DataLoomClock
import io.dataloom.api.time.DataLoomInstant
import io.dataloom.api.transport.PullChangesRequest
import io.dataloom.api.transport.PullChangesResult
import io.dataloom.api.transport.PushChangesRequest
import io.dataloom.api.transport.TransportProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest

/**
 * Direct unit tests for [NetworkOnlyStrategyExecutor].
 *
 * Previously this class had zero test coverage anywhere in the suite — the
 * only existing exercise of network-only strategy execution was two
 * provider-protection-focused tests in `DataLoomBuilderProtectedStrategyTest`
 * covering PULL only. This file covers PUSH, PULL, and BIDIRECTIONAL
 * directly against the executor, plus the acknowledgement-validation logic
 * that has no other coverage at all.
 *
 * [evaluation] is opaque pass-through data to this executor (it never
 * branches on it — only [StrategySynchronizationRequest.request]'s direction
 * and [StrategyOperationInput.DirectTransport] matter), so these tests use a
 * real [BuiltInSynchronizationStrategyEvaluator] result rather than a
 * hand-built one, for a more realistic integration than mocking it.
 */
class NetworkOnlyStrategyExecutorTest {

    private val now = DataLoomInstant(epochMilliseconds = 5_000L)
    private val clock = FixedDataLoomClock(now)
    private val evaluator = BuiltInSynchronizationStrategyEvaluator()
    private val executor = NetworkOnlyStrategyExecutor(clock)

    private val changeSet = ChangeSet(
        id = ChangeSetId("change-set-1"),
        events = listOf(
            ChangeEvent(
                id = ChangeEventId("event-1"),
                entity = entityReference("1"),
                operation = ChangeOperation.CREATE,
            ),
            ChangeEvent(
                id = ChangeEventId("event-2"),
                entity = entityReference("2"),
                operation = ChangeOperation.UPDATE,
            ),
        ),
    )

    // -------------------------------------------------------------------------
    // PUSH
    // -------------------------------------------------------------------------

    @Test
    fun pushWithAMatchingAcknowledgementSucceeds() = runTest {
        val ack = acknowledgementFor(changeSet, ChangeAcknowledgementStatus.ACCEPTED)
        val transport = FakeTransportProvider(pushResult = ProviderOperationResult.Success(ack))
        val result = executor.execute(
            request = networkOnlyRequest(SynchronizationDirection.PUSH, outboundChangeSet = changeSet),
            evaluation = evaluationFor(SynchronizationDirection.PUSH),
            providers = providerSet(transport),
        )
        val executed = assertIs<StrategySynchronizationExecutionResult.Executed>(result)
        val output = assertIs<StrategyTransportOutput.Pushed>(executed.output)
        assertEquals(ack, output.acknowledgement)
        assertEquals(now, executed.completedAt)
        assertEquals(1, transport.pushCalls)
        assertEquals(0, transport.pullCalls)
    }

    @Test
    fun pushWhenTransportFailsPropagatesTheTransportError() = runTest {
        val error = testError("PUSH_UNAVAILABLE")
        val transport = FakeTransportProvider(pushResult = ProviderOperationResult.Failure(error))
        val result = executor.execute(
            request = networkOnlyRequest(SynchronizationDirection.PUSH, outboundChangeSet = changeSet),
            evaluation = evaluationFor(SynchronizationDirection.PUSH),
            providers = providerSet(transport),
        )
        val failed = assertIs<StrategySynchronizationExecutionResult.Failed>(result)
        assertEquals(error, failed.error)
        assertTrue(failed.transportAttempted)
        assertTrue(failed.completedOperations.isEmpty())
        assertNull(failed.partialOutput)
    }

    @Test
    fun pushRejectsAnAcknowledgementForADifferentChangeSet() = runTest {
        val wrongAck = ChangeSetAcknowledgement(
            changeSetId = ChangeSetId("some-other-change-set"),
            events = listOf(ChangeEventAcknowledgement(ChangeEventId("event-1"), ChangeAcknowledgementStatus.ACCEPTED)),
        )
        val transport = FakeTransportProvider(pushResult = ProviderOperationResult.Success(wrongAck))
        val result = executor.execute(
            request = networkOnlyRequest(SynchronizationDirection.PUSH, outboundChangeSet = changeSet),
            evaluation = evaluationFor(SynchronizationDirection.PUSH),
            providers = providerSet(transport),
        )
        val failed = assertIs<StrategySynchronizationExecutionResult.Failed>(result)
        assertEquals("DL-STRATEGY-NETWORK-ACK-CHANGESET-MISMATCH", failed.error.code.value)
    }

    @Test
    fun pushRejectsAnAcknowledgementReferencingAnUnknownEvent() = runTest {
        val ackWithExtra = ChangeSetAcknowledgement(
            changeSetId = changeSet.id,
            events = listOf(
                ChangeEventAcknowledgement(ChangeEventId("event-1"), ChangeAcknowledgementStatus.ACCEPTED),
                ChangeEventAcknowledgement(ChangeEventId("event-2"), ChangeAcknowledgementStatus.ACCEPTED),
                ChangeEventAcknowledgement(ChangeEventId("event-never-pushed"), ChangeAcknowledgementStatus.ACCEPTED),
            ),
        )
        val transport = FakeTransportProvider(pushResult = ProviderOperationResult.Success(ackWithExtra))
        val result = executor.execute(
            request = networkOnlyRequest(SynchronizationDirection.PUSH, outboundChangeSet = changeSet),
            evaluation = evaluationFor(SynchronizationDirection.PUSH),
            providers = providerSet(transport),
        )
        val failed = assertIs<StrategySynchronizationExecutionResult.Failed>(result)
        assertEquals("DL-STRATEGY-NETWORK-ACK-UNKNOWN-EVENT", failed.error.code.value)
    }

    @Test
    fun pushRejectsAnAcknowledgementMissingAPushedEvent() = runTest {
        val incompleteAck = ChangeSetAcknowledgement(
            changeSetId = changeSet.id,
            events = listOf(ChangeEventAcknowledgement(ChangeEventId("event-1"), ChangeAcknowledgementStatus.ACCEPTED)),
        )
        val transport = FakeTransportProvider(pushResult = ProviderOperationResult.Success(incompleteAck))
        val result = executor.execute(
            request = networkOnlyRequest(SynchronizationDirection.PUSH, outboundChangeSet = changeSet),
            evaluation = evaluationFor(SynchronizationDirection.PUSH),
            providers = providerSet(transport),
        )
        val failed = assertIs<StrategySynchronizationExecutionResult.Failed>(result)
        assertEquals("DL-STRATEGY-NETWORK-ACK-MISSING-EVENT", failed.error.code.value)
    }

    // -------------------------------------------------------------------------
    // PULL
    // -------------------------------------------------------------------------

    @Test
    fun pullWithNoChangesSucceeds() = runTest {
        val transport = FakeTransportProvider(
            pullResult = ProviderOperationResult.Success(PullChangesResult.NoChanges()),
        )
        val result = executor.execute(
            request = networkOnlyRequest(SynchronizationDirection.PULL),
            evaluation = evaluationFor(SynchronizationDirection.PULL),
            providers = providerSet(transport),
        )
        val executed = assertIs<StrategySynchronizationExecutionResult.Executed>(result)
        val output = assertIs<StrategyTransportOutput.Pulled>(executed.output)
        assertIs<PullChangesResult.NoChanges>(output.result)
        assertEquals(0, transport.pushCalls)
        assertEquals(1, transport.pullCalls)
    }

    @Test
    fun pullWithChangesSucceeds() = runTest {
        val transport = FakeTransportProvider(
            pullResult = ProviderOperationResult.Success(
                PullChangesResult.Changes(changeSet = changeSet, hasMore = false),
            ),
        )
        val result = executor.execute(
            request = networkOnlyRequest(SynchronizationDirection.PULL),
            evaluation = evaluationFor(SynchronizationDirection.PULL),
            providers = providerSet(transport),
        )
        val executed = assertIs<StrategySynchronizationExecutionResult.Executed>(result)
        val output = assertIs<StrategyTransportOutput.Pulled>(executed.output)
        val changes = assertIs<PullChangesResult.Changes>(output.result)
        assertEquals(changeSet, changes.changeSet)
    }

    @Test
    fun pullWhenTransportFailsPropagatesTheTransportError() = runTest {
        val error = testError("PULL_UNAVAILABLE")
        val transport = FakeTransportProvider(pullResult = ProviderOperationResult.Failure(error))
        val result = executor.execute(
            request = networkOnlyRequest(SynchronizationDirection.PULL),
            evaluation = evaluationFor(SynchronizationDirection.PULL),
            providers = providerSet(transport),
        )
        val failed = assertIs<StrategySynchronizationExecutionResult.Failed>(result)
        assertEquals(error, failed.error)
    }

    // -------------------------------------------------------------------------
    // Cancellation
    //
    // #102 acceptance: "Authentication, validation, conflict, cancellation,
    // and non-retryable failures cannot be hidden by fallback." Proves a real
    // kotlinx.coroutines.CancellationException thrown mid-transport-call
    // actually propagates out of execute() rather than being converted into
    // a Failed result — a structurally different, previously-untested
    // property from the Failed/error-mapping tests above, which only prove
    // ProviderOperationResult.Failure business-error handling.
    // -------------------------------------------------------------------------

    @Test
    fun pullCancellationPropagatesRatherThanBecomingAFailedResult() = runTest {
        val transport = FakeTransportProvider(pullThrows = CancellationException("pull cancelled"))

        assertFailsWith<CancellationException> {
            executor.execute(
                request = networkOnlyRequest(SynchronizationDirection.PULL),
                evaluation = evaluationFor(SynchronizationDirection.PULL),
                providers = providerSet(transport),
            )
        }
    }

    @Test
    fun pushCancellationPropagatesRatherThanBecomingAFailedResult() = runTest {
        val transport = FakeTransportProvider(pushThrows = CancellationException("push cancelled"))

        assertFailsWith<CancellationException> {
            executor.execute(
                request = networkOnlyRequest(SynchronizationDirection.PUSH, outboundChangeSet = changeSet),
                evaluation = evaluationFor(SynchronizationDirection.PUSH),
                providers = providerSet(transport),
            )
        }
    }

    // -------------------------------------------------------------------------
    // BIDIRECTIONAL
    // -------------------------------------------------------------------------

    @Test
    fun bidirectionalWithBothOperationsSucceedingReturnsCombinedOutput() = runTest {
        val ack = acknowledgementFor(changeSet, ChangeAcknowledgementStatus.ACCEPTED)
        val pulled = PullChangesResult.NoChanges()
        val transport = FakeTransportProvider(
            pushResult = ProviderOperationResult.Success(ack),
            pullResult = ProviderOperationResult.Success(pulled),
        )
        val result = executor.execute(
            request = networkOnlyRequest(SynchronizationDirection.BIDIRECTIONAL, outboundChangeSet = changeSet),
            evaluation = evaluationFor(SynchronizationDirection.BIDIRECTIONAL),
            providers = providerSet(transport),
        )
        val executed = assertIs<StrategySynchronizationExecutionResult.Executed>(result)
        val output = assertIs<StrategyTransportOutput.Bidirectional>(executed.output)
        assertEquals(ack, output.acknowledgement)
        assertEquals(pulled, output.pullResult)
        assertEquals(1, transport.pushCalls)
        assertEquals(1, transport.pullCalls)
    }

    @Test
    fun bidirectionalWhenPushFailsDoesNotAttemptPull() = runTest {
        val error = testError("PUSH_UNAVAILABLE")
        val transport = FakeTransportProvider(pushResult = ProviderOperationResult.Failure(error))
        val result = executor.execute(
            request = networkOnlyRequest(SynchronizationDirection.BIDIRECTIONAL, outboundChangeSet = changeSet),
            evaluation = evaluationFor(SynchronizationDirection.BIDIRECTIONAL),
            providers = providerSet(transport),
        )
        val failed = assertIs<StrategySynchronizationExecutionResult.Failed>(result)
        assertEquals(error, failed.error)
        assertTrue(failed.completedOperations.isEmpty())
        assertNull(failed.partialOutput)
        assertEquals(0, transport.pullCalls)
    }

    @Test
    fun bidirectionalWhenPullFailsAfterASuccessfulPushPreservesPartialEvidence() = runTest {
        val ack = acknowledgementFor(changeSet, ChangeAcknowledgementStatus.ACCEPTED)
        val pullError = testError("PULL_UNAVAILABLE")
        val transport = FakeTransportProvider(
            pushResult = ProviderOperationResult.Success(ack),
            pullResult = ProviderOperationResult.Failure(pullError),
        )
        val result = executor.execute(
            request = networkOnlyRequest(SynchronizationDirection.BIDIRECTIONAL, outboundChangeSet = changeSet),
            evaluation = evaluationFor(SynchronizationDirection.BIDIRECTIONAL),
            providers = providerSet(transport),
        )
        val failed = assertIs<StrategySynchronizationExecutionResult.Failed>(result)
        assertEquals(pullError, failed.error)
        assertEquals(listOf(StrategyOperation.PUSH_REMOTE), failed.completedOperations)
        val partial = assertIs<StrategyTransportOutput.Pushed>(failed.partialOutput)
        assertEquals(ack, partial.acknowledgement)
    }

    // -------------------------------------------------------------------------
    // Fixtures
    // -------------------------------------------------------------------------

    private fun entityReference(suffix: String) = io.dataloom.api.change.EntityReference(
        type = EntityType("test-entity"),
        id = EntityId("entity-$suffix"),
    )

    private fun acknowledgementFor(
        changeSet: ChangeSet,
        status: ChangeAcknowledgementStatus,
    ): ChangeSetAcknowledgement = ChangeSetAcknowledgement(
        changeSetId = changeSet.id,
        events = changeSet.events.map { ChangeEventAcknowledgement(it.id, status) },
    )

    private fun networkOnlyRequest(
        direction: SynchronizationDirection,
        outboundChangeSet: ChangeSet? = null,
    ): StrategySynchronizationRequest = StrategySynchronizationRequest(
        request = SynchronizationRequest(
            workflowId = WorkflowId("network-only-workflow"),
            sessionId = SynchronizationSessionId("network-only-session"),
            direction = direction,
            mode = SynchronizationMode.DELTA,
            context = ExecutionContext(
                executionId = ExecutionId("network-only-execution"),
                correlationId = CorrelationId("network-only-correlation"),
            ),
        ),
        decisionId = StrategyDecisionId("network-only-decision"),
        planId = StrategyPlanId("network-only-plan"),
        profile = NetworkOnlyStrategyProfile(
            id = StrategyProfileId("network-only-profile"),
            configurationVersion = StrategyConfigurationVersion(1L),
        ),
        evidence = StrategyRuntimeEvidence(
            connectivity = StrategyConnectivity.AVAILABLE,
            transportHealth = StrategyProviderHealth.HEALTHY,
        ),
        input = StrategyOperationInput.DirectTransport(outboundChangeSet = outboundChangeSet),
    )

    private fun evaluationFor(direction: SynchronizationDirection) =
        evaluator.evaluate(
            networkOnlyRequest(
                direction = direction,
                outboundChangeSet = when (direction) {
                    SynchronizationDirection.PUSH, SynchronizationDirection.BIDIRECTIONAL -> changeSet
                    SynchronizationDirection.PULL -> null
                },
            ).evaluationRequest(),
        )

    private fun providerSet(transport: TransportProvider): StrategyProviderSet = object : StrategyProviderSet {
        override val storageProvider: StorageProvider? = null
        override val transportProvider: TransportProvider = transport
        override val schedulerProvider: SchedulerProvider? = null
        override val connectivityProvider: ConnectivityProvider? = null
        override val queueProvider: QueueProvider? = null
    }

    private fun testError(code: String): DataLoomError = TestTransportError(ErrorCode(code))

    private data class TestTransportError(
        override val code: ErrorCode,
        override val message: String = "test transport failure",
        override val category: ErrorCategory = ErrorCategory.NETWORK,
        override val severity: ErrorSeverity = ErrorSeverity.ERROR,
        override val recoverability: Recoverability = Recoverability.RECOVERABLE,
        override val cause: Throwable? = null,
    ) : DataLoomError

    private class FixedDataLoomClock(private val instant: DataLoomInstant) : DataLoomClock {
        override fun now(): DataLoomInstant = instant
    }

    private class FakeTransportProvider(
        private val pushResult: ProviderOperationResult<ChangeSetAcknowledgement>? = null,
        private val pullResult: ProviderOperationResult<PullChangesResult>? = null,
        private val pushThrows: Throwable? = null,
        private val pullThrows: Throwable? = null,
    ) : TransportProvider {
        var pushCalls: Int = 0
            private set
        var pullCalls: Int = 0
            private set

        override val descriptor: ProviderDescriptor = ProviderDescriptor(
            id = ProviderId("fake-transport"),
            name = ProviderName("Fake Transport"),
            type = ProviderType.TRANSPORT,
            version = ProviderVersion("1.0.0"),
        )

        override suspend fun initialize(
            context: ProviderInitializationContext,
        ): ProviderOperationResult<Unit> = ProviderOperationResult.Success(Unit)

        override suspend fun health(): ProviderOperationResult<ProviderHealth> =
            ProviderOperationResult.Success(ProviderHealth(ProviderHealthStatus.HEALTHY))

        override suspend fun close(): ProviderOperationResult<Unit> = ProviderOperationResult.Success(Unit)

        override suspend fun pushChanges(
            request: PushChangesRequest,
        ): ProviderOperationResult<ChangeSetAcknowledgement> {
            pushCalls++
            pushThrows?.let { throw it }
            return requireNotNull(pushResult) { "Test did not configure a pushChanges result." }
        }

        override suspend fun pullChanges(
            request: PullChangesRequest,
        ): ProviderOperationResult<PullChangesResult> {
            pullCalls++
            pullThrows?.let { throw it }
            return requireNotNull(pullResult) { "Test did not configure a pullChanges result." }
        }
    }
}
