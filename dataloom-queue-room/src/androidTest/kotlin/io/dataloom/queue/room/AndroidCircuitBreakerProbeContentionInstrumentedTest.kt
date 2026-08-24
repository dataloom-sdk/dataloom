package io.dataloom.queue.room

import android.content.Context
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Proves genuine cross-process contention for the single half-open
 * circuit-breaker probe permit, closing the specific gap
 * [AndroidProcessTerminationCircuitBreakerInstrumentedTest]'s own class doc
 * names as still open: "it does not exercise cross-process *contention* for
 * the half-open probe lease."
 *
 * That test proves persisted circuit-breaker state survives a genuine
 * process kill/relaunch, but only ever runs one `:circuitproof` process at a
 * time -- open the circuit, kill it, relaunch it, read it back, all
 * sequential. This test instead drives two genuinely separate, real Android
 * OS processes (`:circuitprobea` and `:circuitprobeb`, hosting
 * [CircuitBreakerProbeContentionContentProviderA] and
 * [CircuitBreakerProbeContentionContentProviderB] respectively -- two
 * genuinely different classes sharing logic only through their common
 * [CircuitBreakerProbeContentionContentProviderBase], because Android's
 * `PackageManagerService` does not support the same class declared twice
 * under different authorities/processes at runtime, even though it compiles
 * and packages cleanly -- see
 * `src/androidTest/AndroidManifest.xml`) to race for the same circuit's
 * single half-open probe permit at the same real wall-clock moment:
 *
 * 1. Warm up process B (a harmless read-only call) so it is not cold-starting
 *    during the race, which would bias the outcome toward whichever process
 *    happened to be warm rather than testing genuine contention.
 * 2. Drive two real failures through process A's real
 *    `CircuitBreakerExecutionGate`, opening the circuit on the shared
 *    on-disk database. Assert process A's pid differs from this
 *    instrumentation process's own pid -- a genuinely separate process.
 * 3. Sleep past the circuit's open duration (real wall-clock time, since the
 *    two processes share no in-memory clock) so both processes will judge
 *    the circuit eligible to start a half-open probe.
 * 4. Fire one [android.content.ContentResolver.call] to process A and one to
 *    process B from two separate threads, released together by a
 *    [CyclicBarrier] so both Binder IPC calls launch as close to
 *    simultaneously as two real OS processes allow.
 * 5. Assert process A's and process B's pids are genuinely different OS
 *    processes, that *exactly one* of the two calls received
 *    `CircuitBreakerPermission.ProbeAllowed` (outcome "ALLOWED") and the
 *    other received `CircuitBreakerPermission.Rejected` with reason
 *    `PROBE_IN_FLIGHT` -- never both allowed, never both rejected -- and
 *    that the persisted state read back afterward shows exactly the winning
 *    generation, `HALF_OPEN`, with a probe in flight.
 *
 * This exercises the real, production
 * `CircuitBreakerCoordinator.acquire`/`RoomCircuitBreakerStateStore.compareAndSet`
 * path end to end: the mutual exclusion is enforced by a real
 * `UPDATE circuit_breaker_states ... WHERE record_version = :expectedVersion`
 * SQL statement that Android's SQLite driver serializes across the two
 * processes via its own file locking -- not a test-only mutex, `synchronized`
 * block, or single-process coroutine dispatcher.
 *
 * Boundary: two real OS processes issuing IPC calls from two JVM threads
 * released by a barrier is as close to true simultaneity as this
 * repository's tooling (no host-level orchestration, no `simctl`-style
 * external launcher) can drive two Android app processes. It does not
 * guarantee nanosecond-identical dispatch, but the assertions do not depend
 * on which process wins -- only that mutual exclusion holds regardless of
 * order, which is exactly the invariant this proof exists to establish.
 */
@RunWith(AndroidJUnit4::class)
class AndroidCircuitBreakerProbeContentionInstrumentedTest {

    @Test
    fun exactlyOneOfTwoRacingProcessesWinsTheHalfOpenProbePermit() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "dataloom-circuit-probe-contention-${UUID.randomUUID()}"
        context.deleteDatabase(databaseName)

