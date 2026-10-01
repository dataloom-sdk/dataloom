package io.dataloom.consumer.android

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.dataloom.api.circuit.CircuitBreakerScope
import io.dataloom.api.error.DataLoomError
import io.dataloom.api.error.ErrorCategory
import io.dataloom.api.error.ErrorCode
import io.dataloom.api.error.ErrorSeverity
import io.dataloom.api.error.Recoverability
import io.dataloom.api.provider.ProviderId
import io.dataloom.api.scheduling.SchedulingDelay
import io.dataloom.api.time.DataLoomClock
import io.dataloom.api.time.DataLoomInstant
import io.dataloom.queue.room.DataLoomDatabaseBuilder
import io.dataloom.queue.room.RoomCircuitBreakerStateStore
import io.dataloom.runtime.retry.CircuitBreakerConfiguration
import io.dataloom.runtime.retry.CircuitBreakerCoordinator
import io.dataloom.runtime.retry.CircuitBreakerExecutionGate
import io.dataloom.runtime.retry.CircuitBreakerPermission
import io.dataloom.runtime.retry.CircuitBreakerProbePermit
import io.dataloom.runtime.retry.CircuitBreakerRecordResult
import io.dataloom.runtime.retry.CircuitBreakerRejectionReason
import io.dataloom.runtime.retry.CircuitProtectedOperationResult
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Robolectric-backed, actually-executable-on-this-host proof that the real
 * `CircuitBreakerCoordinator.acquire`/`recordSuccess` gate -- not just
 * [RoomCircuitBreakerStateStore.load] -- still makes the correct
 * ALLOWED/REJECTED/PROBE_ALLOWED decision when re-driven against a *fresh*
 * connection to the same on-disk database, mirroring the shape
 * `AndroidProcessTerminationCircuitBreakerInstrumentedTest`'s own
 * `androidTest` re-drive (see its class KDoc) exercises via a genuine
 * Android OS process kill/relaunch.
 *
 * ## Why this exists alongside the instrumented test, not instead of it
 *
 * `AndroidProcessTerminationCircuitBreakerInstrumentedTest` is the actual
 * `#94` acceptance proof: it kills a real second `:circuitproof` OS process
 * with `ActivityManager.killBackgroundProcesses` and relaunches it, which
 * this Windows host cannot execute without a real Gradle Managed Device (see
 * that test's own PR for the honest compile-only-verified boundary). This
 * test cannot substitute for that -- a fresh Room connection inside the same
 * JVM process proves the gate logic is correctly re-derived from a cold
 * on-disk read, but it does not exercise genuine OS process death, a
 * different `pid`, or cross-process SQLite file locking.
 *
 * What it *does* prove, and what actually ran green on this host via
 * `./gradlew :runtime-android-reference-consumer:test`: opening a brand-new
 * [RoomCircuitBreakerStateStore] connection to the same on-disk database file
 * that a now-discarded first connection wrote to, then calling the real
 * production [CircuitBreakerCoordinator.acquire]/[CircuitBreakerCoordinator.recordSuccess]
 * against it, yields the exact three decisions the instrumented test also
 * asserts after a genuine relaunch: reject-before-deadline, probe-granted-
 * exactly-once-at-deadline, and recovery-to-ALLOWED after a recorded probe
 * success. This also answers, for this module's sibling `dataloom-queue-room`
 * store, the open question `AndroidProcessTerminationCircuitBreakerInstrumentedTest`
 * flagged from its own KDoc history: Robolectric can open and correctly read
 * back `circuit_breaker_states` rows across independent connections, at
 * least at this module's short class/database-name lengths -- see the
 * `@Test` method's own comment on Windows `MAX_PATH`, the same boundary
 * [ComposedQueueCircuitRobolectricTest] already documents.
 */
@RunWith(RobolectricTestRunner::class)
class CircuitGateReconnectRobolectricTest {

    // Short class/method/database names are deliberate -- see
    // ComposedQueueCircuitRobolectricTest's identical note: Robolectric's
    // per-test temp directory embeds the test class and method name, and
    // long combinations hit a Windows MAX_PATH SQLite-open failure that is a
    // local path-length artifact, not a product bug.
    @Test
    fun realGateRedrivenAfterFreshConnectionRejectsProbesAndRecovers() = runTest {
        val context: Context = ApplicationProvider.getApplicationContext()
        val databaseName = "cg-${UUID.randomUUID().toString().take(8)}.db"
        val scope = CircuitBreakerScope.provider(ProviderId("cg-reconnect-proof"))
        val configuration = CircuitBreakerConfiguration(
            failureThreshold = 2,
            failureWindow = SchedulingDelay(5_000L),
            openDuration = SchedulingDelay(1_000L),
            halfOpenProbeLeaseDuration = SchedulingDelay(500L),
        )

        try {
            // "Process 1": open the circuit, then discard this connection --
            // the closest a single JVM can get to simulating a relaunch
            // reading a cold on-disk file, short of an actual OS process kill.
            val firstDatabase = DataLoomDatabaseBuilder.build(context, databaseName)
            val clock = MutableClock(FIRST_FAILURE_AT_MS)
            val gate = CircuitBreakerExecutionGate(
                CircuitBreakerCoordinator(configuration, clock, RoomCircuitBreakerStateStore(firstDatabase)),
            )
            val failure = InjectedFailure()
            gate.execute<Unit>(scope) { CircuitProtectedOperationResult.Failure(failure) }
            clock.nowMillis = SECOND_FAILURE_AT_MS
            gate.execute<Unit>(scope) { CircuitProtectedOperationResult.Failure(failure) }
            firstDatabase.close()

            // "Process 2": a fresh connection to the same on-disk file, never
            // sharing the first connection's in-memory Room instance.
            val secondDatabase = DataLoomDatabaseBuilder.build(context, databaseName)
            try {
                val store = RoomCircuitBreakerStateStore(secondDatabase)

                // Reject-before-deadline: one millisecond before the
                // persisted open-deadline (SECOND_FAILURE_AT_MS + openDuration
                // = 2_040L), the real gate must still reject.
                val beforeDeadline = CircuitBreakerCoordinator(
                    configuration,
                    MutableClock(OPEN_UNTIL_MS - 1L),
                    store,
                ).acquire(scope)
                val rejected = assertIs<CircuitBreakerPermission.Rejected>(beforeDeadline)
                assertEquals(CircuitBreakerRejectionReason.OPEN, rejected.reason)

                // Probe-granted-at-deadline: exactly at the deadline, the
                // real gate must grant exactly one half-open probe.
                val atDeadline = CircuitBreakerCoordinator(
                    configuration,
                    MutableClock(OPEN_UNTIL_MS),
                    store,
                ).acquire(scope)
                val probeAllowed = assertIs<CircuitBreakerPermission.ProbeAllowed>(atDeadline)
                assertTrue(probeAllowed.permit.generation > 0L)

                // Recovery-after-success: recording a real success for that
                // exact probe permit, then re-verifying, must return the
                // real gate to normal ALLOWED state.
                val recordingCoordinator = CircuitBreakerCoordinator(
                    configuration,
                    MutableClock(OPEN_UNTIL_MS + 10L),
                    store,
                )
                val recorded = recordingCoordinator.recordSuccess(
                    scope,
                    CircuitBreakerProbePermit(scope, probeAllowed.permit.generation),
                )
                assertIs<CircuitBreakerRecordResult.Recorded>(recorded)

                val reverified = CircuitBreakerCoordinator(
                    configuration,
                    MutableClock(OPEN_UNTIL_MS + 20L),
                    store,
                ).acquire(scope)
                assertEquals(CircuitBreakerPermission.Allowed, reverified)
            } finally {
                secondDatabase.close()
            }
        } finally {
            context.deleteDatabase(databaseName)
        }
    }

    private class MutableClock(var nowMillis: Long) : DataLoomClock {
        override fun now(): DataLoomInstant = DataLoomInstant(nowMillis)
    }

    private data class InjectedFailure(
        override val code: ErrorCode = ErrorCode("CIRCUIT_GATE_RECONNECT_INJECTED_FAILURE"),
        override val category: ErrorCategory = ErrorCategory.NETWORK,
        override val severity: ErrorSeverity = ErrorSeverity.ERROR,
        override val recoverability: Recoverability = Recoverability.RECOVERABLE,
        override val message: String = "Sanitized injected failure for the circuit-gate reconnect proof.",
        override val cause: Throwable? = null,
    ) : DataLoomError

    private companion object {
        const val FIRST_FAILURE_AT_MS: Long = 1_000L
        const val SECOND_FAILURE_AT_MS: Long = 1_040L
        const val OPEN_UNTIL_MS: Long = SECOND_FAILURE_AT_MS + 1_000L
    }
}
