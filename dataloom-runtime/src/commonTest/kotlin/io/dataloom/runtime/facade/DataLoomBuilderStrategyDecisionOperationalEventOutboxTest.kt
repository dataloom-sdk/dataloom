package io.dataloom.runtime.facade

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
import io.dataloom.api.operational.OperationalEventOutboxScope
import io.dataloom.api.operational.OperationalEventOutboxState
import io.dataloom.api.provider.ProviderDescriptor
import io.dataloom.api.provider.ProviderHealth
import io.dataloom.api.provider.ProviderHealthStatus
import io.dataloom.api.provider.ProviderId
import io.dataloom.api.provider.ProviderInitializationContext
import io.dataloom.api.provider.ProviderLifecycleResult
import io.dataloom.api.provider.ProviderName
import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.api.provider.ProviderType
import io.dataloom.api.provider.ProviderVersion
import io.dataloom.api.provider.StrategyProviderBindings
import io.dataloom.api.runtime.RuntimeDependencies
import io.dataloom.api.runtime.RuntimeIdentifierGenerators
import io.dataloom.api.state.DurableStateCompareAndSetRequest
import io.dataloom.api.state.DurableStateCompareAndSetResult
import io.dataloom.api.state.DurableStateLoadResult
import io.dataloom.api.state.DurableStateRecord
import io.dataloom.api.state.DurableStateStore
import io.dataloom.api.strategy.NetworkOnlyStrategyProfile
import io.dataloom.api.strategy.StrategyConfigurationVersion
import io.dataloom.api.strategy.StrategyConnectivity
import io.dataloom.api.strategy.StrategyDecisionEvent
import io.dataloom.api.strategy.StrategyDecisionId
import io.dataloom.api.strategy.StrategyOperationInput
import io.dataloom.api.strategy.StrategyPlanId
import io.dataloom.api.strategy.StrategyProfileId
import io.dataloom.api.strategy.StrategyProviderHealth
import io.dataloom.api.strategy.StrategyRuntimeEvidence
import io.dataloom.api.strategy.StrategySynchronizationRequest
import io.dataloom.api.synchronization.ChangeSetAcknowledgement
import io.dataloom.api.time.DataLoomClock
import io.dataloom.api.time.DataLoomInstant
import io.dataloom.api.transport.PullChangesRequest
import io.dataloom.api.transport.PullChangesResult
import io.dataloom.api.transport.PushChangesRequest
import io.dataloom.api.transport.TransportProvider
import io.dataloom.runtime.strategy.StrategySynchronizationExecutionResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.test.runTest

/**
 * Proves [DataLoomStrategyDecisionOperationalEventOutboxSpec]/
 * `strategyDecisionOperationalEventOutboxConfiguration` is a real, reachable
 * caller of [io.dataloom.api.operational.DurableOperationalEventOutbox] for
 * strategy-decision events produced by a real
 * [DataLoomBuilder]-built [io.dataloom.api.facade.DataLoom] instance -- and
 * that a durable-recording failure never changes the real strategy execution
 * result the caller receives, and that nothing is bridged unless both
 * [DataLoomStrategyDiagnosticsSpec] and this spec are configured.
 */
class DataLoomBuilderStrategyDecisionOperationalEventOutboxTest {

    private data class FakeError(
        override val code: ErrorCode = ErrorCode("DL-FAKE"),
        override val category: ErrorCategory = ErrorCategory.STORAGE,
        override val severity: ErrorSeverity = ErrorSeverity.ERROR,
        override val recoverability: Recoverability = Recoverability.RECOVERABLE,
        override val message: String = "raw-sensitive-message",
        override val cause: Throwable? = null,
    ) : DataLoomError

