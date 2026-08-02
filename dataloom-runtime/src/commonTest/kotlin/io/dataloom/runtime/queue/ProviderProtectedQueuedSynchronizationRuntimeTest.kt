package io.dataloom.runtime.queue

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
import io.dataloom.api.identifier.SynchronizationSessionId
import io.dataloom.api.identifier.WorkflowId
import io.dataloom.api.model.SynchronizationDirection
import io.dataloom.api.model.SynchronizationMode
import io.dataloom.api.model.SynchronizationRequest
import io.dataloom.api.provider.ProviderId
import io.dataloom.api.provider.SynchronizationProviderBindings
import io.dataloom.api.queue.QueueEntry
import io.dataloom.api.queue.QueueEntryState
import io.dataloom.api.queue.QueueLease
import io.dataloom.api.retry.WorkflowTimeoutState
import io.dataloom.api.synchronization.SynchronizationResult
import io.dataloom.api.synchronization.SynchronizationSummary
import io.dataloom.api.time.DataLoomInstant
import io.dataloom.runtime.execution.SynchronizationExecutionRejectionReason
import io.dataloom.runtime.execution.SynchronizationExecutionResult
import io.dataloom.runtime.execution.protection.ProviderProtectedSynchronizationResult
import io.dataloom.runtime.facade.DataLoomProtectedSynchronization
import io.dataloom.runtime.facade.ProviderProtectedSynchronizationExecutionResult
import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ProviderProtectedQueuedSynchronizationRuntimeTest {

    @Test
    fun `resolution rejection prevents protected synchronization`() {
        val error = error("WORK_RESOLUTION_REJECTED")
        val protected = RecordingProtectedSynchronization()
        val runtime = ProviderProtectedQueuedSynchronizationRuntime(
            workResolver = QueuedSynchronizationWorkResolver {
                QueuedSynchronizationWorkResolution.Rejected(error)
            },
            protectedSynchronization = protected,
        )

        val result = runSuspend { runtime.execute(entry()) }

        val rejected = assertIs<ProviderProtectedQueuedSynchronizationResult.ResolutionRejected>(result)
        assertEquals(entryId, rejected.queueEntryId)
        assertEquals(error, rejected.error)
        assertEquals(0, protected.calls)
    }

    @Test
    fun `persisted deadline without executor fails before protected synchronization`() {
        val protected = RecordingProtectedSynchronization()
        val runtime = ProviderProtectedQueuedSynchronizationRuntime(
            workResolver = resolver(),
            protectedSynchronization = protected,
            workflowTimeoutExecutor = null,
        )

        val result = runSuspend {
            runtime.execute(
                entry(
                    workflowTimeoutState = WorkflowTimeoutState(
                        startedAt = DataLoomInstant(1_000L),
                        deadline = DataLoomInstant(3_000L),
                    ),
                ),
            )
        }

        val rejected = assertIs<ProviderProtectedQueuedSynchronizationResult.WorkflowTimeoutRejected>(result)
        assertEquals("QUEUED_WORKFLOW_TIMEOUT_EXECUTOR_NOT_CONFIGURED", rejected.error.code.value)
        assertEquals(0, protected.calls)
    }

    @Test
    fun `structural admission rejection is preserved exactly`() {
        val rejection = SynchronizationExecutionResult.Rejected(
            SynchronizationExecutionRejectionReason.PROVIDERS_NOT_INITIALIZED,
        )
        val protected = RecordingProtectedSynchronization(
            result = ProviderProtectedSynchronizationExecutionResult.Rejected(rejection),
        )
        val runtime = ProviderProtectedQueuedSynchronizationRuntime(
            workResolver = resolver(),
            protectedSynchronization = protected,
        )

        val result = runSuspend { runtime.execute(entry()) }

        val rejected = assertIs<ProviderProtectedQueuedSynchronizationResult.ExecutionRejected>(result)
        assertEquals(rejection, rejected.rejection)
        assertEquals(1, protected.calls)
        assertEquals(bindings, protected.lastBindings)
    }

    @Test
    fun `executed protected result preserves synchronization and provider evidence`() {
        val synchronizationResult = SynchronizationResult.Succeeded(
            request = request,
            completedAt = DataLoomInstant(2_000L),
            summary = SynchronizationSummary(),
        )
        val protectedResult = ProviderProtectedSynchronizationResult(
            synchronizationResult = synchronizationResult,
            operationEvidence = emptyList(),
        )
        val protected = RecordingProtectedSynchronization(
            result = ProviderProtectedSynchronizationExecutionResult.Executed(protectedResult),
        )
        val runtime = ProviderProtectedQueuedSynchronizationRuntime(
            workResolver = resolver(),
            protectedSynchronization = protected,
        )

        val result = runSuspend { runtime.execute(entry()) }

        val executed = assertIs<ProviderProtectedQueuedSynchronizationResult.Executed>(result)
        assertEquals(entryId, executed.queueEntryId)
        assertEquals(protectedResult, executed.result)
        assertEquals(1, protected.calls)
    }

    private class RecordingProtectedSynchronization(
        private val result: ProviderProtectedSynchronizationExecutionResult =
            ProviderProtectedSynchronizationExecutionResult.Rejected(
                SynchronizationExecutionResult.Rejected(
                    SynchronizationExecutionRejectionReason.PROVIDERS_NOT_INITIALIZED,
                ),
            ),
    ) : DataLoomProtectedSynchronization {
        var calls: Int = 0
            private set
        var lastBindings: SynchronizationProviderBindings? = null
            private set

        override suspend fun synchronize(
            request: SynchronizationRequest,
        ): ProviderProtectedSynchronizationExecutionResult {
            error("Default protected bindings must not be selected for queued execution.")
        }

        override suspend fun synchronize(
            request: SynchronizationRequest,
            bindings: SynchronizationProviderBindings,
        ): ProviderProtectedSynchronizationExecutionResult {
            calls++
            lastBindings = bindings
            return result
        }
    }

    private fun resolver(): QueuedSynchronizationWorkResolver =
        QueuedSynchronizationWorkResolver {
            QueuedSynchronizationWorkResolution.Resolved(
                QueuedSynchronizationWork(request = request, bindings = bindings),
            )
        }

    private fun entry(
        workflowTimeoutState: WorkflowTimeoutState? = null,
    ): QueueEntry = QueueEntry(
        id = entryId,
        synchronizationRequest = request,
        state = QueueEntryState.LEASED,
        enqueuedAt = DataLoomInstant(1_000L),
        availableAt = DataLoomInstant(1_000L),
        lease = QueueLease(
            id = QueueLeaseId("lease-1"),
            consumerId = QueueConsumerId("consumer-1"),
            acquiredAt = DataLoomInstant(1_100L),
            expiresAt = DataLoomInstant(4_000L),
        ),
        workflowTimeoutState = workflowTimeoutState,
    )

    private fun error(code: String): DataLoomError = TestError(ErrorCode(code))

    private fun <T> runSuspend(block: suspend () -> T): T {
        var completed: Result<T>? = null
        block.startCoroutine(object : Continuation<T> {
            override val context: CoroutineContext = EmptyCoroutineContext
            override fun resumeWith(result: Result<T>) {
                completed = result
            }
        })
        return checkNotNull(completed).getOrThrow()
    }

    private data class TestError(
        override val code: ErrorCode,
        override val category: ErrorCategory = ErrorCategory.CONFIGURATION,
        override val severity: ErrorSeverity = ErrorSeverity.ERROR,
        override val recoverability: Recoverability = Recoverability.NON_RECOVERABLE,
        override val message: String = "Protected queued synchronization test error.",
        override val cause: Throwable? = null,
    ) : DataLoomError

    private companion object {
        val entryId = QueueEntryId("protected-queued-entry")
        val request = SynchronizationRequest(
            workflowId = WorkflowId("workflow-1"),
            sessionId = SynchronizationSessionId("session-1"),
            direction = SynchronizationDirection.PUSH,
            mode = SynchronizationMode.DELTA,
            context = ExecutionContext(
                executionId = ExecutionId("execution-1"),
                correlationId = CorrelationId("correlation-1"),
            ),
        )
        val bindings = SynchronizationProviderBindings(
            storageProviderId = ProviderId("storage-1"),
            transportProviderId = ProviderId("transport-1"),
        )
    }
}
