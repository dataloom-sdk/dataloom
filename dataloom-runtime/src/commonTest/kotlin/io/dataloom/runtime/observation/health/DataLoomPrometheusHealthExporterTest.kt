package io.dataloom.runtime.observation.health

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
import io.dataloom.api.identifier.QueueConsumerId
import io.dataloom.api.identifier.QueueEntryId
import io.dataloom.api.identifier.QueueLeaseId
import io.dataloom.api.identifier.RetryPolicyId
import io.dataloom.api.identifier.ScheduleId
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
import io.dataloom.api.provider.SynchronizationProviderBindings
import io.dataloom.api.queue.ExpiredLeaseRecoveryRequest
import io.dataloom.api.queue.ExpiredLeaseRecoveryResult
import io.dataloom.api.queue.QueueAcquireRequest
import io.dataloom.api.queue.QueueAcquireResult
import io.dataloom.api.queue.QueueCancellationRequest
import io.dataloom.api.queue.QueueCompletionRequest
import io.dataloom.api.queue.QueueDeferralRequest
import io.dataloom.api.queue.QueueEnqueueRequest
import io.dataloom.api.queue.QueueEntry
import io.dataloom.api.queue.QueueEntryState
import io.dataloom.api.queue.QueueFailureRequest
import io.dataloom.api.queue.QueueLease
import io.dataloom.api.queue.QueueProvider
import io.dataloom.api.queue.QueueRescheduleRequest
import io.dataloom.api.runtime.RuntimeDependencies
import io.dataloom.api.runtime.RuntimeIdentifierGenerators
import io.dataloom.api.retry.RetryDecision
import io.dataloom.api.retry.RetryEvaluationRequest
import io.dataloom.api.retry.RetryOperation
import io.dataloom.api.retry.RetryPolicy
import io.dataloom.api.retry.RetryStopReason
import io.dataloom.api.scheduling.ExistingSchedulePolicy
import io.dataloom.api.scheduling.ScheduleConstraints
import io.dataloom.api.scheduling.SchedulingDelay
import io.dataloom.api.state.DurableStateCompareAndSetRequest
import io.dataloom.api.state.DurableStateCompareAndSetResult
import io.dataloom.api.state.DurableStateLoadResult
import io.dataloom.api.state.DurableStateRecord
import io.dataloom.api.state.DurableStateStore
import io.dataloom.api.storage.InboundChangeApplyRequest
import io.dataloom.api.storage.OutboundChangeReadRequest
import io.dataloom.api.storage.OutboundChangeReadResult
import io.dataloom.api.storage.StorageProvider
import io.dataloom.api.synchronization.ChangeSetAcknowledgement
import io.dataloom.api.synchronization.SynchronizationCheckpoint
import io.dataloom.api.synchronization.SynchronizationResult
import io.dataloom.api.synchronization.SynchronizationSummary
import io.dataloom.api.synchronization.CheckpointReadRequest
import io.dataloom.api.synchronization.CheckpointWriteRequest
import io.dataloom.api.synchronization.OutboundChangeAcknowledgementRequest
import io.dataloom.api.time.DataLoomClock
import io.dataloom.api.time.DataLoomInstant
import io.dataloom.api.transport.PullChangesRequest
import io.dataloom.api.transport.PullChangesResult
import io.dataloom.api.transport.PushChangesRequest
import io.dataloom.api.transport.TransportProvider
import io.dataloom.runtime.execution.SynchronizationExecutionContext
import io.dataloom.runtime.execution.SynchronizationPipeline
import io.dataloom.runtime.facade.DataLoomBuilder
import io.dataloom.runtime.facade.DataLoomQueueLifecycleOperationalEventOutboxSpec
import io.dataloom.runtime.facade.DataLoomQueueWorkerSpec
import io.dataloom.runtime.queue.QueueProcessingRequest
import io.dataloom.runtime.queue.QueuedSynchronizationWork
import io.dataloom.runtime.queue.QueuedSynchronizationWorkResolution
import io.dataloom.runtime.queue.QueuedSynchronizationWorkResolver
import io.dataloom.runtime.worker.QueueWorkerConfiguration
import io.dataloom.runtime.worker.QueueWorkerRunRequest
import io.dataloom.runtime.worker.QueueWorkerRunResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.test.runTest

