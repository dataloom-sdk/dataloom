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
 * Proves that a persisted [io.dataloom.api.conflict.UnresolvedConflictRecord]
 * survives a genuine Android OS process kill and relaunch, applying the
 * exact methodology
 * [AndroidProcessTerminationCircuitBreakerInstrumentedTest] and
 * [AndroidProcessTerminationRetryBudgetInstrumentedTest] already established
 * for `#94` to a third durable-state domain: `#95`'s conflict engine. Neither
 * `RoomDurableStateStoreUnresolvedConflictIntegrationTest`'s own restart
 * proof (a freshly constructed store sharing a *mocked* database instance,
 * never a real Room connection) nor any existing Robolectric/JVM test
 * simulates a genuine, separate OS process being killed outright -- both
 * only simulate "restart" by reopening a connection inside the *same*
 * process. This is that missing proof for the conflict-engine gate's own
 * "production-store... process-death... qualification" pending item.
 *
 * This test talks to [ConflictLogProcessTerminationContentProvider], a real
 * second component Android hosts in a separate `:conflictproof` process
 * (declared in `src/androidTest/AndroidManifest.xml`):
 *
 * 1. A [android.content.ContentResolver.call] into that provider records one
 *    real [io.dataloom.api.conflict.UnresolvedConflictRecord] through the
 *    production [io.dataloom.api.conflict.DurableUnresolvedConflictLog.record]
 *    call, backed by a real [RoomDurableStateStore], and returns the recorded
 *    facts plus the provider process's real pid.
 * 2. [android.app.ActivityManager.killBackgroundProcesses] terminates that
 *    `:conflictproof` process outright -- a genuine OS-level kill this
 *    instrumentation process (which never stops running the test) does not
 *    control the internals of. The test polls
 *    [android.app.ActivityManager.getRunningAppProcesses] until it is
 *    confirmed gone, failing loudly on timeout rather than assuming success.
 * 3. A second [android.content.ContentResolver.call] to the same authority
 *    causes Android to relaunch the `:conflictproof` process from scratch.
 *    The returned pid is asserted to differ from the first -- proof this is
 *    a genuinely new OS process, not a warm one that merely serviced a
 *    second request -- and the unresolved-conflict record it reads back
 *    through [io.dataloom.api.conflict.DurableUnresolvedConflictLog.current]
 *    from a brand new Room connection to the same on-disk database is
 *    asserted to match exactly what was recorded before the kill.
 *
 * Boundary: this proves process termination/relaunch for
 * [io.dataloom.api.conflict.DurableUnresolvedConflictLog] specifically.
 * [io.dataloom.api.conflict.DurableResolvedConflictDecisionLog] shares the
 * identical [RoomDurableStateStore] persistence path and is expected to
 * behave the same way, but is not separately proven here -- a real, narrow
 * follow-up, not silently claimed. This also does not exercise cross-process
 * *contention* for a concurrent [io.dataloom.api.conflict.DurableUnresolvedConflictLog.record]
 * race (a separate, still-open acceptance item, the same shape
 * [AndroidCircuitBreakerProbeContentionInstrumentedTest] closes for the
 * circuit-breaker domain), and it does not run conflict detection/resolution
 * through a composed `DataLoomBuilder` instance.
 */
@RunWith(AndroidJUnit4::class)
class AndroidProcessTerminationConflictLogInstrumentedTest {

    @Test
    fun unresolvedConflictRecordSurvivesGenuineProcessKillAndRelaunch() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "dataloom-conflict-proof-process-kill-${UUID.randomUUID()}"
        context.deleteDatabase(databaseName)
        val conflictProofProcessName = "${context.packageName}${ConflictLogProcessTerminationContract.PROCESS_SUFFIX}"

        try {
            val recorded = callProvider(
                context,
                ConflictLogProcessTerminationContract.METHOD_RECORD_CONFLICT,
                databaseName,
            )
            assertEquals(
                "RECORDED",
                recorded.getString(ConflictLogProcessTerminationContract.KEY_STATUS),
            )
            val pidBeforeKill = recorded.getInt(ConflictLogProcessTerminationContract.KEY_PID)
            assertNotEquals(
                android.os.Process.myPid(),
                pidBeforeKill,
                "The conflict-proof provider must run in a separate :conflictproof process, " +
                    "not this instrumentation test's own process.",
            )

            ProcessTerminationTestSupport.killAndAwaitProcessDeath(context, conflictProofProcessName)

            val reread = callProvider(
                context,
                ConflictLogProcessTerminationContract.METHOD_READ_CONFLICT,
                databaseName,
            )
            val pidAfterRelaunch = reread.getInt(ConflictLogProcessTerminationContract.KEY_PID)
            assertNotEquals(
                pidBeforeKill,
                pidAfterRelaunch,
                "Android must have relaunched :conflictproof as a genuinely new OS process " +
                    "after the kill, not reused the terminated one.",
            )

            assertEquals("RECORDED", reread.getString(ConflictLogProcessTerminationContract.KEY_STATUS))
            assertFieldsEqual(recorded, reread)
        } finally {
            context.deleteDatabase(databaseName)
        }
    }

    private fun assertFieldsEqual(expected: Bundle, actual: Bundle) {
        assertEquals(
            expected.getString(ConflictLogProcessTerminationContract.KEY_CONFLICT_TYPE),
            actual.getString(ConflictLogProcessTerminationContract.KEY_CONFLICT_TYPE),
        )
        assertEquals(
            expected.getString(ConflictLogProcessTerminationContract.KEY_ENTITY_TYPE),
            actual.getString(ConflictLogProcessTerminationContract.KEY_ENTITY_TYPE),
        )
        assertEquals(
            expected.getString(ConflictLogProcessTerminationContract.KEY_ENTITY_ID),
            actual.getString(ConflictLogProcessTerminationContract.KEY_ENTITY_ID),
        )
        assertEquals(
            expected.getString(ConflictLogProcessTerminationContract.KEY_LOCAL_CHANGE_EVENT_ID),
            actual.getString(ConflictLogProcessTerminationContract.KEY_LOCAL_CHANGE_EVENT_ID),
        )
        assertEquals(
            expected.getString(ConflictLogProcessTerminationContract.KEY_REMOTE_CHANGE_EVENT_ID),
            actual.getString(ConflictLogProcessTerminationContract.KEY_REMOTE_CHANGE_EVENT_ID),
        )
        assertEquals(
            expected.getString(ConflictLogProcessTerminationContract.KEY_REASON),
            actual.getString(ConflictLogProcessTerminationContract.KEY_REASON),
        )
        assertEquals(
            expected.getLong(ConflictLogProcessTerminationContract.KEY_COMMITTED_AT_MILLIS),
            actual.getLong(ConflictLogProcessTerminationContract.KEY_COMMITTED_AT_MILLIS),
        )
    }

    private fun callProvider(context: Context, method: String, databaseName: String): Bundle {
        return requireNotNull(
            context.contentResolver.call(
                ConflictLogProcessTerminationContract.AUTHORITY,
                method,
                databaseName,
                null,
            ),
        ) { "ContentProvider call '$method' returned no result." }
    }
}