    @Test
    fun executedRequest_appendsRealEntry_whenBothDiagnosticsAndOutboxAreConfigured() = runTest {
        val transport = RecordingTransportProvider()
        val bindings = StrategyProviderBindings(transportProviderId = transport.descriptor.id)
        val diagnosticsStore = InMemoryStrategyDecisionEventStore()
        val outboxStore = InMemoryOperationalEventOutboxStore()
        val scope = OperationalEventOutboxScope("integration-strategy-decision-events")
        val dataLoom = builder(transport, bindings)
            .strategyDiagnosticsConfiguration(DataLoomStrategyDiagnosticsSpec(diagnosticsStore))
            .strategyDecisionOperationalEventOutboxConfiguration(
                DataLoomStrategyDecisionOperationalEventOutboxSpec(store = outboxStore, scope = scope),
            )
            .build()
        assertIs<ProviderLifecycleResult.InitializeSuccess>(dataLoom.initialize())
        val request = networkOnlyRequest()

        val result = dataLoom.synchronize(request, bindings)

        assertIs<StrategySynchronizationExecutionResult.Executed>(result)
        assertEquals(1, outboxStore.recordedEntryCount(scope))
    }

    @Test
    fun nothingIsBridged_whenOutboxIsConfiguredButDiagnosticsAreNot() = runTest {
        val transport = RecordingTransportProvider()
        val bindings = StrategyProviderBindings(transportProviderId = transport.descriptor.id)
        val outboxStore = InMemoryOperationalEventOutboxStore()
        val scope = OperationalEventOutboxScope("integration-no-diagnostics-events")
        val dataLoom = builder(transport, bindings)
            // Note: strategyDiagnosticsConfiguration is never called, so no
            // StrategyDecisionEvent is ever constructed to bridge.
            .strategyDecisionOperationalEventOutboxConfiguration(
                DataLoomStrategyDecisionOperationalEventOutboxSpec(store = outboxStore, scope = scope),
            )
            .build()
        assertIs<ProviderLifecycleResult.InitializeSuccess>(dataLoom.initialize())
        val request = networkOnlyRequest()

        val result = dataLoom.synchronize(request, bindings)

        assertIs<StrategySynchronizationExecutionResult.Executed>(result)
        assertEquals(0, outboxStore.recordedEntryCount(scope))
    }

    @Test
    fun nothingIsBridged_whenDiagnosticsAreConfiguredButOutboxIsNot() = runTest {
        val transport = RecordingTransportProvider()
        val bindings = StrategyProviderBindings(transportProviderId = transport.descriptor.id)
        val diagnosticsStore = InMemoryStrategyDecisionEventStore()
        val outboxStore = InMemoryOperationalEventOutboxStore()
        val scope = OperationalEventOutboxScope("integration-no-outbox-events")
        val dataLoom = builder(transport, bindings)
            .strategyDiagnosticsConfiguration(DataLoomStrategyDiagnosticsSpec(diagnosticsStore))
            // Note: strategyDecisionOperationalEventOutboxConfiguration is never called.
            .build()
        assertIs<ProviderLifecycleResult.InitializeSuccess>(dataLoom.initialize())
        val request = networkOnlyRequest()

        val result = dataLoom.synchronize(request, bindings)

        assertIs<StrategySynchronizationExecutionResult.Executed>(result)
        assertEquals(0, outboxStore.recordedEntryCount(scope))
    }

    @Test
    fun appendFailureDoesNotChangeTheRealResult() = runTest {
        val transport = RecordingTransportProvider()
        val bindings = StrategyProviderBindings(transportProviderId = transport.descriptor.id)
        val diagnosticsStore = InMemoryStrategyDecisionEventStore()
        val scope = OperationalEventOutboxScope("integration-failing-events")
        val dataLoom = builder(transport, bindings)
            .strategyDiagnosticsConfiguration(DataLoomStrategyDiagnosticsSpec(diagnosticsStore))
            .strategyDecisionOperationalEventOutboxConfiguration(
                DataLoomStrategyDecisionOperationalEventOutboxSpec(
                    store = AlwaysFailingOperationalEventOutboxStore(),
                    scope = scope,
                ),
            )
            .build()
        assertIs<ProviderLifecycleResult.InitializeSuccess>(dataLoom.initialize())
        val request = networkOnlyRequest()

        val result = dataLoom.synchronize(request, bindings)

        // The real strategy execution result is unchanged, and the durable
        // diagnostics log entry (a fully separate concern from the outbox
        // bridge) still succeeded despite the outbox append failure.
        assertIs<StrategySynchronizationExecutionResult.Executed>(result)
        val recorded = diagnosticsStore.recordedFor(request.decisionId)
        assertEquals(request.planId, recorded?.planId)
    }

