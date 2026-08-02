package io.dataloom.runtime.retry

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
import io.dataloom.api.identifier.CorrelationId
import io.dataloom.api.identifier.EntityId
import io.dataloom.api.identifier.EntityType
import io.dataloom.api.identifier.ExecutionId
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
import io.dataloom.api.scheduling.SchedulingDelay
import io.dataloom.api.synchronization.ChangeSetAcknowledgement
import io.dataloom.api.time.DataLoomClock
import io.dataloom.api.time.DataLoomInstant
import io.dataloom.api.transport.PullChangesRequest
import io.dataloom.api.transport.PullChangesResult
import io.dataloom.api.transport.PushChangesRequest
import io.dataloom.api.transport.TransportProvider
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest

class TransportRequestTimeoutRuntimeTest {

    @Test
    fun `descriptor and completed provider result are preserved exactly`() = runTest {
        val expected = TestError(
            code = ErrorCode("REMOTE_REJECTED"),
            category = ErrorCategory.NETWORK,
        )
        val delegate = RecordingTransportProvider(
            pushResult = ProviderOperationResult.Failure(expected),
        )
        val provider = requestTimed(delegate, 1_000L)

        assertSame(delegate.descriptor, provider.descriptor)
        val failure = assertIs<ProviderOperationResult.Failure>(
            provider.pushChanges(pushRequest),
        )
        assertSame(expected, failure.error)
        assertEquals(1, delegate.pushCalls)
    }

    @Test
    fun `zero request timeout protects push and pull but bypasses lifecycle`() = runTest {
        val delegate = RecordingTransportProvider()
        val provider = requestTimed(delegate, 0L)

        assertIs<ProviderOperationResult.Success<Unit>>(
            provider.initialize(ProviderInitializationContext()),
        )
        assertIs<ProviderOperationResult.Success<ProviderHealth>>(provider.health())
        assertIs<ProviderOperationResult.Success<Unit>>(provider.close())

        val pushFailure = assertIs<ProviderOperationResult.Failure>(
            provider.pushChanges(pushRequest),
        )
        val pullFailure = assertIs<ProviderOperationResult.Failure>(
            provider.pullChanges(pullRequest),
        )

        assertEquals(1, delegate.initializeCalls)
        assertEquals(1, delegate.healthCalls)
        assertEquals(1, delegate.closeCalls)
        assertEquals(0, delegate.pushCalls)
        assertEquals(0, delegate.pullCalls)
        assertEquals("TRANSPORT_REQUEST_TIMEOUT", pushFailure.error.code.value)
        assertEquals("TRANSPORT_REQUEST_TIMEOUT", pullFailure.error.code.value)
        assertEquals(ErrorCategory.NETWORK, pushFailure.error.category)
        assertEquals(ErrorCategory.NETWORK, pullFailure.error.category)
        assertEquals(Recoverability.UNKNOWN, pushFailure.error.recoverability)
        assertEquals(Recoverability.UNKNOWN, pullFailure.error.recoverability)
        assertTrue(pushFailure.error.message.contains("completion is not confirmed"))
        assertTrue(pullFailure.error.message.contains("completion is not confirmed"))
    }

    @Test
    fun `executing request timeout runs cooperative cleanup`() = runTest {
        val delegate = RecordingTransportProvider(delayMilliseconds = 1_000L)

        val failure = assertIs<ProviderOperationResult.Failure>(
            requestTimed(delegate, 100L).pushChanges(pushRequest),
        )

        assertEquals(1, delegate.pushCalls)
        assertTrue(delegate.pushFinallyExecuted)
        assertEquals("TRANSPORT_REQUEST_TIMEOUT", failure.error.code.value)
        assertEquals(Recoverability.UNKNOWN, failure.error.recoverability)
    }

