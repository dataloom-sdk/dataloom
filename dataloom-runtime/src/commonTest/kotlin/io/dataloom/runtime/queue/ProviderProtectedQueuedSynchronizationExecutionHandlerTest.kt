package io.dataloom.runtime.queue

import io.dataloom.api.connectivity.ConnectivityRequirement
import io.dataloom.api.context.ExecutionContext
import io.dataloom.api.error.DataLoomError
import io.dataloom.api.error.ErrorCategory
import io.dataloom.api.error.ErrorCode
import io.dataloom.api.error.ErrorSeverity
import io.dataloom.api.error.Recoverability
import io.dataloom.api.identifier.CorrelationId
import io.dataloom.api.identifier.ExecutionId
import io.dataloom.api.identifier.QueueConsumerId
import io.dataloom.api.identifier.QueueEntryId
import io.dataloom.api.identifier.QueueLeaseId
import io.dataloom.api.identifier.RetryPolicyId
import io.dataloom.api.identifier.SynchronizationSessionId
import io.dataloom.api.identifier.WorkflowId
import io.dataloom.api.model.SynchronizationDirection
import io.dataloom.api.model.SynchronizationMode
import io.dataloom.api.model.SynchronizationRequest
import io.dataloom.api.provider.ProviderId
import io.dataloom.api.provider.SynchronizationProviderBindings
import io.dataloom.api.queue.QueueEntry
import io.dataloom.api.queue.QueueEntryState
import io.dataloom.api.queue.QueueFailureDisposition
import io.dataloom.api.queue.QueueLease
import io.dataloom.api.retry.RetryAttempt
import io.dataloom.api.retry.RetryOperation
import io.dataloom.api.retry.WorkflowTimeoutState
import io.dataloom.api.strategy.BuiltInSynchronizationStrategy
import io.dataloom.api.strategy.PersistedStrategyDecision
import io.dataloom.api.strategy.StrategyConfigurationVersion
import io.dataloom.api.strategy.StrategyDecisionId
import io.dataloom.api.strategy.StrategyDisposition
import io.dataloom.api.strategy.StrategyPlanId
import io.dataloom.api.strategy.StrategyProfileId
import io.dataloom.api.scheduling.SchedulingDelay
import io.dataloom.api.synchronization.SynchronizationResult
import io.dataloom.api.synchronization.SynchronizationSummary
import io.dataloom.api.time.DataLoomClock
import io.dataloom.api.time.DataLoomInstant
import io.dataloom.runtime.connectivity.SynchronizationConnectivityConfiguration
import io.dataloom.runtime.execution.SynchronizationExecutionRejectionReason
import io.dataloom.runtime.execution.SynchronizationExecutionResult
import io.dataloom.runtime.execution.protection.ProviderProtectedSynchronizationResult
import io.dataloom.runtime.execution.protection.ProviderProtectionInvocation
import io.dataloom.runtime.execution.protection.ProviderProtectionOperationEvidence
import io.dataloom.runtime.facade.DataLoomProtectedSynchronization
import io.dataloom.runtime.facade.ProviderProtectedSynchronizationExecutionResult
import io.dataloom.runtime.retry.CircuitBreakerRecordResult
import io.dataloom.runtime.retry.RetryBackoffStrategy
import io.dataloom.runtime.retry.StandardRetryPolicy
import io.dataloom.runtime.retry.SynchronizationRetryEvaluator
import io.dataloom.runtime.retry.WorkflowTimeoutStateExecutor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlinx.coroutines.test.runTest

class ProviderProtectedQueuedSynchronizationExecutionHandlerTest {

    @Test
    fun `successful execution preserves exact bindings outcome and evidence`() = runTest {
        val request = request()
        val bindings = bindings()
        val evidence = successfulEvidence()
        val protectedResult = ProviderProtectedSynchronizationExecutionResult.Executed(
            ProviderProtectedSynchronizationResult(
                synchronizationResult = succeeded(request),
                operationEvidence = listOf(evidence),
            ),
        )
        val facade = RecordingProtectedSynchronization(protectedResult)
        val handler = handler(
            resolver = resolved(request, bindings),
            facade = facade,
        )

        val result = handler.execute(entry(request))

        assertEquals(1, facade.calls)
        assertSame(request, facade.lastRequest)
        assertSame(bindings, facade.lastBindings)
        assertIs<QueueEntryExecutionOutcome.Completed>(result.outcome)
        assertSame(protectedResult, result.executionResult)
        assertEquals(listOf(evidence), result.operationEvidence)
        assertEquals("storage.read-outbound-changes", result.operationEvidence.single().operation.value)
    }