    // -------------------------------------------------------------------------
    // Fixtures
    // -------------------------------------------------------------------------

    private fun builder(
        transport: RecordingTransportProvider,
        bindings: StrategyProviderBindings,
    ): DataLoomBuilder = DataLoomBuilder()
        .runtimeDependencies(runtimeDependencies())
        .provider(transport)
        .defaultStrategyProviderBindings(bindings)

    private fun networkOnlyRequest(): StrategySynchronizationRequest = StrategySynchronizationRequest(
        request = synchronizationRequest("outbox", SynchronizationDirection.PULL),
        decisionId = StrategyDecisionId("outbox-network-only-decision"),
        planId = StrategyPlanId("outbox-network-only-plan"),
        profile = NetworkOnlyStrategyProfile(
            id = StrategyProfileId("outbox-network-only-profile"),
            configurationVersion = StrategyConfigurationVersion(1L),
        ),
        evidence = StrategyRuntimeEvidence(
            connectivity = StrategyConnectivity.AVAILABLE,
            transportHealth = StrategyProviderHealth.HEALTHY,
        ),
        input = StrategyOperationInput.DirectTransport(),
    )

    private fun synchronizationRequest(
        suffix: String,
        direction: SynchronizationDirection,
    ): SynchronizationRequest = SynchronizationRequest(
        workflowId = WorkflowId("outbox-strategy-$suffix-workflow"),
        sessionId = SynchronizationSessionId("outbox-strategy-$suffix-session"),
        direction = direction,
        mode = SynchronizationMode.DELTA,
        context = ExecutionContext(
            executionId = ExecutionId("outbox-strategy-$suffix-execution"),
            correlationId = CorrelationId("outbox-strategy-$suffix-correlation"),
        ),
    )

    private fun runtimeDependencies(): RuntimeDependencies = RuntimeDependencies(
        clock = FixedDataLoomClock(DataLoomInstant(epochMilliseconds = 9_000L)),
        identifiers = RuntimeIdentifierGenerators(
            synchronizationEventIds = generator { SynchronizationEventId("outbox-strategy-event") },
            queueEntryIds = generator { QueueEntryId("outbox-strategy-queue-entry") },
            queueLeaseIds = generator { QueueLeaseId("outbox-strategy-queue-lease") },
            conflictIds = generator { ConflictId("outbox-strategy-conflict") },
        ),
    )

    private fun <T> generator(block: () -> T): IdentifierGenerator<T> =
        object : IdentifierGenerator<T> {
            override fun generate(): T = block()
        }

    private class FixedDataLoomClock(private val instant: DataLoomInstant) : DataLoomClock {
        override fun now(): DataLoomInstant = instant
    }

    private class RecordingTransportProvider(
        private val pullResult: ProviderOperationResult<PullChangesResult> =
            ProviderOperationResult.Success(PullChangesResult.NoChanges()),
    ) : TransportProvider {
        override val descriptor: ProviderDescriptor = ProviderDescriptor(
            id = ProviderId("outbox-strategy-transport"),
            name = ProviderName("Outbox Strategy Transport"),
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
        ): ProviderOperationResult<ChangeSetAcknowledgement> =
            ProviderOperationResult.Failure(
                object : DataLoomError {
                    override val code = ErrorCode("PUSH_UNUSED")
                    override val category = ErrorCategory.NETWORK
                    override val severity = ErrorSeverity.ERROR
                    override val recoverability = Recoverability.RECOVERABLE
                    override val message = "Push not exercised in this test."
                    override val cause: Throwable? = null
                },
            )

        override suspend fun pullChanges(
            request: PullChangesRequest,
        ): ProviderOperationResult<PullChangesResult> = pullResult
    }