/**
 * Proves [dataLoomPrometheusMetrics] against two kinds of state:
 *
 * - [fullSnapshot_fromARealDataLoomBuilder_rendersTheExactExpectedPrometheusText]
 *   builds a real [DataLoomBuilder]-built [io.dataloom.api.facade.DataLoom],
 *   drives one real queue entry through it (bridged to a real durable outbox
 *   entry, observed by real [OperationalEventOutboxHealthTracker]/
 *   [QueueWorkerHealthTracker] instances the same way
 *   `DataLoomBuilderQueueLifecycleOperationalEventOutboxTest` does), calls a
 *   real provider's `health()`, assembles a real [DataLoomHealthSnapshot] from
 *   that state via [dataLoomHealthSnapshot], and asserts the exporter's exact
 *   output byte-for-byte.
 * - The remaining tests construct a [DataLoomHealthSnapshot] directly to pin
 *   down formatting edge cases ([DataLoomHealthSnapshot] itself is a plain
 *   data class with a public constructor, so this needs no additional fakes):
 *   the all-`HEALTHY`/nothing-supplied case, a never-observed outbox scope
 *   (omitted, not zero), and Prometheus label-value escaping.
 */
class DataLoomPrometheusHealthExporterTest {

    private data class FakeError(
        override val code: ErrorCode = ErrorCode("DL-FAKE"),
        override val category: ErrorCategory = ErrorCategory.STORAGE,
        override val severity: ErrorSeverity = ErrorSeverity.ERROR,
        override val recoverability: Recoverability = Recoverability.RECOVERABLE,
        override val message: String = "raw-sensitive-message",
        override val cause: Throwable? = null,
    ) : DataLoomError