    @Test
    fun `unknown recording failure stops retry while preserving provider success evidence`() = runTest {
        val request = request()
        val error = TestError(
            code = ErrorCode("PROVIDER_CIRCUIT_RECORDING_UNCONFIRMED"),
            category = ErrorCategory.STATE,
            recoverability = Recoverability.UNKNOWN,
        )
        val recordFailure = CircuitBreakerRecordResult.PersistenceFailure(
            TestError(
                code = ErrorCode("CIRCUIT_STORE_WRITE_FAILED"),
                category = ErrorCategory.STORAGE,
                recoverability = Recoverability.RECOVERABLE,
            ),
        )
        val evidence = ProviderProtectionOperationEvidence(
            providerId = ProviderId("storage-1"),
            operation = RetryOperation("storage.apply-inbound-changes"),
            invocation = ProviderProtectionInvocation.SUCCEEDED,
            recordResult = recordFailure,
        )
        val protectedResult = ProviderProtectedSynchronizationExecutionResult.Executed(
            ProviderProtectedSynchronizationResult(
                synchronizationResult = failed(request, error),
                operationEvidence = listOf(evidence),
            ),
        )
        val handler = handler(
            resolver = resolved(request, bindings()),
            facade = RecordingProtectedSynchronization(protectedResult),
            maximumAttempts = 10,
        )

        val result = handler.execute(entry(request))

        val outcome = assertIs<QueueEntryExecutionOutcome.Failed>(result.outcome)
        assertSame(error, outcome.error)
        assertEquals(QueueFailureDisposition.FAILED, outcome.disposition)
        assertEquals(listOf(evidence), result.operationEvidence)
        assertEquals(true, result.operationEvidence.single().providerSucceeded)
        assertEquals(false, result.operationEvidence.single().circuitRecordingAccepted)
    }

    @Test
    fun `connectivity rejection defers without provider evidence`() = runTest {
        val request = request()
        val rejection = ProviderProtectedSynchronizationExecutionResult.Rejected(
            SynchronizationExecutionResult.Rejected(
                reason = SynchronizationExecutionRejectionReason.CONNECTIVITY_REQUIREMENT_NOT_MET,
            ),
        )
        val handler = handler(
            resolver = resolved(request, bindings()),
            facade = RecordingProtectedSynchronization(rejection),
            connectivityConfiguration = SynchronizationConnectivityConfiguration(
                requirement = ConnectivityRequirement.AVAILABLE,
                offlineRescheduleDelay = SchedulingDelay(5_000L),
            ),
            clock = FixedClock(DataLoomInstant(10_000L)),
        )

        val result = handler.execute(entry(request))

        val outcome = assertIs<QueueEntryExecutionOutcome.Deferred>(result.outcome)
        assertEquals(DataLoomInstant(15_000L), outcome.availableAt)
        assertSame(rejection, result.executionResult)
        assertEquals(emptyList(), result.operationEvidence)
    }

    @Test
    fun `resolver rejection never invokes protected synchronization`() = runTest {
        val facade = RecordingProtectedSynchronization(
            ProviderProtectedSynchronizationExecutionResult.Executed(
                ProviderProtectedSynchronizationResult(
                    synchronizationResult = succeeded(request()),
                    operationEvidence = emptyList(),
                ),
            ),
        )
        val resolutionError = TestError(
            code = ErrorCode("QUEUE_WORK_DECODE_REJECTED"),
            category = ErrorCategory.SERIALIZATION,
            recoverability = Recoverability.NON_RECOVERABLE,
        )
        val handler = handler(
            resolver = QueuedSynchronizationWorkResolver {
                QueuedSynchronizationWorkResolution.Rejected(resolutionError)
            },
            facade = facade,
        )

        val result = handler.execute(entry(request()))

        val outcome = assertIs<QueueEntryExecutionOutcome.Failed>(result.outcome)
        assertSame(resolutionError, outcome.error)
        assertEquals(0, facade.calls)
        assertEquals(null, result.executionResult)
        assertEquals(emptyList(), result.operationEvidence)
    }

    @Test
    fun `changed resolved strategy decision prevents protected execution`() = runTest {
        val request = request()
        val facade = RecordingProtectedSynchronization(
            ProviderProtectedSynchronizationExecutionResult.Executed(
                ProviderProtectedSynchronizationResult(
                    synchronizationResult = succeeded(request),
                    operationEvidence = emptyList(),
                ),
            ),
        )
        val handler = handler(
            resolver = resolved(
                request = request,
                bindings = bindings(),
                strategyDecision = strategyDecision(version = 4L),
            ),
            facade = facade,
        )

        val result = handler.execute(
            entry(
                request = request,
                strategyDecision = strategyDecision(version = 3L),
            ),
        )

        val outcome = assertIs<QueueEntryExecutionOutcome.Failed>(result.outcome)
        assertEquals("DL-Q-STRATEGY-DECISION-MISMATCH", outcome.error.code.value)
        assertEquals(ErrorCategory.CONFIGURATION, outcome.error.category)
        assertEquals(Recoverability.NON_RECOVERABLE, outcome.error.recoverability)
        assertEquals(0, facade.calls)
        assertEquals(null, result.executionResult)
        assertEquals(emptyList(), result.operationEvidence)
    }