    @Test
    fun `caller cancellation propagates from request timeout wrapper`() = runTest {
        val delegate = RecordingTransportProvider(delayMilliseconds = 10_000L)
        val provider = requestTimed(delegate, 20_000L)
        val execution = backgroundScope.async {
            provider.pushChanges(pushRequest)
        }
        delegate.pushStarted.await()

        execution.cancel(CancellationException("caller cancelled"))
        val failure = captureFailure { execution.await() }

        assertIs<CancellationException>(failure)
        assertEquals("caller cancelled", failure.message)
        assertEquals(1, delegate.pushCalls)
        assertTrue(delegate.pushFinallyExecuted)
    }

    @Test
    fun `workflow deadline outcome is mapped without invoking transport`() = runTest {
        val delegate = RecordingTransportProvider()
        val provider = scripted(
            delegate = delegate,
            result = RetryTimeoutExecutionResult.WorkflowDeadlineExceeded(
                DataLoomInstant(2_000L),
            ),
        )

        val failure = assertIs<ProviderOperationResult.Failure>(
            provider.pushChanges(pushRequest),
        )

        assertEquals(0, delegate.pushCalls)
        assertEquals("TRANSPORT_WORKFLOW_DEADLINE_EXCEEDED", failure.error.code.value)
        assertEquals(ErrorCategory.NETWORK, failure.error.category)
        assertEquals(Recoverability.NON_RECOVERABLE, failure.error.recoverability)
    }

    @Test
    fun `clock regression outcome is mapped without invoking transport`() = runTest {
        val delegate = RecordingTransportProvider()
        val provider = scripted(
            delegate = delegate,
            result = RetryTimeoutExecutionResult.ClockRegression(
                observedAt = DataLoomInstant(500L),
                deadline = DataLoomInstant(2_000L),
            ),
        )

        val failure = assertIs<ProviderOperationResult.Failure>(
            provider.pullChanges(pullRequest),
        )

        assertEquals(0, delegate.pullCalls)
        assertEquals("TRANSPORT_REQUEST_TIMEOUT_CLOCK_REGRESSION", failure.error.code.value)
        assertEquals(ErrorCategory.STATE, failure.error.category)
        assertEquals(Recoverability.NON_RECOVERABLE, failure.error.recoverability)
    }

    @Test
    fun `production request timeout assembly is side effect free`() {
        val clock = CountingClock()
        val delegate = RecordingTransportProvider()

        TransportRequestTimeoutRuntime.create(
            transportProvider = delegate,
            clock = clock,
            requestTimeout = SchedulingDelay(1_000L),
        )

        assertEquals(0, clock.calls)
        assertEquals(0, delegate.totalCalls)
    }

    private fun requestTimed(
        delegate: TransportProvider,
        timeoutMilliseconds: Long,
    ): TransportProvider = TransportRequestTimeoutRuntime.create(
        transportProvider = delegate,
        clock = FixedClock,
        requestTimeout = SchedulingDelay(timeoutMilliseconds),
    )

    private fun scripted(
        delegate: TransportProvider,
        result: RetryTimeoutExecutionResult<Nothing>,
    ): TransportProvider = RequestTimeoutEnforcingTransportProvider(
        delegate = delegate,
        timeoutCoordinator = RetryTimeoutCoordinator(
            configuration = RetryTimeoutConfiguration(
                requestTimeout = SchedulingDelay(1_000L),
            ),
            clock = FixedClock,
            executor = ScriptedTimeoutExecutor(result),
        ),
    )

    private class ScriptedTimeoutExecutor(
        private val result: RetryTimeoutExecutionResult<Nothing>,
    ) : RetryTimeoutExecutor {
        @Suppress("UNCHECKED_CAST")
        override suspend fun <T> execute(
            request: RetryTimeoutExecutionRequest,
            operation: suspend () -> T,
        ): RetryTimeoutExecutionResult<T> = result as RetryTimeoutExecutionResult<T>
    }

