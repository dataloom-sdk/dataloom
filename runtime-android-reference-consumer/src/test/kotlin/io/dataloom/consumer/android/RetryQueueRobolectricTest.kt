package io.dataloom.consumer.android

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import io.dataloom.android.androidDataLoomProviders
import io.dataloom.android.installAndroidProviders
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
import io.dataloom.api.identifier.QueueConsumerId
import io.dataloom.api.identifier.QueueEntryId
import io.dataloom.api.identifier.QueueLeaseId
import io.dataloom.api.identifier.RetryPolicyId
import io.dataloom.api.identifier.ScheduleId
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
import io.dataloom.api.provider.ProviderLifecycleResult
import io.dataloom.api.provider.ProviderName
import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.api.provider.ProviderType
import io.dataloom.api.provider.ProviderVersion
import io.dataloom.api.provider.SynchronizationProviderBindings
import io.dataloom.api.queue.QueueAcquireRequest
import io.dataloom.api.queue.QueueEnqueueRequest
import io.dataloom.api.queue.QueueEntry
import io.dataloom.api.queue.QueueEntryState
import io.dataloom.api.retry.RetryOperation
import io.dataloom.api.runtime.RuntimeDependencies
import io.dataloom.api.runtime.RuntimeIdentifierGenerators
import io.dataloom.api.scheduling.ExistingSchedulePolicy
import io.dataloom.api.scheduling.ScheduleConstraints
import io.dataloom.api.scheduling.SchedulingDelay
import io.dataloom.api.strategy.OfflineFirstStrategyProfile
import io.dataloom.api.strategy.StrategyCacheState
import io.dataloom.api.strategy.StrategyConfigurationVersion
import io.dataloom.api.strategy.StrategyConnectivity
import io.dataloom.api.strategy.StrategyDecisionId
import io.dataloom.api.strategy.StrategyOperationInput
import io.dataloom.api.strategy.StrategyPlanId
import io.dataloom.api.strategy.StrategyProfileId
import io.dataloom.api.strategy.StrategyRuntimeEvidence
import io.dataloom.api.strategy.StrategySynchronizationRequest
import io.dataloom.api.synchronization.ChangeSetAcknowledgement
import io.dataloom.api.time.DataLoomInstant
import io.dataloom.api.time.SystemDataLoomClock
import io.dataloom.api.transport.PullChangesRequest
import io.dataloom.api.transport.PullChangesResult
import io.dataloom.api.transport.PushChangesRequest
import io.dataloom.api.transport.TransportProvider
import io.dataloom.runtime.facade.DataLoom
import io.dataloom.runtime.facade.DataLoomBuilder
import io.dataloom.runtime.facade.DataLoomQueueWorkerSpec
import io.dataloom.runtime.queue.QueueProcessingRequest
import io.dataloom.runtime.queue.QueueProcessingResult
import io.dataloom.runtime.queue.QueuedSynchronizationWork
import io.dataloom.runtime.queue.QueuedSynchronizationWorkResolution
import io.dataloom.runtime.queue.QueuedSynchronizationWorkResolver
import io.dataloom.runtime.retry.RetryBackoffStrategy
import io.dataloom.runtime.retry.StandardRetryPolicy
import io.dataloom.runtime.strategy.StrategySynchronizationExecutionResult
import io.dataloom.runtime.submission.QueuedSynchronizationSubmission
import io.dataloom.runtime.submission.QueuedSynchronizationWorkEncoder
import io.dataloom.runtime.submission.QueuedSynchronizationWorkEncodingResult
import io.dataloom.runtime.worker.QueueWorkerConfiguration
import io.dataloom.runtime.worker.QueueWorkerRunRequest
import io.dataloom.runtime.worker.QueueWorkerRunResult
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Robolectric-backed proof, through a real [DataLoomBuilder]-assembled
 * [DataLoom] and its real [io.dataloom.runtime.facade.DataLoomQueueWorker]
 * over a real Room-backed queue, that a queued entry whose first replay
 * attempt genuinely FAILS (a recoverable transport failure) is durably
 * rescheduled with its retry attempt advanced and then succeeds (or is
 * terminally failed) on a later attempt -- the retry half of the "retry /
 * circuit-breaker / conflict-detection behavior during queue replay (each
 * proven entry always succeeds on its first attempt)" gap `#101`'s
 * market-readiness row names. The sibling Android queue tests in this
 * directory all use an always-stop retry policy and a transport that always
 * succeeds.
 *
 * ## What this proves (two scenarios)
 *
 * [failedReplayRetriesAndSucceeds]
 * 1. An offline-first plan is durably admitted through [DataLoom.synchronize]
 *    (`DurablyEnqueued`); the transport records zero pulls.
 * 2. Worker cycle 1: the real replay reaches the transport, which returns a
 *    recoverable `NETWORK` failure. The real retry evaluator over a real
 *    [StandardRetryPolicy] (fixed backoff) reschedules the entry
 *    (`summary.rescheduled == 1`, `completed == 0`); the entry the work
 *    resolver saw had no retry attempt yet.
 * 3. Worker cycle 2, run immediately (before the backoff elapses, checked
 *    against the real wall clock): the real Room `acquire` honors the
 *    rescheduled `availableAt` and returns no work; the transport is not
 *    touched again.
 * 4. After a real wall-clock wait past `availableAt`, worker cycle 3: the
 *    entry read back out of Room carries `retryAttempt == 1` (the persisted
 *    retry state genuinely advanced across the reschedule, observed by the
 *    resolver on the re-acquired entry), the transport succeeds on its
 *    second call, and the entry completes. (`lastError` is deliberately not
 *    asserted: the Room queue clears it on lease acquisition.)
 * 5. A further cycle finds no work and the transport stays at two pulls: the
 *    completed entry is terminal and is not replayed.
 *
 * [exhaustedRetryEndsFailed] uses the same assembly with a transport that
 * always fails and `maximumAttempts = 1`: the first failure is rescheduled
 * (attempt 1, within the limit), the second is rejected by the policy's
 * attempt limit and the entry is durably `failed` (not rescheduled again), and
 * no further cycle replays it. This is what the advanced retry attempt buys:
 * had the attempt not been persisted across the reschedule, the second
 * failure would again be "attempt 1" and be rescheduled forever.
 *
 * ## Real wall-clock time
 *
 * Both tests use [SystemDataLoomClock] and [runBlocking] with real [delay]
 * (never virtual time), because the queue's `availableAt` guard and the
 * backoff are compared against genuine elapsed time.
 *
 * ## What this does not prove
 *
 * - The circuit-breaker half of the gap. `DataLoomBuilder.queueWorkerConfiguration`
 *   and `circuitQueueWorkerConfiguration` both replay through the unprotected
 *   `SynchronizationExecutionCoordinator`; `providerProtectionConfiguration`'s
 *   transport circuit wraps only `DataLoom.protectedSynchronization`, so a
 *   transport circuit cannot be opened or observed through a builder-assembled
 *   queue worker today. That composition remains proven only by the
 *   hand-assembled `ComposedQueueCircuitRobolectricTest`.
 * - Persisted `retryBudgetState`: the builder's queue-worker retry evaluator is
 *   built without a [io.dataloom.runtime.retry.RetryBudgetConfiguration], so the
 *   budget state is `null` here; the advanced `retryAttempt` is what is proven.
 * - Conflict detection during replay, KMP Android, iOS, a real WorkManager tick
 *   at the retry time, a real process kill between attempts, and a real
 *   emulator run (Robolectric only).
 */