    @Test
    fun `matching resolved strategy decision reaches protected execution`() = runTest {
        val request = request()
        val decision = strategyDecision(version = 3L)
        val facade = RecordingProtectedSynchronization(
            ProviderProtectedSynchronizationExecutionResult.Executed(
                ProviderProtectedSynchronizationResult(
                    synchronizationResult = succeeded(request),
                    operationEvidence = emptyList(),
                ),
            ),
        )
        val handler = handler(
            resolver = resolved(request, bindings(), decision),
            facade = facade,
        )

        val result = handler.execute(entry(request, decision))

        assertIs<QueueEntryExecutionOutcome.Completed>(result.outcome)
        assertEquals(1, facade.calls)
    }

    @Test
    fun `expired persisted workflow deadline prevents protected execution`() = runTest {
        val request = request()
        val facade = RecordingProtectedSynchronization(
            ProviderProtectedSynchronizationExecutionResult.Executed(
                ProviderProtectedSynchronizationResult(
                    synchronizationResult = succeeded(request),
                    operationEvidence = emptyList(),
                ),
            ),
        )
        val handler = handler(
            resolver = resolved(request, bindings()),
            facade = facade,
            clock = FixedClock(DataLoomInstant(5_000L)),
            workflowTimeoutExecutor = WorkflowTimeoutStateExecutor(
                FixedClock(DataLoomInstant(5_000L)),
            ),
        )
        val entry = entry(request).copy(
            workflowTimeoutState = WorkflowTimeoutState(
                startedAt = DataLoomInstant(1_000L),
                deadline = DataLoomInstant(5_000L),
            ),
        )

        val result = handler.execute(entry)

        val outcome = assertIs<QueueEntryExecutionOutcome.Failed>(result.outcome)
        assertEquals("QUEUED_WORKFLOW_DEADLINE_EXCEEDED", outcome.error.code.value)
        assertEquals(0, facade.calls)
        assertEquals(null, result.executionResult)
    }

    @Test
    fun `maximum retry attempt fails without integer overflow`() = runTest {
        val request = request()
        val recoverable = TestError(
            code = ErrorCode("NETWORK_RETRY"),
            category = ErrorCategory.NETWORK,
            recoverability = Recoverability.RECOVERABLE,
        )
        val handler = handler(
            resolver = resolved(request, bindings()),
            facade = RecordingProtectedSynchronization(
                ProviderProtectedSynchronizationExecutionResult.Executed(
                    ProviderProtectedSynchronizationResult(
                        synchronizationResult = failed(request, recoverable),
                        operationEvidence = emptyList(),
                    ),
                ),
            ),
            maximumAttempts = Int.MAX_VALUE,
        )
        val entry = entry(request).copy(retryAttempt = RetryAttempt(Int.MAX_VALUE))

        val result = handler.execute(entry)

        val outcome = assertIs<QueueEntryExecutionOutcome.Failed>(result.outcome)
        assertEquals("DL-PROTECTED-QUEUE-RETRY-ATTEMPT-EXHAUSTED", outcome.error.code.value)
    }

    private fun handler(
        resolver: QueuedSynchronizationWorkResolver,
        facade: DataLoomProtectedSynchronization,
        maximumAttempts: Int = 0,
        connectivityConfiguration: SynchronizationConnectivityConfiguration? = null,
        clock: DataLoomClock = FixedClock(DataLoomInstant(2_000L)),
        workflowTimeoutExecutor: WorkflowTimeoutStateExecutor? = null,
    ): ProviderProtectedQueuedSynchronizationExecutionHandler =
        ProviderProtectedQueuedSynchronizationExecutionHandler(
            workResolver = resolver,
            protectedSynchronization = facade,
            retryEvaluator = SynchronizationRetryEvaluator(
                retryPolicy = StandardRetryPolicy(
                    id = RetryPolicyId("protected-queued-test"),
                    strategy = RetryBackoffStrategy.Immediate,
                    maximumAttempts = maximumAttempts,
                ),
                clock = clock,
            ),
            retryOperation = RetryOperation("queued.synchronize"),
            connectivityConfiguration = connectivityConfiguration,
            clock = clock,
            workflowTimeoutExecutor = workflowTimeoutExecutor,
        )

