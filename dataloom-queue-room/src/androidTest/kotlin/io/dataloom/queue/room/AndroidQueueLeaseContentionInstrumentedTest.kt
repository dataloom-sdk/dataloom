package io.dataloom.queue.room

import android.content.Context
import android.os.Bundle
import android.util.Log
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
 * Proves genuine cross-process contention for a single durable queue entry's
 * lease, the retry-budget lease: the Android counterpart of the Apple
 * Simulator cross-process retry-budget contention proof, and the queue-lease
 * sibling of [AndroidCircuitBreakerProbeContentionInstrumentedTest].
 *
 * Two real Android OS processes (`:queueleasea` / `:queueleaseb`, hosting
 * [QueueLeaseContentionContentProviderA] / [QueueLeaseContentionContentProviderB])
 * each hold their own [RoomQueueProvider] connection to the same on-disk
 * database. For each of [ROUNDS] rounds, with exactly one eligible entry (a
 * RETRY_WAITING entry carrying real retry-budget state):
 *
 * 1. Process A seeds the entry (enqueue -> acquire -> reschedule).
 * 2. Both processes call the real [RoomQueueProvider.acquire] for that one
 *    entry, each with its own lease id, released together on a shared
 *    wall-clock start instant (a Binder call carries the instant; each process
 *    busy-waits to it), then asserts exactly one acquire returned the entry
 *    (with its retry attempt and budget intact) and the other returned
 *    `NoEntries`, never two leases, never a database failure.
 * 3. The loser presents its own (never-granted) lease id to `complete` and is
 *    rejected with `QUEUE_STALE_LEASE`, the lease guard working across
 *    processes.
 * 4. The winner reschedules under its lease with an advanced retry attempt
 *    and budget; the loser's process then re-acquires the entry and observes
 *    exactly that winner-written retry state (a cross-process read of the
 *    winner's write), and completes it.
 *
 * After all rounds a final acquire in either process returns `NoEntries`.
 *
 * Mutual exclusion is enforced by the real production path: one `@Transaction`
 * DAO method (select eligible, then `UPDATE ... WHERE state IN
 * ('PENDING','RETRY_WAITING')`) serialized across processes by SQLite's own
 * file locking, not by a test-only mutex.
 *
 * Boundary: the proof does not depend on which process wins, only that exactly
 * one does. Both processes are released on one shared wall-clock instant, but
 * simultaneity is still bounded by OS scheduling; the per-round call-interval
 * overlap is logged (tag [TAG]) and summarised in the assertion messages so
 * the strength of each run is visible.
 */
@RunWith(AndroidJUnit4::class)
class AndroidQueueLeaseContentionInstrumentedTest {

    @Test
    fun exactlyOneOfTwoRacingProcessesWinsTheQueueEntryLease() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "dataloom-queue-lease-contention-${UUID.randomUUID()}"
        context.deleteDatabase(databaseName)

        val authorities = listOf(
            QueueLeaseContentionContract.AUTHORITY_A,
            QueueLeaseContentionContract.AUTHORITY_B,
        )
        val executor = Executors.newFixedThreadPool(2)
        try {
            val pids = authorities.map {
                callProvider(context, it, QueueLeaseContentionContract.METHOD_OPEN, databaseName)
                    .getInt(QueueLeaseContentionContract.KEY_PID)
            }
            val (pidA, pidB) = pids
            assertNotEquals(android.os.Process.myPid(), pidA, "Racer A must not be the instrumentation process.")
            assertNotEquals(android.os.Process.myPid(), pidB, "Racer B must not be the instrumentation process.")
            assertNotEquals(pidA, pidB, "The two racers must be two genuinely separate Android OS processes.")

            val winsByAuthority = mutableMapOf(
                QueueLeaseContentionContract.AUTHORITY_A to 0,
                QueueLeaseContentionContract.AUTHORITY_B to 0,
            )
            var overlappingRounds = 0

            repeat(ROUNDS) { round ->
                val entryId = "queue-lease-contention-entry-$round"
                callProvider(
                    context,
                    QueueLeaseContentionContract.AUTHORITY_A,
                    QueueLeaseContentionContract.METHOD_SEED,
                    databaseName,
                    mapOf(QueueLeaseContentionContract.EXTRA_ENTRY_ID to entryId),
                )

                val startAtMs = System.currentTimeMillis() + START_GATE_LEAD_MS
                val barrier = CyclicBarrier(2)
                val futures = authorities.map { authority ->
                    executor.submit(
                        Callable {
                            barrier.await()
                            callProvider(
                                context,
                                authority,
                                QueueLeaseContentionContract.METHOD_RACE_ACQUIRE,
                                databaseName,
                                mapOf(
                                    QueueLeaseContentionContract.EXTRA_LEASE_ID to "lease-$round-$authority",
                                    QueueLeaseContentionContract.EXTRA_START_AT_MS to startAtMs,
                                ),
                            )
                        },
                    )
                }
                val resultA = futures[0].get(30, TimeUnit.SECONDS)
                val resultB = futures[1].get(30, TimeUnit.SECONDS)
                assertEquals(pidA, resultA.getInt(QueueLeaseContentionContract.KEY_PID))
                assertEquals(pidB, resultB.getInt(QueueLeaseContentionContract.KEY_PID))

                val outcomeA = resultA.getString(QueueLeaseContentionContract.KEY_OUTCOME)
                val outcomeB = resultB.getString(QueueLeaseContentionContract.KEY_OUTCOME)
                val description = "round $round: A=$outcomeA B=$outcomeB"
                assertEquals(
                    1,
                    listOf(outcomeA, outcomeB).count { it == "WON" },
                    "Exactly one process must win the entry lease ($description).",
                )
                assertEquals(
                    1,
                    listOf(outcomeA, outcomeB).count { it == "NO_ENTRIES" },
                    "The losing process must deterministically observe NoEntries, not a " +
                        "second lease or a database failure ($description).",
                )

                val aWon = outcomeA == "WON"
                val winnerAuthority = if (aWon) authorities[0] else authorities[1]
                val loserAuthority = if (aWon) authorities[1] else authorities[0]
                val winner = if (aWon) resultA else resultB
                val loser = if (aWon) resultB else resultA
                winsByAuthority.merge(winnerAuthority, 1, Int::plus)

                val overlapped = resultA.getLong(QueueLeaseContentionContract.KEY_CALL_START_MS) <=
                    resultB.getLong(QueueLeaseContentionContract.KEY_CALL_END_MS) &&
                    resultB.getLong(QueueLeaseContentionContract.KEY_CALL_START_MS) <=
                    resultA.getLong(QueueLeaseContentionContract.KEY_CALL_END_MS)
                if (overlapped) overlappingRounds++
                Log.i(
                    TAG,
                    "round=$round winner=${if (aWon) "A" else "B"} overlapped=$overlapped " +
                        "aStart=${resultA.getLong(QueueLeaseContentionContract.KEY_CALL_START_MS)} " +
                        "aEnd=${resultA.getLong(QueueLeaseContentionContract.KEY_CALL_END_MS)} " +
                        "bStart=${resultB.getLong(QueueLeaseContentionContract.KEY_CALL_START_MS)} " +
                        "bEnd=${resultB.getLong(QueueLeaseContentionContract.KEY_CALL_END_MS)}",
                )

                // The winner holds the entry with its retry history intact.
                val winnerLease = winner.getString(QueueLeaseContentionContract.KEY_LEASE_ID)
                assertEquals("lease-$round-$winnerAuthority", winnerLease, "Winner's lease id ($description).")
                assertEquals(entryId, winner.getString(QueueLeaseContentionContract.KEY_ACQUIRED_ENTRY_ID))
                assertEquals(1, winner.getInt(QueueLeaseContentionContract.KEY_ACQUIRED_ENTRY_COUNT))
                assertEquals(
                    QueueLeaseContentionContract.SEEDED_RETRY_ATTEMPT,
                    winner.getInt(QueueLeaseContentionContract.KEY_RETRY_ATTEMPT),
                    "Winner must see the persisted retry attempt ($description).",
                )
                assertEquals(
                    QueueLeaseContentionContract.SEEDED_CUMULATIVE_DELAY_MS,
                    winner.getLong(QueueLeaseContentionContract.KEY_RETRY_CUMULATIVE_DELAY_MS),
                    "Winner must see the persisted retry budget ($description).",
                )
                assertEquals(null, loser.getString(QueueLeaseContentionContract.KEY_ACQUIRED_ENTRY_ID))

                // The loser's never-granted lease id is rejected by the lease guard.
                val loserComplete = callProvider(
                    context,
                    loserAuthority,
                    QueueLeaseContentionContract.METHOD_COMPLETE,
                    databaseName,
                    mapOf(
                        QueueLeaseContentionContract.EXTRA_ENTRY_ID to entryId,
                        QueueLeaseContentionContract.EXTRA_LEASE_ID to "lease-$round-$loserAuthority",
                    ),
                )
                assertEquals(
                    "FAILURE:QUEUE_STALE_LEASE",
                    loserComplete.getString(QueueLeaseContentionContract.KEY_RESULT),
                    "The loser must not be able to complete an entry it never leased ($description).",
                )

                // The winner advances the retry budget under its lease.
                val winnerReschedule = callProvider(
                    context,
                    winnerAuthority,
                    QueueLeaseContentionContract.METHOD_RESCHEDULE,
                    databaseName,
                    mapOf(
                        QueueLeaseContentionContract.EXTRA_ENTRY_ID to entryId,
                        QueueLeaseContentionContract.EXTRA_LEASE_ID to winnerLease!!,
                    ),
                )
                assertEquals("OK", winnerReschedule.getString(QueueLeaseContentionContract.KEY_RESULT))

                // The loser's process reads the winner's write across the process boundary.
                val readback = callProvider(
                    context,
                    loserAuthority,
                    QueueLeaseContentionContract.METHOD_READBACK_ACQUIRE,
                    databaseName,
                    mapOf(QueueLeaseContentionContract.EXTRA_LEASE_ID to "readback-$round"),
                )
                assertEquals("WON", readback.getString(QueueLeaseContentionContract.KEY_OUTCOME), description)
                assertEquals(entryId, readback.getString(QueueLeaseContentionContract.KEY_ACQUIRED_ENTRY_ID))
                assertEquals(
                    QueueLeaseContentionContract.RESCHEDULED_RETRY_ATTEMPT,
                    readback.getInt(QueueLeaseContentionContract.KEY_RETRY_ATTEMPT),
                    "The other process must observe the winner's rescheduled retry attempt ($description).",
                )
                assertEquals(
                    QueueLeaseContentionContract.RESCHEDULED_CUMULATIVE_DELAY_MS,
                    readback.getLong(QueueLeaseContentionContract.KEY_RETRY_CUMULATIVE_DELAY_MS),
                    "The other process must observe the winner's rescheduled retry budget ($description).",
                )
                val readbackComplete = callProvider(
                    context,
                    loserAuthority,
                    QueueLeaseContentionContract.METHOD_COMPLETE,
                    databaseName,
                    mapOf(
                        QueueLeaseContentionContract.EXTRA_ENTRY_ID to entryId,
                        QueueLeaseContentionContract.EXTRA_LEASE_ID to "readback-$round",
                    ),
                )
                assertEquals("OK", readbackComplete.getString(QueueLeaseContentionContract.KEY_RESULT))
            }

            // Every entry is COMPLETED: nothing left for either process to lease.
            authorities.forEach { authority ->
                val sweep = callProvider(
                    context,
                    authority,
                    QueueLeaseContentionContract.METHOD_READBACK_ACQUIRE,
                    databaseName,
                    mapOf(QueueLeaseContentionContract.EXTRA_LEASE_ID to "final-sweep-$authority"),
                )
                assertEquals("NO_ENTRIES", sweep.getString(QueueLeaseContentionContract.KEY_OUTCOME))
            }

            Log.i(
                TAG,
                "summary rounds=$ROUNDS overlappingRounds=$overlappingRounds wins=$winsByAuthority",
            )
            assertTrue(
                winsByAuthority.values.sum() == ROUNDS,
                "Every round must have produced exactly one winner. Wins: $winsByAuthority.",
            )
        } finally {
            executor.shutdown()
            authorities.forEach {
                runCatching {
                    callProvider(context, it, QueueLeaseContentionContract.METHOD_CLOSE, databaseName)
                }
            }
            context.deleteDatabase(databaseName)
        }
    }

    private fun callProvider(
        context: Context,
        authority: String,
        method: String,
        databaseName: String,
        extras: Map<String, Any> = emptyMap(),
    ): Bundle {
        val bundle = Bundle()
        extras.forEach { (key, value) ->
            when (value) {
                is String -> bundle.putString(key, value)
                is Long -> bundle.putLong(key, value)
                else -> error("Unsupported extra type for $key")
            }
        }
        return requireNotNull(
            context.contentResolver.call(authority, method, databaseName, bundle),
        ) { "ContentProvider call '$method' to '$authority' returned no result." }
    }

    private companion object {
        const val TAG = "QueueLeaseContention"
        const val ROUNDS = 25

        /** Lead time so both Binder calls reach their process before the shared start instant. */
        const val START_GATE_LEAD_MS = 150L
    }
}