@RunWith(RobolectricTestRunner::class)
class RetryQueueRobolectricTest {

    // Short class/method/database names are deliberate: Robolectric's
    // per-test temp directory embeds them, and this module's Windows runs hit
    // a MAX_PATH SQLite open failure otherwise (see
    // AndroidReferenceConsumerDurableQueueRobolectricTest).
    @Test
    fun failedReplayRetriesAndSucceeds() = runBlocking {
        val context: Context = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)

        val transport = ScriptedTransport(failuresBeforeSuccess = 1)
        val resolved = mutableListOf<QueueEntry>()
        val dataLoom = buildDataLoom(context, transport, maximumAttempts = 3, resolved = resolved)
        assertEquals(ProviderLifecycleResult.InitializeSuccess, dataLoom.initialize())

        val admission = dataLoom.synchronize(offlineFirstRequest())
        assertIs<StrategySynchronizationExecutionResult.DurablyEnqueued>(admission)
        assertEquals(0, transport.pullCalls)

        // Cycle 1: the first replay attempt fails and is rescheduled.
        val cycle1Start = System.currentTimeMillis()
        val cycle1 = runWorker(dataLoom, "c1")
        val processed1 = assertIs<QueueProcessingResult.Processed>(cycle1)
        assertEquals(1, processed1.summary.rescheduled)
        assertEquals(0, processed1.summary.completed)
        assertEquals(0, processed1.summary.failed)
        assertEquals(1, transport.pullCalls)
        assertNull(resolved.single().retryAttempt)
        val availableAt = assertNotNull(processed1.earliestRescheduledAt).epochMilliseconds
        assertTrue(
            availableAt >= cycle1Start + BACKOFF_MILLIS,
            "availableAt=$availableAt must be at least ${BACKOFF_MILLIS}ms after cycle start $cycle1Start",
        )