    private class RecordingTransportProvider(
        private val delayMilliseconds: Long = 0L,
        private val pushResult: ProviderOperationResult<ChangeSetAcknowledgement> =
            ProviderOperationResult.Failure(TestError()),
        private val pullResult: ProviderOperationResult<PullChangesResult> =
            ProviderOperationResult.Failure(TestError()),
    ) : TransportProvider {
        override val descriptor: ProviderDescriptor = ProviderDescriptor(
            id = ProviderId("transport-request-timeout-test"),
            name = ProviderName("Transport Request Timeout Test"),
            type = ProviderType.TRANSPORT,
            version = ProviderVersion("1.0.0"),
        )

        var initializeCalls: Int = 0
            private set
        var healthCalls: Int = 0
            private set
        var closeCalls: Int = 0
            private set
        var pushCalls: Int = 0
            private set
        var pullCalls: Int = 0
            private set
        var pushFinallyExecuted: Boolean = false
            private set
        val pushStarted: CompletableDeferred<Unit> = CompletableDeferred()

        val totalCalls: Int
            get() = initializeCalls + healthCalls + closeCalls + pushCalls + pullCalls

        override suspend fun initialize(
            context: ProviderInitializationContext,
        ): ProviderOperationResult<Unit> {
            initializeCalls++
            waitIfConfigured()
            return ProviderOperationResult.Success(Unit)
        }

        override suspend fun health(): ProviderOperationResult<ProviderHealth> {
            healthCalls++
            waitIfConfigured()
            return ProviderOperationResult.Success(ProviderHealth(ProviderHealthStatus.HEALTHY))
        }

        override suspend fun close(): ProviderOperationResult<Unit> {
            closeCalls++
            waitIfConfigured()
            return ProviderOperationResult.Success(Unit)
        }

        override suspend fun pushChanges(
            request: PushChangesRequest,
        ): ProviderOperationResult<ChangeSetAcknowledgement> {
            pushCalls++
            pushStarted.complete(Unit)
            return try {
                waitIfConfigured()
                pushResult
            } finally {
                pushFinallyExecuted = true
            }
        }

        override suspend fun pullChanges(
            request: PullChangesRequest,
        ): ProviderOperationResult<PullChangesResult> {
            pullCalls++
            waitIfConfigured()
            return pullResult
        }

        private suspend fun waitIfConfigured() {
            if (delayMilliseconds > 0L) delay(delayMilliseconds)
        }
    }

    private class CountingClock : DataLoomClock {
        var calls: Int = 0
            private set

        override fun now(): DataLoomInstant {
            calls++
            return DataLoomInstant(1_000L)
        }
    }

    private data class TestError(
        override val code: ErrorCode = ErrorCode("PROVIDER_TEST_FAILURE"),
        override val category: ErrorCategory = ErrorCategory.PROVIDER,
        override val severity: ErrorSeverity = ErrorSeverity.ERROR,
        override val recoverability: Recoverability = Recoverability.RECOVERABLE,
        override val message: String = "Provider test failure.",
        override val cause: Throwable? = null,
    ) : DataLoomError

    private suspend fun captureFailure(block: suspend () -> Unit): Throwable = try {
        block()
        error("Expected block to fail.")
    } catch (failure: Throwable) {
        failure
    }

    private object FixedClock : DataLoomClock {
        override fun now(): DataLoomInstant = DataLoomInstant(1_000L)
    }

    private companion object {
        val synchronizationRequest = SynchronizationRequest(
            workflowId = WorkflowId("transport-request-timeout-workflow"),
            sessionId = SynchronizationSessionId("transport-request-timeout-session"),
            direction = SynchronizationDirection.PUSH,
            mode = SynchronizationMode.DELTA,
            context = ExecutionContext(
                executionId = ExecutionId("transport-request-timeout-execution"),
                correlationId = CorrelationId("transport-request-timeout-correlation"),
            ),
        )
        val changeSet = ChangeSet(
            id = ChangeSetId("transport-request-timeout-change-set"),
            events = listOf(
                ChangeEvent(
                    id = ChangeEventId("transport-request-timeout-change"),
                    entity = EntityReference(
                        type = EntityType("document"),
                        id = EntityId("document-1"),
                    ),
                    operation = ChangeOperation.UPDATE,
                ),
            ),
        )
        val pushRequest = PushChangesRequest(
            request = synchronizationRequest,
            changeSet = changeSet,
        )
        val pullRequest = PullChangesRequest(
            request = synchronizationRequest,
        )
    }
}
