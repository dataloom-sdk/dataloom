package io.dataloom.runtime.retry

import io.dataloom.api.circuit.CircuitBreakerCompareAndSetRequest
import io.dataloom.api.circuit.CircuitBreakerCompareAndSetResult
import io.dataloom.api.circuit.CircuitBreakerLoadResult
import io.dataloom.api.circuit.CircuitBreakerPhase
import io.dataloom.api.circuit.CircuitBreakerScope
import io.dataloom.api.circuit.CircuitBreakerStateRecord
import io.dataloom.api.circuit.CircuitBreakerStateStore
import io.dataloom.api.context.ExecutionContext
import io.dataloom.api.error.DataLoomError
import io.dataloom.api.error.ErrorCategory
import io.dataloom.api.error.ErrorCode
import io.dataloom.api.error.ErrorSeverity
import io.dataloom.api.error.Recoverability
import io.dataloom.api.identifier.CorrelationId
import io.dataloom.api.identifier.ExecutionId
import io.dataloom.api.identifier.RetryPolicyId
import io.dataloom.api.identifier.SynchronizationSessionId
import io.dataloom.api.identifier.WorkflowId
import io.dataloom.api.model.SynchronizationDirection
import io.dataloom.api.model.SynchronizationMode
import io.dataloom.api.model.SynchronizationRequest
import io.dataloom.api.provider.ProviderDescriptor
import io.dataloom.api.provider.ProviderHealth
import io.dataloom.api.provider.ProviderHealthStatus
import io.dataloom.api.provider.ProviderInitializationContext
import io.dataloom.api.provider.ProviderId
import io.dataloom.api.provider.ProviderName
import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.api.provider.ProviderType
import io.dataloom.api.provider.ProviderVersion
import io.dataloom.api.retry.RetryAttempt
import io.dataloom.api.scheduling.SchedulingDelay
import io.dataloom.api.synchronization.ChangeSetAcknowledgement
import io.dataloom.api.synchronization.SynchronizationResult
import io.dataloom.api.synchronization.SynchronizationSummary
import io.dataloom.api.time.DataLoomClock
import io.dataloom.api.time.DataLoomInstant
import io.dataloom.api.transport.PullChangesRequest
import io.dataloom.api.transport.PullChangesResult
import io.dataloom.api.transport.PushChangesRequest
import io.dataloom.api.transport.TransportProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest

/** Book 2 AC-FUNC-004 reference flow using the assembled common runtime. */
class RetryCircuitFunctionalQualificationTest {
    @Test
    fun `jittered retry opens durable circuit admits one probe and recovers`() = runTest {
        val clock = MutableClock(1_000L)
        val stateStore = InMemoryCircuitStateStore()
        val providerFailure = RecoverableNetworkError()
        val provider = FaultInjectingTransportProvider(providerFailure)
        val random = SequenceRetryRandomSource(40L, 75L)
        val retryEvaluator = SynchronizationRetryEvaluator(
            retryPolicy = StandardRetryPolicy(
                id = RetryPolicyId("ac-func-004"),
                strategy = RetryBackoffStrategy.Exponential(
                    initialDelay = SchedulingDelay(100L),
                    multiplier = 2,
                    maximumDelay = SchedulingDelay(1_000L),
                ),
                maximumAttempts = 4,
                jitterStrategy = RetryJitterStrategy.Full,
                randomSource = random,
            ),
            clock = clock,
        )
        val firstRuntime = protectedTransportRuntime(clock, stateStore, provider)

        val firstFailure = assertIs<CircuitBreakerExecutionResult.Executed<Unit>>(
            firstRuntime.initialize(scope, ProviderInitializationContext()),
        )
        assertSame(
            providerFailure,
            assertIs<CircuitProtectedOperationResult.Failure>(firstFailure.operationResult).error,
        )
        assertEquals(CircuitBreakerPhase.CLOSED, stateStore.record(scope)?.state?.phase)
        assertEquals(1, stateStore.record(scope)?.state?.consecutiveFailures)

        val firstRetry = assertIs<SynchronizationRetryEvaluation.ShouldRetry>(
            retryEvaluator.evaluate(
                result = failedSynchronization(providerFailure, clock.now()),
                retryAttempt = RetryAttempt(1),
                retryOperation = operation,
            ),
        )
        assertEquals(SchedulingDelay(40L), firstRetry.selectedDelay)
        assertEquals(DataLoomInstant(1_040L), firstRetry.availableAt)

        clock.nowMillis = firstRetry.availableAt.epochMilliseconds
        val secondFailure = assertIs<CircuitBreakerExecutionResult.Executed<Unit>>(
            firstRuntime.initialize(scope, ProviderInitializationContext()),
        )
        val opened = assertIs<CircuitBreakerRecordResult.Recorded>(secondFailure.recordResult)
        assertEquals(CircuitBreakerPhase.OPEN, opened.record.state.phase)
        assertEquals(DataLoomInstant(2_040L), opened.record.state.openUntil)

        val secondRetry = assertIs<SynchronizationRetryEvaluation.ShouldRetry>(
            retryEvaluator.evaluate(
                result = failedSynchronization(providerFailure, clock.now()),
                retryAttempt = RetryAttempt(2),
                retryOperation = operation,
            ),
        )
        assertEquals(SchedulingDelay(75L), secondRetry.selectedDelay)
        assertEquals(DataLoomInstant(1_115L), secondRetry.availableAt)
        assertEquals(listOf(100L, 200L), random.maximums)

        // A new coordinator reads the same durable record after runtime recreation.
        val restartedRuntime = protectedTransportRuntime(clock, stateStore, provider)
        clock.nowMillis = secondRetry.availableAt.epochMilliseconds
        val rejected = assertIs<CircuitBreakerExecutionResult.Rejected>(
            restartedRuntime.initialize(scope, ProviderInitializationContext()),
        )
        assertEquals(CircuitBreakerRejectionReason.OPEN, rejected.reason)
        assertEquals(DataLoomInstant(2_040L), rejected.retryAt)
        assertEquals(2, provider.initializeCalls)

        // At the exact deadline, one runtime owns the persisted probe lease.
        clock.nowMillis = checkNotNull(rejected.retryAt).epochMilliseconds
        val probe = async {
            restartedRuntime.initialize(scope, ProviderInitializationContext())
        }
        provider.probeStarted.await()
        val competingRuntime = protectedTransportRuntime(clock, stateStore, provider)
        val competingProbe = assertIs<CircuitBreakerExecutionResult.Rejected>(
            competingRuntime.initialize(scope, ProviderInitializationContext()),
        )
        assertEquals(CircuitBreakerRejectionReason.PROBE_IN_FLIGHT, competingProbe.reason)
        assertEquals(DataLoomInstant(2_540L), competingProbe.retryAt)
        assertEquals(3, provider.initializeCalls)

        provider.releaseProbe.complete(Unit)
        val recovered = assertIs<CircuitBreakerExecutionResult.Executed<Unit>>(probe.await())
        assertIs<CircuitProtectedOperationResult.Success<Unit>>(recovered.operationResult)
        val closed = assertIs<CircuitBreakerRecordResult.Recorded>(recovered.recordResult)
        assertEquals(CircuitBreakerPhase.CLOSED, closed.record.state.phase)
        assertEquals(1L, closed.record.state.probeGeneration)

        clock.nowMillis += 1L
        val normal = assertIs<CircuitBreakerExecutionResult.Executed<Unit>>(
            competingRuntime.initialize(scope, ProviderInitializationContext()),
        )
        assertIs<CircuitProtectedOperationResult.Success<Unit>>(normal.operationResult)
        assertIs<CircuitBreakerRecordResult.Ignored>(normal.recordResult)
        assertEquals(4, provider.initializeCalls)
    }

    private fun protectedTransportRuntime(
        clock: DataLoomClock,
        stateStore: CircuitBreakerStateStore,
        provider: TransportProvider,
    ): CircuitBreakerTransportOperationAdapter = CircuitBreakerTransportOperationAdapter(
        transportProvider = provider,
        executionGate = CircuitBreakerExecutionGate(
            CircuitBreakerCoordinator(
                configuration = CircuitBreakerConfiguration(
                    failureThreshold = 2,
                    failureWindow = SchedulingDelay(5_000L),
                    openDuration = SchedulingDelay(1_000L),
                    halfOpenProbeLeaseDuration = SchedulingDelay(500L),
                ),
                clock = clock,
                stateStore = stateStore,
            ),
        ),
    )

    private fun failedSynchronization(
        error: DataLoomError,
        completedAt: DataLoomInstant,
    ): SynchronizationResult.Failed = SynchronizationResult.Failed(
        request = synchronizationRequest,
        completedAt = completedAt,
        summary = SynchronizationSummary(),
        error = error,
    )

    private class MutableClock(
        var nowMillis: Long,
    ) : DataLoomClock {
        override fun now(): DataLoomInstant = DataLoomInstant(nowMillis)
    }

