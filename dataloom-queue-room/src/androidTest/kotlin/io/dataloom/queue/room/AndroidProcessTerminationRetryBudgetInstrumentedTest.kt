package io.dataloom.queue.room

import android.content.Context
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Proves that persisted durable retry-budget state (attempt count, retry
 * window, cumulative delay) survives a genuine Android OS process kill and
 * relaunch -- the same bar
 * [AndroidProcessTerminationCircuitBreakerInstrumentedTest] already proved
 * for the circuit-breaker store, closing the "real Android termination/
 * relaunch for retry-budget state ... a separate durable structure from
 * circuit-breaker state" item `docs/status/market-readiness.md`'s `#94` row
 * previously named as remaining.
 *
 * Retry-budget state genuinely is a separate durable structure: it lives in
 * the `retry_attempt_number`, `retry_window_started_at_ms`,
 * `retry_last_evaluated_at_ms`, and `retry_cumulative_delay_ms` columns of
 * the `queue_entries` table (added by `DataLoomRoomMigrations.MIGRATION_1_2`)
 * and is written and read exclusively through the real [RoomQueueProvider]
 * production coordinator's `enqueue`/`acquire`/`reschedule`/`defer`
 * operations -- never the independent `circuit_breaker_states` table
 * (added by `MIGRATION_2_3`) that [RoomCircuitBreakerStateStore] owns.
 *
 * This test talks to [RetryBudgetProcessTerminationContentProvider], a real
 * second component Android hosts in a separate `:retrybudgetproof` process
 * (declared in `src/androidTest/AndroidManifest.xml`), reusing the exact
 * kill/poll/relaunch mechanics
 * [AndroidProcessTerminationCircuitBreakerInstrumentedTest] established,
 * factored into [ProcessTerminationTestSupport]:
 *
 * 1. A [android.content.ContentResolver.call] into that provider enqueues a
 *    real queue entry and drives it through the real `RoomQueueProvider`
 *    `acquire -> reschedule -> acquire -> defer` sequence, persisting a
 *    genuine retry attempt number and retry-budget window/cumulative delay,
 *    and returns that state (read back by an independent acquisition, not
 *    merely echoed) plus the provider process's real pid.
 * 2. [android.app.ActivityManager.killBackgroundProcesses] terminates that
 *    `:retrybudgetproof` process outright -- a genuine OS-level kill of a
 *    real Android process that this JVM/instrumentation process (which
 *    never stops running the test) does not control the internals of. The
 *    test polls [android.app.ActivityManager.getRunningAppProcesses] until
 *    it is confirmed gone, failing loudly on timeout rather than assuming
 *    success.
 * 3. A second [android.content.ContentResolver.call] to the same authority
 *    causes Android to relaunch `:retrybudgetproof` from scratch (content
 *    providers start their host process on first access). The returned pid
 *    is asserted to differ from the first -- proof this is a genuinely new
 *    OS process, not a warm one that merely serviced a second request --
 *    and the retry-budget state that fresh process reads back from a brand
 *    new Room connection to the same on-disk database, via a real
 *    `RoomQueueProvider.acquire` call, is asserted to match exactly what
 *    was persisted before the kill.
 *
 * Boundary: this proves process termination/relaunch for the Android Room
 * retry-budget durable structure specifically. It does not exercise
 * cross-process *contention* for queue acquisition, and it does not run the
 * full retry-scheduling/transport-provider AC-FUNC-004 flow through a
 * composed `DataLoomBuilder` instance -- see
 * `docs/audits/DL-040-ac-func-004-android-room-qualification.md`.
 */
@RunWith(AndroidJUnit4::class)
class AndroidProcessTerminationRetryBudgetInstrumentedTest {

    @Test
    fun retryBudgetStateSurvivesGenuineProcessKillAndRelaunch() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "dataloom-retry-budget-proof-process-kill-${UUID.randomUUID()}"
        context.deleteDatabase(databaseName)
        val retryBudgetProofProcessName =
            "${context.packageName}${RetryBudgetProcessTerminationContract.PROCESS_SUFFIX}"

        try {
            val written = callProvider(
                context,
                RetryBudgetProcessTerminationContract.METHOD_WRITE_RETRY_BUDGET,
                databaseName,
            )
            assertEquals(
                EXPECTED_RETRY_ATTEMPT_NUMBER,
                written.getInt(RetryBudgetProcessTerminationContract.KEY_RETRY_ATTEMPT_NUMBER),
            )
            val pidBeforeKill = written.getInt(RetryBudgetProcessTerminationContract.KEY_PID)
            assertNotEquals(
                android.os.Process.myPid(),
                pidBeforeKill,
                "The retry-budget-proof provider must run in a separate :retrybudgetproof " +
                    "process, not this instrumentation test's own process.",
            )

            ProcessTerminationTestSupport.killAndAwaitProcessDeath(context, retryBudgetProofProcessName)

            val reread = callProvider(
                context,
                RetryBudgetProcessTerminationContract.METHOD_READ_RETRY_BUDGET,
                databaseName,
            )
            val pidAfterRelaunch = reread.getInt(RetryBudgetProcessTerminationContract.KEY_PID)
            assertNotEquals(
                pidBeforeKill,
                pidAfterRelaunch,
                "Android must have relaunched :retrybudgetproof as a genuinely new OS " +
                    "process after the kill, not reused the terminated one.",
            )

            assertEquals(
                written.getInt(RetryBudgetProcessTerminationContract.KEY_RETRY_ATTEMPT_NUMBER),
                reread.getInt(RetryBudgetProcessTerminationContract.KEY_RETRY_ATTEMPT_NUMBER),
            )
            assertEquals(
                written.getLong(RetryBudgetProcessTerminationContract.KEY_RETRY_WINDOW_STARTED_AT_MILLIS),
                reread.getLong(RetryBudgetProcessTerminationContract.KEY_RETRY_WINDOW_STARTED_AT_MILLIS),
            )
            assertEquals(
                written.getLong(RetryBudgetProcessTerminationContract.KEY_RETRY_LAST_EVALUATED_AT_MILLIS),
                reread.getLong(RetryBudgetProcessTerminationContract.KEY_RETRY_LAST_EVALUATED_AT_MILLIS),
            )
            assertEquals(
                written.getLong(RetryBudgetProcessTerminationContract.KEY_RETRY_CUMULATIVE_DELAY_MILLIS),
                reread.getLong(RetryBudgetProcessTerminationContract.KEY_RETRY_CUMULATIVE_DELAY_MILLIS),
            )
        } finally {
            context.deleteDatabase(databaseName)
        }
    }

    private fun callProvider(context: Context, method: String, databaseName: String): Bundle {
        return requireNotNull(
            context.contentResolver.call(
                RetryBudgetProcessTerminationContract.AUTHORITY,
                method,
                databaseName,
                null,
            ),
        ) { "ContentProvider call '$method' returned no result." }
    }

    private companion object {
        const val EXPECTED_RETRY_ATTEMPT_NUMBER: Int = 3
    }
}