    @Test
    fun fullSnapshot_fromARealDataLoomBuilder_rendersTheExactExpectedPrometheusText() = runTest {
        val queue = RecordingQueueProvider()
        val outboxStore = InMemoryOperationalEventOutboxStore()
        val scope = OperationalEventOutboxScope("prometheus-outbox-events")
        val trackerClock = object : DataLoomClock {
            override fun now(): DataLoomInstant = DataLoomInstant(9_000L)
        }
        val outboxTracker = OperationalEventOutboxHealthTracker(trackerClock)
        val workerTracker = QueueWorkerHealthTracker(trackerClock)
        val dataLoom = builder(queue)
            .pipeline(SucceedingPipeline(SynchronizationDirection.PUSH))
            .queueWorkerConfiguration(queueWorkerSpec())
            .queueLifecycleOperationalEventOutboxConfiguration(
                DataLoomQueueLifecycleOperationalEventOutboxSpec(store = outboxStore, scope = scope),
            )
            .operationalEventOutboxHealthTracker(outboxTracker)
            .queueWorkerHealthTracker(workerTracker)
            .build()
        assertIs<ProviderLifecycleResult.InitializeSuccess>(dataLoom.initialize())

        val runResult = dataLoom.queueWorker!!.run(workerRunRequest())
        assertIs<QueueWorkerRunResult.ProcessingCompleted>(runResult)

        // A real, caller-performed provider health() call -- exactly how
        // dataLoomHealthSnapshot's own KDoc says providerHealth is obtained.
        val queueHealth = assertIs<ProviderOperationResult.Success<ProviderHealth>>(queue.health())

        val snapshot = dataLoomHealthSnapshot(
            providerHealth = mapOf(queue.descriptor.id to queueHealth.value),
            outboxObservations = outboxTracker.snapshot(),
            queueWorkerObservation = workerTracker.snapshot(),
            now = DataLoomInstant(9_000L),
        )
        assertEquals(DataLoomHealthSeverity.DEGRADED, snapshot.severity)
        assertEquals(1, outboxTracker.snapshot().single().stateObservation?.summary?.pendingCount)

        val expected = listOf(
            "# HELP dataloom_health_severity Overall DataLoomHealthSnapshot severity (0=HEALTHY,1=DEGRADED,2=UNHEALTHY).",
            "# TYPE dataloom_health_severity gauge",
            "dataloom_health_severity 1",
            "# HELP dataloom_health_component_severity Maximum finding severity per DataLoomHealthComponent " +
                "(0=HEALTHY,1=DEGRADED,2=UNHEALTHY); 0 when the component raised no finding (component is one of " +
                "PROVIDER, TELEMETRY_EXPORTER, OPERATIONAL_EVENT_OUTBOX, QUEUE_WORKER, ASSET_TRANSFER, PLUGIN).",
            "# TYPE dataloom_health_component_severity gauge",
            "dataloom_health_component_severity{component=\"PROVIDER\"} 1",
            "dataloom_health_component_severity{component=\"TELEMETRY_EXPORTER\"} 0",
            "dataloom_health_component_severity{component=\"OPERATIONAL_EVENT_OUTBOX\"} 0",
            "dataloom_health_component_severity{component=\"QUEUE_WORKER\"} 0",
            "dataloom_health_component_severity{component=\"ASSET_TRANSFER\"} 0",
            "dataloom_health_component_severity{component=\"PLUGIN\"} 0",
            "# HELP dataloom_outbox_pending_entries Pending entries in a durable operational-event outbox scope, " +
                "as of the last observation this process made. A scope never observed by this process is omitted.",
            "# TYPE dataloom_outbox_pending_entries gauge",
            "dataloom_outbox_pending_entries{scope=\"prometheus-outbox-events\"} 1",
            "# HELP dataloom_outbox_acknowledged_retained_entries Retained acknowledged (tombstoned) entries in a " +
                "durable operational-event outbox scope, as of the last observation this process made. A scope " +
                "never observed by this process is omitted.",
            "# TYPE dataloom_outbox_acknowledged_retained_entries gauge",
            "dataloom_outbox_acknowledged_retained_entries{scope=\"prometheus-outbox-events\"} 0",
            "# HELP dataloom_outbox_severity Per-scope outbox health severity (0=HEALTHY,1=DEGRADED,2=UNHEALTHY).",
            "# TYPE dataloom_outbox_severity gauge",
            "dataloom_outbox_severity{scope=\"prometheus-outbox-events\"} 0",
            "# HELP dataloom_queue_worker_runs_in_flight Queue worker runs currently in flight.",
            "# TYPE dataloom_queue_worker_runs_in_flight gauge",
            "dataloom_queue_worker_runs_in_flight 0",
            "# HELP dataloom_queue_worker_consecutive_failed_runs Queue worker's current consecutive failed run streak.",
            "# TYPE dataloom_queue_worker_consecutive_failed_runs gauge",
            "dataloom_queue_worker_consecutive_failed_runs 0",
            "# HELP dataloom_queue_worker_severity Queue worker health severity (0=HEALTHY,1=DEGRADED,2=UNHEALTHY).",
            "# TYPE dataloom_queue_worker_severity gauge",
            "dataloom_queue_worker_severity 0",
            "# HELP dataloom_provider_health_status Redacted provider health status (0=UNKNOWN,1=HEALTHY,2=DEGRADED,3=UNHEALTHY).",
            "# TYPE dataloom_provider_health_status gauge",
            "dataloom_provider_health_status{provider=\"prometheus-queue-provider\"} 2",
        ).joinToString("\n") + "\n"

        assertEquals(expected, dataLoomPrometheusMetrics(snapshot))
    }

    @Test
    fun emptySnapshot_rendersOnlyTheTwoAlwaysPresentMetrics_allHealthy() {
        val snapshot = dataLoomHealthSnapshot()
        assertEquals(DataLoomHealthSeverity.HEALTHY, snapshot.severity)

        val expected = listOf(
            "# HELP dataloom_health_severity Overall DataLoomHealthSnapshot severity (0=HEALTHY,1=DEGRADED,2=UNHEALTHY).",
            "# TYPE dataloom_health_severity gauge",
            "dataloom_health_severity 0",
            "# HELP dataloom_health_component_severity Maximum finding severity per DataLoomHealthComponent " +
                "(0=HEALTHY,1=DEGRADED,2=UNHEALTHY); 0 when the component raised no finding (component is one of " +
                "PROVIDER, TELEMETRY_EXPORTER, OPERATIONAL_EVENT_OUTBOX, QUEUE_WORKER, ASSET_TRANSFER, PLUGIN).",
            "# TYPE dataloom_health_component_severity gauge",
            "dataloom_health_component_severity{component=\"PROVIDER\"} 0",
            "dataloom_health_component_severity{component=\"TELEMETRY_EXPORTER\"} 0",
            "dataloom_health_component_severity{component=\"OPERATIONAL_EVENT_OUTBOX\"} 0",
            "dataloom_health_component_severity{component=\"QUEUE_WORKER\"} 0",
            "dataloom_health_component_severity{component=\"ASSET_TRANSFER\"} 0",
            "dataloom_health_component_severity{component=\"PLUGIN\"} 0",
        ).joinToString("\n") + "\n"

        assertEquals(expected, dataLoomPrometheusMetrics(snapshot))
    }