        // Cycle 2: before the backoff elapses the real Room acquire returns
        // nothing and the transport is not touched.
        val beforeBackoff = System.currentTimeMillis()
        assertTrue(beforeBackoff < availableAt, "cycle 2 must run inside the backoff window")
        assertIs<QueueProcessingResult.NoWork>(runWorker(dataLoom, "c2"))
        assertEquals(1, transport.pullCalls)
        assertEquals(1, resolved.size)

        // Cycle 3: after a real wait the entry is re-acquired from Room with
        // its advanced retry state and now succeeds.
        waitUntil(availableAt)
        val processed3 = assertIs<QueueProcessingResult.Processed>(runWorker(dataLoom, "c3"))
        assertEquals(1, processed3.summary.completed)
        assertEquals(0, processed3.summary.rescheduled)
        assertEquals(2, transport.pullCalls)
        assertEquals(2, resolved.size)
        val reacquired = resolved.last()
        assertEquals(1, reacquired.retryAttempt?.number)
        assertEquals(QueueEntryState.LEASED, reacquired.state)

        // Cycle 4: the completed entry is terminal and never replayed.
        assertIs<QueueProcessingResult.NoWork>(runWorker(dataLoom, "c4"))
        assertEquals(2, transport.pullCalls)

        assertEquals(ProviderLifecycleResult.ShutdownSuccess, dataLoom.shutdown())
    }

    @Test
    fun exhaustedRetryEndsFailed() = runBlocking {
        val context: Context = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)

        val transport = ScriptedTransport(failuresBeforeSuccess = Int.MAX_VALUE)
        val resolved = mutableListOf<QueueEntry>()
        val dataLoom = buildDataLoom(context, transport, maximumAttempts = 1, resolved = resolved)
        assertEquals(ProviderLifecycleResult.InitializeSuccess, dataLoom.initialize())

        assertIs<StrategySynchronizationExecutionResult.DurablyEnqueued>(
            dataLoom.synchronize(offlineFirstRequest()),
        )

        // Attempt 1 is within maximumAttempts = 1: rescheduled.
        val processed1 = assertIs<QueueProcessingResult.Processed>(runWorker(dataLoom, "e1"))
        assertEquals(1, processed1.summary.rescheduled)
        assertEquals(1, transport.pullCalls)
        val availableAt = assertNotNull(processed1.earliestRescheduledAt).epochMilliseconds

        // Attempt 2 exceeds the limit: terminally failed, not rescheduled.
        waitUntil(availableAt)
        val processed2 = assertIs<QueueProcessingResult.Processed>(runWorker(dataLoom, "e2"))
        assertEquals(1, processed2.summary.failed)
        assertEquals(0, processed2.summary.rescheduled)
        assertEquals(0, processed2.summary.completed)
        assertEquals(2, transport.pullCalls)
        assertEquals(1, resolved[1].retryAttempt?.number)

        // The failed entry is terminal: a later cycle finds nothing.
        assertIs<QueueProcessingResult.NoWork>(runWorker(dataLoom, "e3"))
        assertEquals(2, transport.pullCalls)

        assertEquals(ProviderLifecycleResult.ShutdownSuccess, dataLoom.shutdown())
    }

    private suspend fun runWorker(dataLoom: DataLoom, tag: String): QueueProcessingResult {
        val acquiredAt = System.currentTimeMillis()
        val result = dataLoom.queueWorker!!.run(
            QueueWorkerRunRequest(
                processingRequest = QueueProcessingRequest(
                    acquireRequest = QueueAcquireRequest(
                        consumerId = QueueConsumerId("retry-queue-consumer"),
                        leaseId = QueueLeaseId("retry-queue-lease-$tag"),
                        acquiredAt = DataLoomInstant(acquiredAt),
                        leaseExpiresAt = DataLoomInstant(acquiredAt + 60_000L),
                        maxEntries = 10,
                    ),
                ),
                recoveryRequest = null,
            ),
        )
        return assertIs<QueueWorkerRunResult.ProcessingCompleted>(result).processingResult
    }

    /** Real wall-clock wait (never virtual time) until [epochMillis] has passed. */
    private suspend fun waitUntil(epochMillis: Long) {
        while (System.currentTimeMillis() <= epochMillis) {
            delay(25)
        }
    }

    private fun offlineFirstRequest(): StrategySynchronizationRequest {
        val suffix = UUID.randomUUID().toString().take(8)
        return StrategySynchronizationRequest(
            request = SynchronizationRequest(
                workflowId = WorkflowId("retry-queue-workflow-$suffix"),
                sessionId = SynchronizationSessionId("retry-queue-session-$suffix"),
                direction = SynchronizationDirection.PULL,
                mode = SynchronizationMode.FULL,
                context = ExecutionContext(
                    executionId = ExecutionId("retry-queue-execution-$suffix"),
                    correlationId = CorrelationId("retry-queue-correlation-$suffix"),
                ),
            ),
            decisionId = StrategyDecisionId("retry-queue-decision-$suffix"),
            planId = StrategyPlanId("retry-queue-plan-$suffix"),
            profile = OfflineFirstStrategyProfile(
                id = StrategyProfileId("retry-queue-offline-first-profile"),
                configurationVersion = StrategyConfigurationVersion(1L),
                requireDurableQueue = true,
                reconcileWhenOnline = false,
            ),
            evidence = StrategyRuntimeEvidence(
                connectivity = StrategyConnectivity.AVAILABLE,
                cacheState = StrategyCacheState.MISSING,
            ),
            input = StrategyOperationInput.ProviderBacked,
        )
    }

    private fun buildDataLoom(
        context: Context,
        transport: TransportProvider,
        maximumAttempts: Int,
        resolved: MutableList<QueueEntry>,
    ): DataLoom {
        val suffix = UUID.randomUUID().toString().take(8)
        val providers = androidDataLoomProviders(
            context = context,
            storageDatabaseName = "rq-storage-$suffix.db",
            queueDatabaseName = "rq-queue-$suffix.db",
        )
        val bindings = SynchronizationProviderBindings(
            storageProviderId = providers.storage.descriptor.id,
            transportProviderId = transport.descriptor.id,
            schedulerProviderId = providers.scheduler.descriptor.id,
            connectivityProviderId = providers.connectivity.descriptor.id,
            queueProviderId = providers.queue.descriptor.id,
        )
        return DataLoomBuilder()
            .runtimeDependencies(
                RuntimeDependencies(
                    clock = SystemDataLoomClock(),
                    identifiers = RuntimeIdentifierGenerators(
                        synchronizationEventIds = uuidGenerator(::SynchronizationEventId),
                        queueEntryIds = uuidGenerator(::QueueEntryId),
                        queueLeaseIds = uuidGenerator(::QueueLeaseId),
                        conflictIds = uuidGenerator(::ConflictId),
                    ),
                ),
            )
            .installAndroidProviders(providers, transport)
            .queueSubmissionEncoder(PassthroughEncoder)
            .queueWorkerConfiguration(
                DataLoomQueueWorkerSpec(
                    workResolver = QueuedSynchronizationWorkResolver { entry ->
                        resolved += entry
                        QueuedSynchronizationWorkResolution.Resolved(
                            QueuedSynchronizationWork(
                                request = entry.synchronizationRequest,
                                bindings = bindings,
                                strategyDecision = entry.strategyDecision,
                                strategyPlan = entry.strategyPlan,
                            ),
                        )
                    },
                    retryPolicy = StandardRetryPolicy(
                        id = RetryPolicyId("retry-queue-policy"),
                        strategy = RetryBackoffStrategy.Fixed(SchedulingDelay(BACKOFF_MILLIS)),
                        maximumAttempts = maximumAttempts,
                    ),
                    retryOperation = RetryOperation("retry-queue.pull"),
                    configuration = QueueWorkerConfiguration(
                        scheduleId = ScheduleId("retry-queue-worker"),
                        constraints = ScheduleConstraints(),
                        existingSchedulePolicy = ExistingSchedulePolicy.REPLACE,
                        continuationDelay = SchedulingDelay.ZERO,
                        recoverExpiredLeasesBeforeProcessing = false,
                    ),
                ),
            )
            .build()
    }

    private fun <T> uuidGenerator(construct: (String) -> T): IdentifierGenerator<T> =
        object : IdentifierGenerator<T> {
            override fun generate(): T = construct(UUID.randomUUID().toString())
        }

    private object PassthroughEncoder : QueuedSynchronizationWorkEncoder {
        override fun encode(
            submission: QueuedSynchronizationSubmission,
        ): QueuedSynchronizationWorkEncodingResult =
            QueuedSynchronizationWorkEncodingResult.Encoded(
                QueueEnqueueRequest(
                    entry = QueueEntry(
                        id = submission.queueEntryId,
                        synchronizationRequest = submission.work.request,
                        state = QueueEntryState.PENDING,
                        enqueuedAt = submission.availableAt,
                        availableAt = submission.availableAt,
                        strategyDecision = submission.work.strategyDecision,
                        strategyPlan = submission.work.strategyPlan,
                    ),
                ),
            )
    }

    private data class InjectedNetworkFailure(
        override val code: ErrorCode = INJECTED_CODE,
        override val category: ErrorCategory = ErrorCategory.NETWORK,
        override val severity: ErrorSeverity = ErrorSeverity.ERROR,
        override val recoverability: Recoverability = Recoverability.RECOVERABLE,
        override val message: String = "Sanitized injected transport failure for the retry queue test.",
        override val cause: Throwable? = null,
    ) : DataLoomError

    /**
     * Test-only [TransportProvider] whose [pullChanges] fails its first
     * [failuresBeforeSuccess] calls with a recoverable network error and
     * succeeds afterwards.
     */
    private class ScriptedTransport(
        private val failuresBeforeSuccess: Int,
    ) : TransportProvider {
        var pullCalls: Int = 0
            private set

        override val descriptor: ProviderDescriptor = ProviderDescriptor(
            id = ProviderId("io.dataloom.consumer.android.test.retry-queue-transport"),
            name = ProviderName("Retry Queue Scripted Test Transport"),
            type = ProviderType.TRANSPORT,
            version = ProviderVersion("1.0.0"),
        )

        override suspend fun initialize(
            context: ProviderInitializationContext,
        ): ProviderOperationResult<Unit> = ProviderOperationResult.Success(Unit)

        override suspend fun health(): ProviderOperationResult<ProviderHealth> =
            ProviderOperationResult.Success(ProviderHealth(status = ProviderHealthStatus.HEALTHY))

        override suspend fun close(): ProviderOperationResult<Unit> = ProviderOperationResult.Success(Unit)

        override suspend fun pushChanges(
            request: PushChangesRequest,
        ): ProviderOperationResult<ChangeSetAcknowledgement> =
            error("ScriptedTransport does not support push.")

        override suspend fun pullChanges(
            request: PullChangesRequest,
        ): ProviderOperationResult<PullChangesResult> {
            pullCalls += 1
            return if (pullCalls <= failuresBeforeSuccess) {
                ProviderOperationResult.Failure(InjectedNetworkFailure())
            } else {
                ProviderOperationResult.Success(
                    PullChangesResult.Changes(
                        changeSet = ChangeSet(
                            id = ChangeSetId("retry-queue-change-set"),
                            events = listOf(
                                ChangeEvent(
                                    id = ChangeEventId("retry-queue-event"),
                                    entity = EntityReference(
                                        type = EntityType("retry-queue-entity"),
                                        id = EntityId("retry-queue-entity-1"),
                                    ),
                                    operation = ChangeOperation.CREATE,
                                ),
                            ),
                        ),
                        hasMore = false,
                    ),
                )
            }
        }
    }

    private companion object {
        const val BACKOFF_MILLIS = 1_500L
        val INJECTED_CODE = ErrorCode("RETRY_QUEUE_NETWORK_FAILURE")
    }
}
