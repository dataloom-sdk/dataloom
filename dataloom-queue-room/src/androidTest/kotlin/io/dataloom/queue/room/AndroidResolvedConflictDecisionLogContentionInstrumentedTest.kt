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
 * [io.dataloom.api.conflict.DurableResolvedConflictDecisionLog.record] race,
 * extending the exact shape
 * [AndroidUnresolvedConflictLogContentionInstrumentedTest] already
 * establishes for `#95`'s conflict-engine sibling durable domain to this
 * gate's other durable conflict-engine domain -- the specific follow-up round
 * 28's PR #389 (that test's own scope notes) named as still open:
 * [io.dataloom.api.conflict.DurableResolvedConflictDecisionLog] "shares the
 * identical `DurableStateStore`/`RoomDurableStateStore` compare-and-set
 * persistence path (only the record type and codec differ ...) and is
 * expected to behave the same way but not separately proven here."
 *
 * [AndroidProcessTerminationResolvedConflictDecisionLogInstrumentedTest]
 * proves a persisted resolved-decision record survives a genuine process
 * kill/relaunch, but only ever runs one `:resolvedconflictproof` process at a
 * time, sequentially. This test instead drives two genuinely separate, real
 * Android OS processes (`:resolvedconflictca` and `:resolvedconflictcb`,
 * hosting [ResolvedConflictDecisionContentionContentProviderA] and
 * [ResolvedConflictDecisionContentionContentProviderB] respectively -- two
 * genuinely different classes sharing logic only through their common
 * [ResolvedConflictDecisionContentionContentProviderBase], because Android's
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
 *    production
 *    [io.dataloom.api.conflict.DurableResolvedConflictDecisionLog.record]
 *    path against a real [RoomDurableStateStore].
 * 3. Assert *exactly one* of the two calls observed
 *    [io.dataloom.api.conflict.DurableResolvedConflictDecisionRecordOutcome.Recorded]
 *    and the other observed
 *    [io.dataloom.api.conflict.DurableResolvedConflictDecisionRecordOutcome.AlreadyRecorded]
 *    -- never two `Recorded` (a lost-update corruption), never a
 *    `Conflict`/`PersistenceFailure`/`ContentionLimitReached`. Assert both
 *    calls came back with byte-identical persisted facts, proving they
 *    genuinely contended over the same row rather than one seeing unrelated
 *    state. Then read the row back from a brand-new connection and assert its
 *    `record_version` is exactly `0` -- one insert, zero compare-and-set
 *    overwrites reached it.
 *
 * This exercises the real, production
 * [io.dataloom.api.conflict.DurableResolvedConflictDecisionLog.record] ->
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
 * invariant is exactly what this proof exists to establish. It does not run
 * conflict detection/resolution through a composed `DataLoomBuilder` instance,
 * and it does not provide Apple-side process-kill/contention evidence
 * (blocked on the same infrastructure gap `#94` already documents).
 */
@RunWith(AndroidJUnit4::class)
class AndroidResolvedConflictDecisionLogContentionInstrumentedTest {

    @Test
    fun exactlyOneOfTwoRacingProcessesRecordsTheDecisionTheOtherSeesAlreadyRecorded() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "dataloom-resolved-conflict-contention-${UUID.randomUUID()}"
        context.deleteDatabase(databaseName)

        try {
            val warmedA = callProvider(
                context,
                ResolvedConflictDecisionContentionContract.AUTHORITY_A,
                ResolvedConflictDecisionContentionContract.METHOD_WARM_UP,
                databaseName,
            )
            val warmedB = callProvider(
                context,
                ResolvedConflictDecisionContentionContract.AUTHORITY_B,
                ResolvedConflictDecisionContentionContract.METHOD_WARM_UP,
                databaseName,
            )
            val pidA = warmedA.getInt(ResolvedConflictDecisionContentionContract.KEY_PID)
            val pidB = warmedB.getInt(ResolvedConflictDecisionContentionContract.KEY_PID)

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
                            ResolvedConflictDecisionContentionContract.AUTHORITY_A,
                            ResolvedConflictDecisionContentionContract.METHOD_RECORD_DECISION,
                            databaseName,
                        )
                    },
                )
                val futureB = executor.submit(
                    Callable {
                        barrier.await()
                        callProvider(
                            context,
                            ResolvedConflictDecisionContentionContract.AUTHORITY_B,
                            ResolvedConflictDecisionContentionContract.METHOD_RECORD_DECISION,
                            databaseName,
                        )
                    },
                )

                val resultA = futureA.get(30, TimeUnit.SECONDS)
                val resultB = futureB.get(30, TimeUnit.SECONDS)

                assertNotEquals(
                    resultA.getInt(ResolvedConflictDecisionContentionContract.KEY_PID),
                    resultB.getInt(ResolvedConflictDecisionContentionContract.KEY_PID),
                    "The two racing record calls must have been served by two genuinely separate " +
                        "Android OS processes.",
                )

                val outcomeA = resultA.getString(ResolvedConflictDecisionContentionContract.KEY_OUTCOME)
                val outcomeB = resultB.getString(ResolvedConflictDecisionContentionContract.KEY_OUTCOME)
                val outcomes = listOf(outcomeA, outcomeB)

                assertEquals(
                    1,
                    outcomes.count { it == "RECORDED" },
                    "Exactly one of the two racing processes must have newly recorded the decision. " +
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
                    ResolvedConflictDecisionContentionContract.AUTHORITY_A,
                    ResolvedConflictDecisionContentionContract.METHOD_READ_DECISION_STATE,
                    databaseName,
                )
                assertEquals(
                    "FOUND",
                    finalState.getString(ResolvedConflictDecisionContentionContract.KEY_STATUS),
                    "Exactly one durable-state row must be persisted after the race.",
                )
                assertEquals(
                    0L,
                    finalState.getLong(ResolvedConflictDecisionContentionContract.KEY_VERSION),
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
            expected.getString(ResolvedConflictDecisionContentionContract.KEY_CONFLICT_TYPE),
            actual.getString(ResolvedConflictDecisionContentionContract.KEY_CONFLICT_TYPE),
        )
        assertEquals(
            expected.getString(ResolvedConflictDecisionContentionContract.KEY_ENTITY_TYPE),
            actual.getString(ResolvedConflictDecisionContentionContract.KEY_ENTITY_TYPE),
        )
        assertEquals(
            expected.getString(ResolvedConflictDecisionContentionContract.KEY_ENTITY_ID),
            actual.getString(ResolvedConflictDecisionContentionContract.KEY_ENTITY_ID),
        )
        assertEquals(
            expected.getString(ResolvedConflictDecisionContentionContract.KEY_LOCAL_CHANGE_EVENT_ID),
            actual.getString(ResolvedConflictDecisionContentionContract.KEY_LOCAL_CHANGE_EVENT_ID),
        )
        assertEquals(
            expected.getString(ResolvedConflictDecisionContentionContract.KEY_REMOTE_CHANGE_EVENT_ID),
            actual.getString(ResolvedConflictDecisionContentionContract.KEY_REMOTE_CHANGE_EVENT_ID),
        )
        assertEquals(
            expected.getString(ResolvedConflictDecisionContentionContract.KEY_RESOLVER_ID),
            actual.getString(ResolvedConflictDecisionContentionContract.KEY_RESOLVER_ID),
        )
        assertEquals(
            expected.getString(ResolvedConflictDecisionContentionContract.KEY_DECISION_KIND),
            actual.getString(ResolvedConflictDecisionContentionContract.KEY_DECISION_KIND),
        )
        assertEquals(
            expected.getLong(ResolvedConflictDecisionContentionContract.KEY_COMMITTED_AT_MILLIS),
            actual.getLong(ResolvedConflictDecisionContentionContract.KEY_COMMITTED_AT_MILLIS),
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