    private class SequenceRetryRandomSource(
        vararg values: Long,
    ) : RetryRandomSource {
        private val remaining = values.toMutableList()
        val maximums = mutableListOf<Long>()

        override fun sample(request: RetryRandomRequest): Long {
            maximums += request.maximumInclusive
            return remaining.removeAt(0)
        }
    }

    private class InMemoryCircuitStateStore : CircuitBreakerStateStore {
        private val records = mutableMapOf<CircuitBreakerScope, CircuitBreakerStateRecord>()

        fun record(scope: CircuitBreakerScope): CircuitBreakerStateRecord? = records[scope]

        override suspend fun load(
            scope: CircuitBreakerScope,
        ): ProviderOperationResult<CircuitBreakerLoadResult> = ProviderOperationResult.Success(
            records[scope]?.let(CircuitBreakerLoadResult::Found)
                ?: CircuitBreakerLoadResult.Missing,
        )

        override suspend fun compareAndSet(
            request: CircuitBreakerCompareAndSetRequest,
        ): ProviderOperationResult<CircuitBreakerCompareAndSetResult> {
            val current = records[request.scope]
            if (current?.version != request.expectedVersion) {
                return ProviderOperationResult.Success(
                    CircuitBreakerCompareAndSetResult.Conflict(current),
                )
            }
            val next = CircuitBreakerStateRecord(
                state = request.nextState,
                version = (current?.version ?: -1L) + 1L,
            )
            records[request.scope] = next
            return ProviderOperationResult.Success(
                CircuitBreakerCompareAndSetResult.Updated(next),
            )
        }
    }

    private class FaultInjectingTransportProvider(
        private val failure: DataLoomError,
    ) : TransportProvider {
        var initializeCalls: Int = 0
            private set
        val probeStarted = CompletableDeferred<Unit>()
        val releaseProbe = CompletableDeferred<Unit>()

        override val descriptor: ProviderDescriptor = ProviderDescriptor(
            id = providerId,
            name = ProviderName("AC-FUNC-004 transport"),
            type = ProviderType.TRANSPORT,
            version = ProviderVersion("1.0.0"),
        )

        override suspend fun initialize(
            context: ProviderInitializationContext,
        ): ProviderOperationResult<Unit> {
            initializeCalls += 1
            return when (initializeCalls) {
                1, 2 -> ProviderOperationResult.Failure(failure)
                3 -> {
                    probeStarted.complete(Unit)
                    releaseProbe.await()
                    ProviderOperationResult.Success(Unit)
                }
                else -> ProviderOperationResult.Success(Unit)
            }
        }

        override suspend fun health(): ProviderOperationResult<ProviderHealth> =
            ProviderOperationResult.Success(ProviderHealth(ProviderHealthStatus.HEALTHY))

        override suspend fun close(): ProviderOperationResult<Unit> =
            ProviderOperationResult.Success(Unit)

        override suspend fun pushChanges(
            request: PushChangesRequest,
        ): ProviderOperationResult<ChangeSetAcknowledgement> = error("Not used by this flow.")

        override suspend fun pullChanges(
            request: PullChangesRequest,
        ): ProviderOperationResult<PullChangesResult> = error("Not used by this flow.")
    }

    private data class RecoverableNetworkError(
        override val code: ErrorCode = ErrorCode("AC_FUNC_004_INJECTED_NETWORK_FAILURE"),
        override val category: ErrorCategory = ErrorCategory.NETWORK,
        override val severity: ErrorSeverity = ErrorSeverity.ERROR,
        override val recoverability: Recoverability = Recoverability.RECOVERABLE,
        override val message: String = "Sanitized injected transport failure.",
        override val cause: Throwable? = null,
    ) : DataLoomError

    private companion object {
        val providerId = ProviderId("ac-func-004-transport")
        val operation = TransportCircuitOperation.INITIALIZE.retryOperation
        val scope = CircuitBreakerScope.providerOperation(providerId, operation)
        val synchronizationRequest = SynchronizationRequest(
            workflowId = WorkflowId("ac-func-004-workflow"),
            sessionId = SynchronizationSessionId("ac-func-004-session"),
            direction = SynchronizationDirection.PUSH,
            mode = SynchronizationMode.DELTA,
            context = ExecutionContext(
                executionId = ExecutionId("ac-func-004-execution"),
                correlationId = CorrelationId("ac-func-004-correlation"),
            ),
        )
    }
}