    @Test
    fun outboxScope_neverObserved_isOmittedNotZero() {
        // A scope with a processing-cycle observation but no state observation
        // yet (OperationalEventOutboxHealth.pendingCount/acknowledgedRetainedCount
        // are both null) -- constructed directly, the same way
        // OperationalEventOutboxHealthTracker's own doc describes "a processing
        // cycle recorded, state never observed".
        val outboxHealth = OperationalEventOutboxHealth(
            scope = OperationalEventOutboxScope("never-observed-scope"),
            pendingCount = null,
            acknowledgedRetainedCount = null,
            oldestPendingAge = null,
            observedAt = null,
            observationAge = null,
            stale = false,
            lastProcessingCycle = null,
            hasSkippedOrFailedEntries = false,
            severity = DataLoomHealthSeverity.HEALTHY,
        )
        val snapshot = DataLoomHealthSnapshot(
            providerLifecycleState = null,
            retryCircuitTelemetry = null,
            providerHealth = emptyMap(),
            outboxHealth = listOf(outboxHealth),
            queueWorkerHealth = null,
            assetTransferHealth = null,
            pluginHealth = emptyMap(),
            severity = DataLoomHealthSeverity.HEALTHY,
            findings = emptyList(),
        )

        val text = dataLoomPrometheusMetrics(snapshot)

        assertEquals(false, text.contains("dataloom_outbox_pending_entries"))
        assertEquals(false, text.contains("dataloom_outbox_acknowledged_retained_entries"))
        assertEquals(true, text.contains("dataloom_outbox_severity{scope=\"never-observed-scope\"} 0"))
    }

    @Test
    fun labelValuesContainingQuotesBackslashesAndNewlines_areEscapedPerThePrometheusTextFormat() {
        val outboxHealth = OperationalEventOutboxHealth(
            scope = OperationalEventOutboxScope("weird\\scope\"with\nnewline"),
            pendingCount = 3,
            acknowledgedRetainedCount = 0,
            oldestPendingAge = null,
            observedAt = null,
            observationAge = null,
            stale = false,
            lastProcessingCycle = null,
            hasSkippedOrFailedEntries = false,
            severity = DataLoomHealthSeverity.HEALTHY,
        )
        val snapshot = DataLoomHealthSnapshot(
            providerLifecycleState = null,
            retryCircuitTelemetry = null,
            providerHealth = emptyMap(),
            outboxHealth = listOf(outboxHealth),
            queueWorkerHealth = null,
            assetTransferHealth = null,
            pluginHealth = emptyMap(),
            severity = DataLoomHealthSeverity.HEALTHY,
            findings = emptyList(),
        )

        val text = dataLoomPrometheusMetrics(snapshot)

        assertEquals(
            true,
            text.contains("dataloom_outbox_pending_entries{scope=\"weird\\\\scope\\\"with\\nnewline\"} 3"),
        )
    }

    // -------------------------------------------------------------------------
    // Fixtures (mirroring DataLoomBuilderQueueLifecycleOperationalEventOutboxTest)
    // -------------------------------------------------------------------------

    private fun builder(queue: RecordingQueueProvider): DataLoomBuilder = DataLoomBuilder()
        .runtimeDependencies(runtimeDependencies())
        .providers(RecordingStorageProvider(), RecordingTransportProvider(), queue)
        .defaultProviderBindings(
            SynchronizationProviderBindings(
                storageProviderId = ProviderId("prometheus-storage"),
                transportProviderId = ProviderId("prometheus-transport"),
                queueProviderId = queue.descriptor.id,
            ),
        )

    private fun queueWorkerSpec(): DataLoomQueueWorkerSpec = DataLoomQueueWorkerSpec(
        workResolver = QueuedSynchronizationWorkResolver { entry ->
            QueuedSynchronizationWorkResolution.Resolved(
                QueuedSynchronizationWork(
                    request = entry.synchronizationRequest,
                    bindings = SynchronizationProviderBindings(
                        storageProviderId = ProviderId("prometheus-storage"),
                        transportProviderId = ProviderId("prometheus-transport"),
                    ),
                ),
            )
        },
        retryPolicy = object : RetryPolicy {
            override val id = RetryPolicyId("prometheus-no-retry")
            override fun evaluate(request: RetryEvaluationRequest): RetryDecision =
                RetryDecision.Stop(RetryStopReason.NON_RECOVERABLE)
        },
        retryOperation = RetryOperation("prometheus.test.operation"),
        configuration = QueueWorkerConfiguration(
            scheduleId = ScheduleId("prometheus-worker"),
            constraints = ScheduleConstraints(),
            existingSchedulePolicy = ExistingSchedulePolicy.REPLACE,
            continuationDelay = SchedulingDelay.ZERO,
            recoverExpiredLeasesBeforeProcessing = false,
        ),
    )