    private class InMemoryStrategyDecisionEventStore : DurableStateStore<StrategyDecisionId, StrategyDecisionEvent> {
        private val records = mutableMapOf<StrategyDecisionId, DurableStateRecord<StrategyDecisionEvent>>()

        suspend fun recordedFor(decisionId: StrategyDecisionId): StrategyDecisionEvent? = records[decisionId]?.state

        override suspend fun load(
            scope: StrategyDecisionId,
        ): ProviderOperationResult<DurableStateLoadResult<StrategyDecisionEvent>> {
            val record = records[scope]
            return ProviderOperationResult.Success(
                if (record == null) DurableStateLoadResult.Missing else DurableStateLoadResult.Found(record),
            )
        }

        override suspend fun compareAndSet(
            request: DurableStateCompareAndSetRequest<StrategyDecisionId, StrategyDecisionEvent>,
        ): ProviderOperationResult<DurableStateCompareAndSetResult<StrategyDecisionEvent>> {
            val current = records[request.scope]
            if (current?.version != request.expectedVersion) {
                return ProviderOperationResult.Success(DurableStateCompareAndSetResult.Conflict(current))
            }
            val updated = DurableStateRecord(
                state = request.nextState,
                version = (current?.version ?: -1L) + 1L,
                schemaVersion = request.nextSchemaVersion,
            )
            records[request.scope] = updated
            return ProviderOperationResult.Success(DurableStateCompareAndSetResult.Updated(updated))
        }
    }

    private class InMemoryOperationalEventOutboxStore :
        DurableStateStore<OperationalEventOutboxScope, OperationalEventOutboxState> {
        private val records = mutableMapOf<OperationalEventOutboxScope, DurableStateRecord<OperationalEventOutboxState>>()

        suspend fun recordedEntryCount(scope: OperationalEventOutboxScope): Int = records[scope]?.state?.entries?.size ?: 0

        override suspend fun load(
            scope: OperationalEventOutboxScope,
        ): ProviderOperationResult<DurableStateLoadResult<OperationalEventOutboxState>> {
            val record = records[scope]
            return ProviderOperationResult.Success(
                if (record == null) DurableStateLoadResult.Missing else DurableStateLoadResult.Found(record),
            )
        }

        override suspend fun compareAndSet(
            request: DurableStateCompareAndSetRequest<OperationalEventOutboxScope, OperationalEventOutboxState>,
        ): ProviderOperationResult<DurableStateCompareAndSetResult<OperationalEventOutboxState>> {
            val current = records[request.scope]
            if (current?.version != request.expectedVersion) {
                return ProviderOperationResult.Success(DurableStateCompareAndSetResult.Conflict(current))
            }
            val updated = DurableStateRecord(
                state = request.nextState,
                version = (current?.version ?: -1L) + 1L,
                schemaVersion = request.nextSchemaVersion,
            )
            records[request.scope] = updated
            return ProviderOperationResult.Success(DurableStateCompareAndSetResult.Updated(updated))
        }
    }

    /** Always fails the underlying durable store -- proves append failures never propagate. */
    private class AlwaysFailingOperationalEventOutboxStore :
        DurableStateStore<OperationalEventOutboxScope, OperationalEventOutboxState> {
        override suspend fun load(
            scope: OperationalEventOutboxScope,
        ): ProviderOperationResult<DurableStateLoadResult<OperationalEventOutboxState>> =
            ProviderOperationResult.Failure(FakeError())

        override suspend fun compareAndSet(
            request: DurableStateCompareAndSetRequest<OperationalEventOutboxScope, OperationalEventOutboxState>,
        ): ProviderOperationResult<DurableStateCompareAndSetResult<OperationalEventOutboxState>> =
            ProviderOperationResult.Failure(FakeError())
    }
}
