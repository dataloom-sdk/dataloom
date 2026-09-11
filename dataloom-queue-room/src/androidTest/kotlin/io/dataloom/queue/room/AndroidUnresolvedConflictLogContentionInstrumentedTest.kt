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
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Proves genuine cross-process contention for a concurrent
 * [io.dataloom.api.conflict.DurableUnresolvedConflictLog.record] race,
 * extending the exact shape
 * [AndroidCircuitBreakerProbeContentionInstrumentedTest] already establishes
 * for the circuit-breaker domain (two genuinely independent OS processes
 * racing, exactly one winning, enforced by the store's own compare-and-set)
 * to `#95`'s conflict-engine durable-state domain -- the specific follow-up
 * both [AndroidProcessTerminationConflictLogInstrumentedTest]'s and
 * [AndroidProcessTerminationResolvedConflictDecisionLogInstrumentedTest]'s
 * own class docs name as still open: "cross-process *contention* for a
 * concurrent `record` race ... remains unexercised for conflicts".
 *
 * Those tests prove a persisted conflict record survives a genuine process
 * kill/relaunch, but only ever run one `:conflictproof` process at a time,
 * sequentially. This test instead drives two genuinely separate, real Android
 * OS processes (`:unresolvedconflictca` and `:unresolvedconflictcb`, hosting
 * [UnresolvedConflictContentionContentProviderA] and
 * [UnresolvedConflictContentionContentProviderB] respectively -- two
 * genuinely different classes sharing logic only through their common
 * [UnresolvedConflictContentionContentProviderBase], because Android's
 * `PackageManagerService` does not support the same class declared twice
 * under different authorities/processes at runtime even though it compiles
 * and packages cleanly -- see
 * [CircuitBreakerProbeContentionContentProviderBase]'s class doc and
 * `src/androidTest/AndroidManifest.xml`) to race to record the **same**
 * [io.dataloom.api.identifier.ConflictId] against the same on-disk database
 * at the same real wall-clock moment:
 *
 * 1. Warm up both processes (a harmless read-only `load`) so neither racer
 *    wins purely because the other was still cold-starting during the race.
 *    Assert each provider's pid differs from this instrumentation process's
 *    own pid and from the other provider's -- three genuinely separate OS
 *    processes.
 * 2. Fire one [android.content.ContentResolver.call] to process A and one to
 *    process B from two separate threads, released together by a
 *    [CyclicBarrier] so both Binder IPC calls launch as close to
 *    simultaneously as two real OS processes allow. Each call runs the real
 *    production [io.dataloom.api.conflict.DurableUnresolvedConflictLog.record]
 *    path against a real [RoomDurableStateStore].
 * 3. Assert *exactly one* of the two calls observed
 *    [io.dataloom.api.conflict.DurableUnresolvedConflictRecordOutcome.Recorded]
 *    and the other observed
 *    [io.dataloom.api.conflict.DurableUnresolvedConflictRecordOutcome.AlreadyRecorded]
 *    -- never two `Recorded` (a lost-update corruption), never a
 *    `Conflict`/`PersistenceFailure`/`ContentionLimitReached`. Assert both
 *    calls came back with byte-identical persisted facts, proving they
 *    genuinely contended over the same row rather than one seeing unrelated
 *    state. Then read the row back from a brand-new connection and assert its
 *    `record_version` is exactly `0` -- one insert, zero compare-and-set
 *    overwrites reached it.
 *
 * This exercises the real, production
 * [io.dataloom.api.conflict.DurableUnresolvedConflictLog.record] ->
 * [RoomDurableStateStore.compareAndSet] -> `DurableStateDao.compareAndSet`
 * path end to end: the mutual exclusion is enforced by a real
 * `@Insert(onConflict = IGNORE)` against the `durable_states` composite
 * primary key that Android's SQLite driver serializes across the two
 * processes via its own file locking -- not a test-only mutex,
 * `synchronized` block, or single-process coroutine dispatcher. The losing
 * racer's ignored insert makes its `record` call observe
 * [io.dataloom.api.state.DurableStateCompareAndSetResult.Conflict], loop per
 * its own documented bounded retry, reload, see the winner's row, find the
 * facts agree, and return `AlreadyRecorded`.
 *
 * Boundary: two real OS processes issuing IPC calls from two JVM threads
 * released by a barrier is as close to true simultaneity as this
 * repository's tooling can drive two Android app processes. It does not
 * guarantee nanosecond-identical dispatch -- if process A fully commits its
 * insert before process B's `record` even calls `load`, B observes `Found`
 * immediately and returns `AlreadyRecorded` without ever attempting a losing
 * compare-and-set. The assertions deliberately do not depend on a
 * compare-and-set collision actually occurring, only on the commit-once
 * invariant holding regardless of interleaving: exactly one `Recorded`, one
 * `AlreadyRecorded`, identical persisted facts, `record_version == 0`. That
 * invariant is exactly what this proof exists to establish.
 */
@RunWith(AndroidJUnit4::class)
class AndroidUnresolvedConflictLogContentionInstrumentedTest {

    @Test
    fun exactlyOneOfTwoRacingProcessesRecordsTheConflictTheOtherSeesAlreadyRecorded() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "dataloom-unresolved-conflict-contention-${UUID.randomUUID()}"
        context.deleteDatabase(databaseName)

        try {
            val warmedA = callProvider(
                context,
                UnresolvedConflictContentionContract.AUTHORITY_A,
                UnresolvedConflictContentionContract.METHOD_WARM_UP,
                databaseName,
            )
            val warmedB = callProvider(
                context,
                UnresolvedConflictContentionContract.AUTHORITY_B,
                UnresolvedConflictContentionContract.METHOD_WARM_UP,
                databaseName,
            )
            val pidA = warmedA.getInt(UnresolvedConflictContentionContract.KEY_PID)
            val pidB = warmedB.getInt(UnresolvedConflictContentionContract.KEY_PID)

            assertNotEquals(
                android.os.Process.myPid(),
                pidA,
                "Racer A's provider must run in a separate process from this instrumentation " +
                    "test's own process.",
            )
            assertNotEquals(
                android.os.Process.myPid(),
                pidB,
                "Racer B's provider must run in a separate process from this instrumentation " +
                    "test's own process.",
            )
            assertNotEquals(
                pidA,
                pidB,
                "The two racing provider instances must run in two genuinely separate Android OS " +
                    "processes, not the same one.",
            )

            val barrier = CyclicBarrier(2)
            val executor = Executors.newFixedThreadPool(2)
            try {
                val futureA = executor.submit(
                    Callable {
                        barrier.await()
                        callProvider(
                            context,
                            UnresolvedConflictContentionContract.AUTHORITY_A,
                            UnresolvedConflictContentionContract.METHOD_RECORD_CONFLICT,
                            databaseName,
                        )
                    },
                )
                val futureB = executor.submit(
                    Callable {
                        barrier.await()
                        callProvider(
                            context,
                            UnresolvedConflictContentionContract.AUTHORITY_B,
                            UnresolvedConflictContentionContract.METHOD_RECORD_CONFLICT,
                            databaseName,
                        )
                    },
                )

                val resultA = futureA.get(30, TimeUnit.SECONDS)
                val resultB = futureB.get(30, TimeUnit.SECONDS)

                assertNotEquals(
                    resultA.getInt(UnresolvedConflictContentionContract.KEY_PID),
                    resultB.getInt(UnresolvedConflictContentionContract.KEY_PID),
                    "The two racing record calls must have been served by two genuinely separate " +
                        "Android OS processes.",
                )

                val outcomeA = resultA.getString(UnresolvedConflictContentionContract.KEY_OUTCOME)
                val outcomeB = resultB.getString(UnresolvedConflictContentionContract.KEY_OUTCOME)
                val outcomes = listOf(outcomeA, outcomeB)

                assertEquals(
                    1,
                    outcomes.count { it == "RECORDED" },
                    "Exactly one of the two racing processes must have newly recorded the conflict. " +
                        "Outcomes were: A=$outcomeA, B=$outcomeB.",
                )
                assertEquals(
                    1,
                    outcomes.count { it == "ALREADY_RECORDED" },
                    "The losing racing process must have observed the winner's commit-once record " +
                        "as already recorded -- not recorded a second copy, not failed. Outcomes " +
                        "were: A=$outcomeA, B=$outcomeB.",
                )

                assertRecordFactsEqual(resultA, resultB)

                val finalState = callProvider(
                    context,
                    UnresolvedConflictContentionContract.AUTHORITY_A,
                    UnresolvedConflictContentionContract.METHOD_READ_CONFLICT_STATE,
                    databaseName,
                )
                assertEquals(
                    "FOUND",
                    finalState.getString(UnresolvedConflictContentionContract.KEY_STATUS),
                    "Exactly one durable-state row must be persisted after the race.",
                )
                assertEquals(
                    0L,
                    finalState.getLong(UnresolvedConflictContentionContract.KEY_VERSION),
                    "The persisted record_version must be 0: exactly one insert and zero " +
                        "compare-and-set overwrites reached the row.",
                )
                assertRecordFactsEqual(resultA, finalState)
            } finally {
                executor.shutdown()
            }
        } finally {
            context.deleteDatabase(databaseName)
        }
    }

    private fun assertRecordFactsEqual(expected: Bundle, actual: Bundle) {
        assertEquals(
            expected.getString(UnresolvedConflictContentionContract.KEY_CONFLICT_TYPE),
            actual.getString(UnresolvedConflictContentionContract.KEY_CONFLICT_TYPE),
        )
        assertEquals(
            expected.getString(UnresolvedConflictContentionContract.KEY_ENTITY_TYPE),
            actual.getString(UnresolvedConflictContentionContract.KEY_ENTITY_TYPE),
        )
        assertEquals(
            expected.getString(UnresolvedConflictContentionContract.KEY_ENTITY_ID),
            actual.getString(UnresolvedConflictContentionContract.KEY_ENTITY_ID),
        )
        assertEquals(
            expected.getString(UnresolvedConflictContentionContract.KEY_LOCAL_CHANGE_EVENT_ID),
            actual.getString(UnresolvedConflictContentionContract.KEY_LOCAL_CHANGE_EVENT_ID),
        )
        assertEquals(
            expected.getString(UnresolvedConflictContentionContract.KEY_REMOTE_CHANGE_EVENT_ID),
            actual.getString(UnresolvedConflictContentionContract.KEY_REMOTE_CHANGE_EVENT_ID),
        )
        assertEquals(
            expected.getString(UnresolvedConflictContentionContract.KEY_REASON),
            actual.getString(UnresolvedConflictContentionContract.KEY_REASON),
        )
        assertEquals(
            expected.getLong(UnresolvedConflictContentionContract.KEY_COMMITTED_AT_MILLIS),
            actual.getLong(UnresolvedConflictContentionContract.KEY_COMMITTED_AT_MILLIS),
        )
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