    private fun workerRunRequest(maxEntries: Int = 5): QueueWorkerRunRequest = QueueWorkerRunRequest(
        processingRequest = QueueProcessingRequest(
            acquireRequest = QueueAcquireRequest(
                consumerId = QueueConsumerId("prometheus-consumer"),
                leaseId = QueueLeaseId("prometheus-lease"),
                acquiredAt = DataLoomInstant(1_000_000L),
                leaseExpiresAt = DataLoomInstant(2_000_000L),
                maxEntries = maxEntries,
            ),
        ),
        recoveryRequest = null,
    )

    private fun runtimeDependencies(): RuntimeDependencies = RuntimeDependencies(
        clock = FixedDataLoomClock(DataLoomInstant(epochMilliseconds = 9_000L)),
        identifiers = RuntimeIdentifierGenerators(
            synchronizationEventIds = generator { SynchronizationEventId("prometheus-event") },
            queueEntryIds = generator { QueueEntryId("prometheus-generated-entry") },
            queueLeaseIds = generator { QueueLeaseId("prometheus-generated-lease") },
            conflictIds = generator { ConflictId("prometheus-conflict") },
        ),
    )

    private fun <T> generator(block: () -> T): IdentifierGenerator<T> =
        object : IdentifierGenerator<T> {
            override fun generate(): T = block()
        }

    private class FixedDataLoomClock(private val instant: DataLoomInstant) : DataLoomClock {
        override fun now(): DataLoomInstant = instant
    }

    /** Always succeeds -- deterministically drives the queue entry to completion. */
    private class SucceedingPipeline(override val direction: SynchronizationDirection) : SynchronizationPipeline {
        override suspend fun execute(context: SynchronizationExecutionContext): SynchronizationResult =
            SynchronizationResult.Succeeded(
                request = context.request,
                completedAt = DataLoomInstant(1_500_000L),
                summary = SynchronizationSummary(),
            )
    }

    /** Returns exactly one real acquired entry on the first [acquire] call, then [QueueAcquireResult.NoEntries]. */
    private class RecordingQueueProvider : QueueProvider {
        private var acquireCallCount = 0

        override val descriptor: ProviderDescriptor = ProviderDescriptor(
            id = ProviderId("prometheus-queue-provider"),
            name = ProviderName("Prometheus Exporter Test Queue"),
            type = ProviderType.QUEUE,
            version = ProviderVersion("1.0.0"),
        )

        override suspend fun initialize(context: ProviderInitializationContext): ProviderOperationResult<Unit> =
            ProviderOperationResult.Success(Unit)

        // Deliberately DEGRADED: this real, caller-performed health() result is
        // what feeds dataloom_provider_health_status / dataloom_health_severity
        // in this test, exercising a non-HEALTHY path end to end.
        override suspend fun health(): ProviderOperationResult<ProviderHealth> =
            ProviderOperationResult.Success(ProviderHealth(ProviderHealthStatus.DEGRADED))

        override suspend fun close(): ProviderOperationResult<Unit> = ProviderOperationResult.Success(Unit)

        override suspend fun enqueue(request: QueueEnqueueRequest): ProviderOperationResult<Unit> =
            ProviderOperationResult.Success(Unit)

