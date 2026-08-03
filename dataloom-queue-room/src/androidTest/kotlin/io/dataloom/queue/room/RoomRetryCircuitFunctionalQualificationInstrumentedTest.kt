package io.dataloom.queue.room

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
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
import io.dataloom.queue.room.internal.DataLoomRoomDatabase
import io.dataloom.runtime.retry.CircuitBreakerConfiguration
import io.dataloom.runtime.retry.CircuitBreakerCoordinator
import io.dataloom.runtime.retry.CircuitBreakerExecutionGate
import io.dataloom.runtime.retry.CircuitBreakerExecutionResult
import io.dataloom.runtime.retry.CircuitBreakerRecordResult
import io.dataloom.runtime.retry.CircuitBreakerRejectionReason
import io.dataloom.runtime.retry.CircuitProtectedOperationResult
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomRetryCircuitFunctionalQualificationInstrumentedTest {
    @Test
    fun openRejectionAndExclusiveRecoveryProbeSurviveRoomDatabaseReopen() =
        kotlinx.coroutines.test.runTest {
            val context = ApplicationProvider.getApplicationContext<android.content.Context>()
            val name = "dataloom-ac-func-004-circuit-test"
            context.deleteDatabase(name)
            val clock = MutableClock(1_000L)
            val failure = RecoverableNetworkError()
            var operationCalls = 0

            val firstDatabase = database(context, name)
            try {
                val firstGate = gate(clock, RoomCircuitBreakerStateStore(firstDatabase))
                val firstFailure = firstGate.execute<Unit>(scope) {
                    operationCalls += 1
                    CircuitProtectedOperationResult.Failure(failure)
                }
                val firstExecuted =
                    assertIs<CircuitBreakerExecutionResult.Executed<Unit>>(firstFailure)
                val firstRecord =
                    assertIs<CircuitBreakerRecordResult.Recorded>(firstExecuted.recordResult)
                assertEquals(CircuitBreakerPhase.CLOSED, firstRecord.record.state.phase)

                clock.nowMillis = 1_040L
                val secondFailure = firstGate.execute<Unit>(scope) {
                    operationCalls += 1
                    CircuitProtectedOperationResult.Failure(failure)
                }
                val secondExecuted =
                    assertIs<CircuitBreakerExecutionResult.Executed<Unit>>(secondFailure)
                val opened =
                    assertIs<CircuitBreakerRecordResult.Recorded>(secondExecuted.recordResult)
                assertEquals(CircuitBreakerPhase.OPEN, opened.record.state.phase)
                assertEquals(DataLoomInstant(2_040L), opened.record.state.openUntil)
            } finally {
                firstDatabase.close()
            }

            val restartedDatabase = database(context, name)
            val competingDatabase = database(context, name)
            val releaseProbe = CompletableDeferred<Unit>()
            try {
                val restartedGate = gate(
                    clock,
                    RoomCircuitBreakerStateStore(restartedDatabase),
                )
                clock.nowMillis = 1_115L
                val rejected = restartedGate.execute<Unit>(scope) {
                    operationCalls += 1
                    CircuitProtectedOperationResult.Success(Unit)
                }
                val openRejection = assertIs<CircuitBreakerExecutionResult.Rejected>(rejected)
                assertEquals(CircuitBreakerRejectionReason.OPEN, openRejection.reason)
                assertEquals(DataLoomInstant(2_040L), openRejection.retryAt)
                assertEquals(2, operationCalls)

                clock.nowMillis = 2_040L
                val probeStarted = CompletableDeferred<Unit>()
                val probe = async {
                    restartedGate.execute(scope) {
                        operationCalls += 1
                        probeStarted.complete(Unit)
                        releaseProbe.await()
                        CircuitProtectedOperationResult.Success(Unit)
                    }
                }
                probeStarted.await()

                val competingGate = gate(
                    clock,
                    RoomCircuitBreakerStateStore(competingDatabase),
                )
                val competingProbe = competingGate.execute<Unit>(scope) {
                    operationCalls += 1
                    CircuitProtectedOperationResult.Success(Unit)
                }
                val probeRejection =
                    assertIs<CircuitBreakerExecutionResult.Rejected>(competingProbe)
                assertEquals(CircuitBreakerRejectionReason.PROBE_IN_FLIGHT, probeRejection.reason)
                assertEquals(DataLoomInstant(2_540L), probeRejection.retryAt)
                assertEquals(3, operationCalls)

                releaseProbe.complete(Unit)
                val recovered =
                    assertIs<CircuitBreakerExecutionResult.Executed<Unit>>(probe.await())
                assertIs<CircuitProtectedOperationResult.Success<Unit>>(recovered.operationResult)
                val closed =
                    assertIs<CircuitBreakerRecordResult.Recorded>(recovered.recordResult)
                assertEquals(CircuitBreakerPhase.CLOSED, closed.record.state.phase)
                assertEquals(1L, closed.record.state.probeGeneration)
            } finally {
                releaseProbe.complete(Unit)
                restartedDatabase.close()
                competingDatabase.close()
            }

            val verificationDatabase = database(context, name)
            try {
                val persisted =
                    assertIs<ProviderOperationResult.Success<CircuitBreakerLoadResult>>(
                        RoomCircuitBreakerStateStore(verificationDatabase).load(scope),
                    )
                val found = assertIs<CircuitBreakerLoadResult.Found>(persisted.value)
                assertEquals(CircuitBreakerPhase.CLOSED, found.record.state.phase)
                assertEquals(1L, found.record.state.probeGeneration)
            } finally {
                verificationDatabase.close()
                context.deleteDatabase(name)
            }
        }

    private fun gate(
        clock: DataLoomClock,
        store: RoomCircuitBreakerStateStore,
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

    private fun database(
        context: android.content.Context,
        name: String,
    ): DataLoomRoomDatabase = Room.databaseBuilder(
        context,
        DataLoomRoomDatabase::class.java,
        name,
    ).addMigrations(*DataLoomRoomMigrations.ALL)
        .build()

    private class MutableClock(
        var nowMillis: Long,
    ) : DataLoomClock {
        override fun now(): DataLoomInstant = DataLoomInstant(nowMillis)
    }

    private data class RecoverableNetworkError(
        override val code: ErrorCode = ErrorCode("AC_FUNC_004_ROOM_NETWORK_FAILURE"),
        override val category: ErrorCategory = ErrorCategory.NETWORK,
        override val severity: ErrorSeverity = ErrorSeverity.ERROR,
        override val recoverability: Recoverability = Recoverability.RECOVERABLE,
        override val message: String = "Sanitized injected Room transport failure.",
        override val cause: Throwable? = null,
    ) : DataLoomError

    private companion object {
        val scope = CircuitBreakerScope.providerOperation(
            ProviderId("ac-func-004-room-transport"),
            RetryOperation("transport.initialize"),
        )
    }
}
