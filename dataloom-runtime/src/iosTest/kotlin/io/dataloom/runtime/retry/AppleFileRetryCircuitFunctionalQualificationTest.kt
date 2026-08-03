@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.dataloom.runtime.retry

import io.dataloom.api.circuit.CircuitBreakerLoadResult
import io.dataloom.api.circuit.CircuitBreakerPhase
import io.dataloom.api.circuit.CircuitBreakerScope
import io.dataloom.api.error.DataLoomError
import io.dataloom.api.error.ErrorCategory
import io.dataloom.api.error.ErrorCode
import io.dataloom.api.error.ErrorSeverity
import io.dataloom.api.error.Recoverability
import io.dataloom.api.provider.ProviderId
import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.api.retry.RetryOperation
import io.dataloom.api.scheduling.SchedulingDelay
import io.dataloom.api.time.DataLoomClock
import io.dataloom.api.time.DataLoomInstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID

class AppleFileRetryCircuitFunctionalQualificationTest {
    @Test
    fun `open rejection and exclusive recovery probe survive Apple store recreation`() = runTest {
        val directory = uniqueDirectory()
        val clock = MutableClock(1_000L)
        val failure = RecoverableNetworkError()
        var operationCalls = 0
        val firstGate = gate(clock, AppleFileCircuitBreakerStateStore(directory))

        val firstFailure = firstGate.execute<Unit>(scope) {
            operationCalls += 1
            CircuitProtectedOperationResult.Failure(failure)
        }
        val firstExecuted = assertIs<CircuitBreakerExecutionResult.Executed<Unit>>(firstFailure)
        val firstRecord = assertIs<CircuitBreakerRecordResult.Recorded>(firstExecuted.recordResult)
        assertEquals(CircuitBreakerPhase.CLOSED, firstRecord.record.state.phase)

        clock.nowMillis = 1_040L
        val secondFailure = firstGate.execute<Unit>(scope) {
            operationCalls += 1
            CircuitProtectedOperationResult.Failure(failure)
        }
        val secondExecuted = assertIs<CircuitBreakerExecutionResult.Executed<Unit>>(secondFailure)
        val opened = assertIs<CircuitBreakerRecordResult.Recorded>(secondExecuted.recordResult)
        assertEquals(CircuitBreakerPhase.OPEN, opened.record.state.phase)
        assertEquals(DataLoomInstant(2_040L), opened.record.state.openUntil)

        // Recreate both the store and coordinator before the scheduled retry.
        val restartedGate = gate(clock, AppleFileCircuitBreakerStateStore(directory))
        clock.nowMillis = 1_115L
        val rejected = restartedGate.execute<Unit>(scope) {
            operationCalls += 1
            CircuitProtectedOperationResult.Success(Unit)
        }
        val openRejection = assertIs<CircuitBreakerExecutionResult.Rejected>(rejected)
        assertEquals(CircuitBreakerRejectionReason.OPEN, openRejection.reason)
        assertEquals(DataLoomInstant(2_040L), openRejection.retryAt)
        assertEquals(2, operationCalls)

        // At the exact deadline one file-backed store owns the persisted probe lease.
        clock.nowMillis = 2_040L
        val probeStarted = CompletableDeferred<Unit>()
        val releaseProbe = CompletableDeferred<Unit>()
        val probe = async {
            restartedGate.execute(scope) {
                operationCalls += 1
                probeStarted.complete(Unit)
                releaseProbe.await()
                CircuitProtectedOperationResult.Success(Unit)
            }
        }
        probeStarted.await()

        val competingGate = gate(clock, AppleFileCircuitBreakerStateStore(directory))
        val competingProbe = competingGate.execute<Unit>(scope) {
            operationCalls += 1
            CircuitProtectedOperationResult.Success(Unit)
        }
        val probeRejection = assertIs<CircuitBreakerExecutionResult.Rejected>(competingProbe)
        assertEquals(CircuitBreakerRejectionReason.PROBE_IN_FLIGHT, probeRejection.reason)
        assertEquals(DataLoomInstant(2_540L), probeRejection.retryAt)
        assertEquals(3, operationCalls)

        releaseProbe.complete(Unit)
        val recovered = assertIs<CircuitBreakerExecutionResult.Executed<Unit>>(probe.await())
        assertIs<CircuitProtectedOperationResult.Success<Unit>>(recovered.operationResult)
        val closed = assertIs<CircuitBreakerRecordResult.Recorded>(recovered.recordResult)
        assertEquals(CircuitBreakerPhase.CLOSED, closed.record.state.phase)
        assertEquals(1L, closed.record.state.probeGeneration)

        val persisted = assertIs<ProviderOperationResult.Success<CircuitBreakerLoadResult>>(
            AppleFileCircuitBreakerStateStore(directory).load(scope),
        )
        val found = assertIs<CircuitBreakerLoadResult.Found>(persisted.value)
        assertEquals(closed.record, found.record)
    }

    private fun gate(
        clock: DataLoomClock,
        store: AppleFileCircuitBreakerStateStore,
    ): CircuitBreakerExecutionGate = CircuitBreakerExecutionGate(
        CircuitBreakerCoordinator(
            configuration = CircuitBreakerConfiguration(
                failureThreshold = 2,
                failureWindow = SchedulingDelay(5_000L),
                openDuration = SchedulingDelay(1_000L),
                halfOpenProbeLeaseDuration = SchedulingDelay(500L),
            ),
            clock = clock,
            stateStore = store,
        ),
    )

    private class MutableClock(
        var nowMillis: Long,
    ) : DataLoomClock {
        override fun now(): DataLoomInstant = DataLoomInstant(nowMillis)
    }

    private data class RecoverableNetworkError(
        override val code: ErrorCode = ErrorCode("AC_FUNC_004_APPLE_NETWORK_FAILURE"),
        override val category: ErrorCategory = ErrorCategory.NETWORK,
        override val severity: ErrorSeverity = ErrorSeverity.ERROR,
        override val recoverability: Recoverability = Recoverability.RECOVERABLE,
        override val message: String = "Sanitized injected Apple transport failure.",
        override val cause: Throwable? = null,
    ) : DataLoomError

    private companion object {
        val scope = CircuitBreakerScope.providerOperation(
            ProviderId("ac-func-004-apple-transport"),
            RetryOperation("transport.initialize"),
        )
    }

    private fun uniqueDirectory(): String = buildString {
        append(NSTemporaryDirectory().trimEnd('/'))
        append("/dataloom-apple-ac-func-004-")
        append(NSUUID().UUIDString)
    }
}