        override suspend fun acquire(request: QueueAcquireRequest): ProviderOperationResult<QueueAcquireResult> {
            acquireCallCount++
            if (acquireCallCount > 1) {
                return ProviderOperationResult.Success(QueueAcquireResult.NoEntries)
            }
            val entryRequest = SynchronizationRequest(
                workflowId = WorkflowId("prometheus-workflow"),
                sessionId = SynchronizationSessionId("prometheus-session"),
                direction = SynchronizationDirection.PUSH,
                mode = SynchronizationMode.DELTA,
                context = ExecutionContext(
                    executionId = ExecutionId("prometheus-execution"),
                    correlationId = CorrelationId("prometheus-correlation"),
                ),
            )
            val lease = QueueLease(
                id = request.leaseId,
                consumerId = request.consumerId,
                acquiredAt = request.acquiredAt,
                expiresAt = request.leaseExpiresAt,
            )
            val entry = QueueEntry(
                id = QueueEntryId("prometheus-real-entry"),
                synchronizationRequest = entryRequest,
                state = QueueEntryState.LEASED,
                enqueuedAt = request.acquiredAt,
                availableAt = request.acquiredAt,
                lease = lease,
            )
            return ProviderOperationResult.Success(QueueAcquireResult.Entries(lease = lease, entries = listOf(entry)))
        }

        override suspend fun complete(request: QueueCompletionRequest): ProviderOperationResult<Unit> =
            ProviderOperationResult.Success(Unit)

        override suspend fun reschedule(request: QueueRescheduleRequest): ProviderOperationResult<Unit> =
            ProviderOperationResult.Failure(FakeError())

        override suspend fun defer(request: QueueDeferralRequest): ProviderOperationResult<Unit> =
            ProviderOperationResult.Failure(FakeError())

        override suspend fun fail(request: QueueFailureRequest): ProviderOperationResult<Unit> =
            ProviderOperationResult.Failure(FakeError())

        override suspend fun cancel(request: QueueCancellationRequest): ProviderOperationResult<Unit> =
            ProviderOperationResult.Failure(FakeError())

        override suspend fun recoverExpiredLeases(
            request: ExpiredLeaseRecoveryRequest,
        ): ProviderOperationResult<ExpiredLeaseRecoveryResult> =
            ProviderOperationResult.Success(ExpiredLeaseRecoveryResult(recoveredEntries = 0))
    }

    /** Never actually invoked -- [SucceedingPipeline] replaces default pipeline execution. */
    private class RecordingStorageProvider : StorageProvider {
        override val descriptor: ProviderDescriptor = ProviderDescriptor(
            id = ProviderId("prometheus-storage"),
            name = ProviderName("Prometheus Exporter Test Storage"),
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

        override suspend fun applyInboundChanges(request: InboundChangeApplyRequest): ProviderOperationResult<Unit> =
            ProviderOperationResult.Success(Unit)

        override suspend fun acknowledgeOutboundChanges(
            request: OutboundChangeAcknowledgementRequest,
        ): ProviderOperationResult<Unit> = ProviderOperationResult.Success(Unit)

        override suspend fun readCheckpoint(
            request: CheckpointReadRequest,
        ): ProviderOperationResult<SynchronizationCheckpoint?> = ProviderOperationResult.Success(null)

        override suspend fun writeCheckpoint(request: CheckpointWriteRequest): ProviderOperationResult<Unit> =
            ProviderOperationResult.Success(Unit)
    }

    /** Never actually invoked -- [SucceedingPipeline] replaces default pipeline execution. */
    private class RecordingTransportProvider : TransportProvider {
        override val descriptor: ProviderDescriptor = ProviderDescriptor(
            id = ProviderId("prometheus-transport"),
            name = ProviderName("Prometheus Exporter Test Transport"),
            type = ProviderType.TRANSPORT,
            version = ProviderVersion("1.0.0"),
        )

        override suspend fun initialize(context: ProviderInitializationContext): ProviderOperationResult<Unit> =
            ProviderOperationResult.Success(Unit)

        override suspend fun health(): ProviderOperationResult<ProviderHealth> =
            ProviderOperationResult.Success(ProviderHealth(ProviderHealthStatus.HEALTHY))

        override suspend fun close(): ProviderOperationResult<Unit> = ProviderOperationResult.Success(Unit)

        override suspend fun pushChanges(request: PushChangesRequest): ProviderOperationResult<ChangeSetAcknowledgement> =
            ProviderOperationResult.Failure(FakeError())

        override suspend fun pullChanges(request: PullChangesRequest): ProviderOperationResult<PullChangesResult> =
            ProviderOperationResult.Success(PullChangesResult.NoChanges())
    }

    private class InMemoryOperationalEventOutboxStore :
        DurableStateStore<OperationalEventOutboxScope, OperationalEventOutboxState> {
        private val records = mutableMapOf<OperationalEventOutboxScope, DurableStateRecord<OperationalEventOutboxState>>()

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
}