        try {
            // Warm up process B first so neither racer wins purely because
            // the other was still being cold-started by the OS.
            val warmedB = callProvider(
                context,
                CircuitBreakerProbeContentionContract.AUTHORITY_B,
                CircuitBreakerProbeContentionContract.METHOD_WARM_UP,
                databaseName,
            )
            val pidB = warmedB.getInt(CircuitBreakerProbeContentionContract.KEY_PID)

            val opened = callProvider(
                context,
                CircuitBreakerProbeContentionContract.AUTHORITY_A,
                CircuitBreakerProbeContentionContract.METHOD_OPEN_CIRCUIT,
                databaseName,
            )
            val pidA = opened.getInt(CircuitBreakerProbeContentionContract.KEY_PID)

            assertNotEquals(
                android.os.Process.myPid(),
                pidA,
                "The probe-contention provider must run in a separate process from this " +
                    "instrumentation test's own process.",
            )
            assertNotEquals(
                pidA,
                pidB,
                "The two racing provider instances must run in two genuinely separate " +
                    "Android OS processes, not the same one.",
            )

            // Real wall-clock sleep: both processes judge probe eligibility
            // against real system time, and openDuration is 400ms.
            Thread.sleep(700L)

            val barrier = CyclicBarrier(2)
            val executor = Executors.newFixedThreadPool(2)
            try {
                val futureA = executor.submit(
                    Callable {
                        barrier.await()
                        callProvider(
                            context,
                            CircuitBreakerProbeContentionContract.AUTHORITY_A,
                            CircuitBreakerProbeContentionContract.METHOD_ATTEMPT_PROBE,
                            databaseName,
                        )
                    },
                )
                val futureB = executor.submit(
                    Callable {
                        barrier.await()
                        callProvider(
                            context,
                            CircuitBreakerProbeContentionContract.AUTHORITY_B,
                            CircuitBreakerProbeContentionContract.METHOD_ATTEMPT_PROBE,
                            databaseName,
                        )
                    },
                )

                val resultA = futureA.get(20, TimeUnit.SECONDS)
                val resultB = futureB.get(20, TimeUnit.SECONDS)

                assertNotEquals(
                    resultA.getInt(CircuitBreakerProbeContentionContract.KEY_PID),
                    resultB.getInt(CircuitBreakerProbeContentionContract.KEY_PID),
                    "The two racing attemptProbe calls must have been served by two " +
                        "genuinely separate Android OS processes.",
                )

                val outcomeA = resultA.getString(CircuitBreakerProbeContentionContract.KEY_OUTCOME)
                val outcomeB = resultB.getString(CircuitBreakerProbeContentionContract.KEY_OUTCOME)
                val outcomes = listOf(outcomeA, outcomeB)

                assertEquals(
                    1,
                    outcomes.count { it == "ALLOWED" },
                    "Exactly one of the two racing processes must win the single half-open " +
                        "probe permit. Outcomes were: A=$outcomeA, B=$outcomeB.",
                )
                assertEquals(
                    1,
                    outcomes.count { it == "REJECTED" },
                    "The losing racing process must be genuinely rejected, not allowed " +
                        "through and not persistence-failed. Outcomes were: " +
                        "A=$outcomeA, B=$outcomeB.",
                )

                val winner = if (outcomeA == "ALLOWED") resultA else resultB
                val loser = if (outcomeA == "ALLOWED") resultB else resultA

                assertEquals(
                    "PROBE_IN_FLIGHT",
                    loser.getString(CircuitBreakerProbeContentionContract.KEY_REJECTION_REASON),
                    "The losing process must be rejected specifically because a probe was " +
                        "already in flight for the winner's generation, proving the two " +
                        "processes genuinely contended for the same permit rather than one " +
                        "seeing a stale/unrelated circuit state.",
                )

                val winningGeneration = winner.getLong(CircuitBreakerProbeContentionContract.KEY_GENERATION)
                assertTrue(
                    winningGeneration > 0L,
                    "The winning process must have been granted a real probe generation.",
                )

                val finalState = callProvider(
                    context,
                    CircuitBreakerProbeContentionContract.AUTHORITY_A,
                    CircuitBreakerProbeContentionContract.METHOD_READ_CIRCUIT_STATE,
                    databaseName,
                )
                assertEquals(
                    "HALF_OPEN",
                    finalState.getString(CircuitBreakerProbeContentionContract.KEY_PHASE),
                    "The persisted state after the race must reflect exactly one probe " +
                        "having started.",
                )
                assertEquals(
                    winningGeneration,
                    finalState.getLong(CircuitBreakerProbeContentionContract.KEY_PROBE_GENERATION),
                    "The persisted probe generation after the race must match the winner's " +
                        "granted generation, not the loser's rejected attempt.",
                )
                assertTrue(
                    finalState.getBoolean(CircuitBreakerProbeContentionContract.KEY_PROBE_IN_FLIGHT),
                    "The persisted state must show the winner's probe still in flight.",
                )
            } finally {
                executor.shutdown()
            }
        } finally {
            context.deleteDatabase(databaseName)
        }
    }

    private fun callProvider(
        context: Context,
        authority: String,
        method: String,
        databaseName: String,
    ): Bundle {
        return requireNotNull(
            context.contentResolver.call(authority, method, databaseName, null),
        ) { "ContentProvider call '$method' to '$authority' returned no result." }
    }
}