    private fun resolved(
        request: SynchronizationRequest,
        bindings: SynchronizationProviderBindings,
        strategyDecision: PersistedStrategyDecision? = null,
    ): QueuedSynchronizationWorkResolver = QueuedSynchronizationWorkResolver {
        QueuedSynchronizationWorkResolution.Resolved(
            QueuedSynchronizationWork(
                request = request,
                bindings = bindings,
                strategyDecision = strategyDecision,
            ),
        )
    }

    private class RecordingProtectedSynchronization(
        private val result: ProviderProtectedSynchronizationExecutionResult,
    ) : DataLoomProtectedSynchronization {
        var calls: Int = 0
            private set
        var lastRequest: SynchronizationRequest? = null
            private set
        var lastBindings: SynchronizationProviderBindings? = null
            private set

        override suspend fun synchronize(
            request: SynchronizationRequest,
        ): ProviderProtectedSynchronizationExecutionResult {
            calls++
            lastRequest = request
            return result
        }

        override suspend fun synchronize(
            request: SynchronizationRequest,
            bindings: SynchronizationProviderBindings,
        ): ProviderProtectedSynchronizationExecutionResult {
            calls++
            lastRequest = request
            lastBindings = bindings
            return result
        }
    }

    private class FixedClock(
        private val instant: DataLoomInstant,
    ) : DataLoomClock {
        override fun now(): DataLoomInstant = instant
    }

    private fun strategyDecision(
        version: Long,
    ): PersistedStrategyDecision = PersistedStrategyDecision(
        decisionId = StrategyDecisionId("decision-protected-1"),
        planId = StrategyPlanId("plan-protected-1"),
        requestedStrategy = BuiltInSynchronizationStrategy.ADAPTIVE,
        effectiveProfileId = StrategyProfileId("offline-profile"),
        effectiveStrategy = BuiltInSynchronizationStrategy.OFFLINE_FIRST,
        configurationVersion = StrategyConfigurationVersion(version),
        disposition = StrategyDisposition.DEFER,
    )

    private fun successfulEvidence(): ProviderProtectionOperationEvidence =
        ProviderProtectionOperationEvidence(
            providerId = ProviderId("storage-1"),
            operation = RetryOperation("storage.read-outbound-changes"),
            invocation = ProviderProtectionInvocation.SUCCEEDED,
            recordResult = CircuitBreakerRecordResult.Ignored,
        )

    private fun succeeded(request: SynchronizationRequest): SynchronizationResult.Succeeded =
        SynchronizationResult.Succeeded(
            request = request,
            completedAt = DataLoomInstant(2_000L),
            summary = SynchronizationSummary(),
        )

    private fun failed(
        request: SynchronizationRequest,
        error: DataLoomError,
    ): SynchronizationResult.Failed = SynchronizationResult.Failed(
        request = request,
        completedAt = DataLoomInstant(2_000L),
        summary = SynchronizationSummary(),
        error = error,
    )

    private fun bindings(): SynchronizationProviderBindings =
        SynchronizationProviderBindings(
            storageProviderId = ProviderId("storage-1"),
            transportProviderId = ProviderId("transport-1"),
        )

    private fun request(): SynchronizationRequest = SynchronizationRequest(
        workflowId = WorkflowId("workflow-1"),
        sessionId = SynchronizationSessionId("session-1"),
        direction = SynchronizationDirection.PUSH,
        mode = SynchronizationMode.DELTA,
        context = ExecutionContext(
            executionId = ExecutionId("execution-1"),
            correlationId = CorrelationId("correlation-1"),
        ),
    )

    private fun entry(
        request: SynchronizationRequest,
        strategyDecision: PersistedStrategyDecision? = null,
    ): QueueEntry = QueueEntry(
        id = QueueEntryId("entry-1"),
        synchronizationRequest = request,
        state = QueueEntryState.LEASED,
        enqueuedAt = DataLoomInstant(1_000L),
        availableAt = DataLoomInstant(1_000L),
        lease = QueueLease(
            id = QueueLeaseId("lease-1"),
            consumerId = QueueConsumerId("consumer-1"),
            acquiredAt = DataLoomInstant(1_500L),
            expiresAt = DataLoomInstant(10_000L),
        ),
        strategyDecision = strategyDecision,
    )

    private data class TestError(
        override val code: ErrorCode,
        override val category: ErrorCategory,
        override val recoverability: Recoverability,
        override val severity: ErrorSeverity = ErrorSeverity.ERROR,
        override val message: String = "Protected queued test error.",
        override val cause: Throwable? = null,
    ) : DataLoomError
}
