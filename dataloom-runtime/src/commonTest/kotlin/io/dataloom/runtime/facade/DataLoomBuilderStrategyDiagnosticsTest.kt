package io.dataloom.runtime.facade

import io.dataloom.api.context.ExecutionContext
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
import io.dataloom.api.strategy.StrategyDecisionOutcomeKind
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
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest

/**
 * Proves [DataLoomStrategyDiagnosticsSpec]/`strategyDiagnosticsConfiguration`
 * is a real, reachable caller of
 * [io.dataloom.api.strategy.DurableStrategyDecisionEventLog] --
 * [io.dataloom.runtime.strategy.StrategySynchronizationExecutionCoordinator]
 * durably records a [StrategyDecisionEvent] for every terminal result when
 * configured, and records nothing when it is not (byte-for-byte the same
 * behavior as before this feature existed).
 */
class DataLoomBuilderStrategyDiagnosticsTest {

    @Test
    fun executedNetworkOnlyRequestIsDurablyRecordedWhenConfigured() = runTest {
        val transport = RecordingTransportProvider()
        val bindings = StrategyProviderBindings(transportProviderId = transport.descriptor.id)
        val store = InMemoryStrategyDecisionEventStore()
        val dataLoom = builder(transport, bindings)
            .strategyDiagnosticsConfiguration(DataLoomStrategyDiagnosticsSpec(store))
            .build()
        assertIs<ProviderLifecycleResult.InitializeSuccess>(dataLoom.initialize())
        val request = networkOnlyRequest()

        val result = dataLoom.synchronize(request, bindings)

        assertIs<StrategySynchronizationExecutionResult.Executed>(result)
        val recorded = store.recordedFor(request.decisionId)
        assertEquals(StrategyDecisionOutcomeKind.EXECUTED, recorded?.outcomeKind)
        assertEquals(request.planId, recorded?.planId)
        assertEquals(9_000L, recorded?.committedAt?.epochMilliseconds)
    }

    @Test
    fun rejectedRequestIsAlsoDurablyRecordedWhenConfigured() = runTest {
        val transport = RecordingTransportProvider()
        val bindings = StrategyProviderBindings(transportProviderId = transport.descriptor.id)
        val store = InMemoryStrategyDecisionEventStore()
        val dataLoom = builder(transport, bindings)
            .strategyDiagnosticsConfiguration(DataLoomStrategyDiagnosticsSpec(store))
            .build()
        // Not initialized -- every request is rejected with PROVIDERS_NOT_INITIALIZED.
        val request = networkOnlyRequest()

        val result = dataLoom.synchronize(request, bindings)

        assertIs<StrategySynchronizationExecutionResult.Rejected>(result)
        val recorded = store.recordedFor(request.decisionId)
        assertEquals(StrategyDecisionOutcomeKind.REJECTED, recorded?.outcomeKind)
        assertEquals("PROVIDERS_NOT_INITIALIZED", recorded?.outcomeDetail)
    }

    @Test
    fun nothingIsRecordedWhenDiagnosticsAreNotConfigured() = runTest {
        val transport = RecordingTransportProvider()
        val bindings = StrategyProviderBindings(transportProviderId = transport.descriptor.id)
        val store = InMemoryStrategyDecisionEventStore()
        // Note: store is never wired via strategyDiagnosticsConfiguration.
        val dataLoom = builder(transport, bindings).build()
        assertIs<ProviderLifecycleResult.InitializeSuccess>(dataLoom.initialize())
        val request = networkOnlyRequest()

        val result = dataLoom.synchronize(request, bindings)

        assertIs<StrategySynchronizationExecutionResult.Executed>(result)
        assertNull(store.recordedFor(request.decisionId))
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
        request = synchronizationRequest("network-only-diagnostics", SynchronizationDirection.PULL),
        decisionId = StrategyDecisionId("diagnostics-network-only-decision"),
        planId = StrategyPlanId("diagnostics-network-only-plan"),
        profile = NetworkOnlyStrategyProfile(
            id = StrategyProfileId("diagnostics-network-only-profile"),
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
        workflowId = WorkflowId("diagnostics-strategy-$suffix-workflow"),
        sessionId = SynchronizationSessionId("diagnostics-strategy-$suffix-session"),
        direction = direction,
        mode = SynchronizationMode.DELTA,
        context = ExecutionContext(
            executionId = ExecutionId("diagnostics-strategy-$suffix-execution"),
            correlationId = CorrelationId("diagnostics-strategy-$suffix-correlation"),
        ),
    )

    private fun runtimeDependencies(): RuntimeDependencies = RuntimeDependencies(
        clock = FixedDataLoomClock(DataLoomInstant(epochMilliseconds = 9_000L)),
        identifiers = RuntimeIdentifierGenerators(
            synchronizationEventIds = generator { SynchronizationEventId("diagnostics-strategy-event") },
            queueEntryIds = generator { QueueEntryId("diagnostics-strategy-queue-entry") },
            queueLeaseIds = generator { QueueLeaseId("diagnostics-strategy-queue-lease") },
            conflictIds = generator { ConflictId("diagnostics-strategy-conflict") },
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
            id = ProviderId("diagnostics-strategy-transport"),
            name = ProviderName("Diagnostics Strategy Transport"),
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
                object : io.dataloom.api.error.DataLoomError {
                    override val code = io.dataloom.api.error.ErrorCode("PUSH_UNUSED")
                    override val category = io.dataloom.api.error.ErrorCategory.NETWORK
                    override val severity = io.dataloom.api.error.ErrorSeverity.ERROR
                    override val recoverability = io.dataloom.api.error.Recoverability.RECOVERABLE
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
}
