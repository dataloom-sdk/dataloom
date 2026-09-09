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
 * Proves that a persisted [io.dataloom.api.conflict.ResolvedConflictDecisionRecord]
 * survives a genuine Android OS process kill and relaunch, extending
 * [AndroidProcessTerminationConflictLogInstrumentedTest]'s exact methodology
 * from `#95`'s [io.dataloom.api.conflict.DurableUnresolvedConflictLog] domain
 * to its sibling [io.dataloom.api.conflict.DurableResolvedConflictDecisionLog]
 * domain -- closing that test's own class doc, which named this specific
 * extension as "expected to behave the same way but ... not separately
 * proven here", rather than leaving it silently unproven indefinitely.
 *
 * Both domains share the identical [RoomDurableStateStore] persistence path
 * (compare-and-set over a generic `TScope`/`TState` contract), differing only
 * in the record type and [io.dataloom.api.state.DurableStateCodec] plugged
 * in -- so this test, its content provider, and its contract are a
 * field-for-field mirror of the unresolved-conflict proof, not a new design.
 *
 * This test talks to
 * [ResolvedConflictDecisionLogProcessTerminationContentProvider], a real
 * second component Android hosts in a separate `:resolvedconflictproof`
 * process (declared in `src/androidTest/AndroidManifest.xml`):
 *
 * 1. A [android.content.ContentResolver.call] into that provider records one
 *    real [io.dataloom.api.conflict.ResolvedConflictDecisionRecord] through
 *    the production
 *    [io.dataloom.api.conflict.DurableResolvedConflictDecisionLog.record]
 *    call, backed by a real [RoomDurableStateStore], and returns the
 *    recorded facts plus the provider process's real pid.
 * 2. [android.app.ActivityManager.killBackgroundProcesses] terminates that
 *    `:resolvedconflictproof` process outright -- a genuine OS-level kill
 *    this instrumentation process (which never stops running the test) does
 *    not control the internals of. The test polls
 *    [android.app.ActivityManager.getRunningAppProcesses] until it is
 *    confirmed gone, failing loudly on timeout rather than assuming success.
 * 3. A second [android.content.ContentResolver.call] to the same authority
 *    causes Android to relaunch the `:resolvedconflictproof` process from
 *    scratch. The returned pid is asserted to differ from the first --
 *    proof this is a genuinely new OS process, not a warm one that merely
 *    serviced a second request -- and the resolved-decision record it reads
 *    back through
 *    [io.dataloom.api.conflict.DurableResolvedConflictDecisionLog.current]
 *    from a brand new Room connection to the same on-disk database is
 *    asserted to match exactly what was recorded before the kill.
 *
 * Boundary: this proves process termination/relaunch for
 * [io.dataloom.api.conflict.DurableResolvedConflictDecisionLog] specifically.
 * It does not exercise cross-process *contention* for a concurrent
 * [io.dataloom.api.conflict.DurableResolvedConflictDecisionLog.record] race
 * (a separate, still-open acceptance item, the same shape
 * [AndroidCircuitBreakerProbeContentionInstrumentedTest] closes for the
 * circuit-breaker domain, and which remains unexercised for either conflict
 * domain), Apple process-kill evidence (blocked on the same infrastructure
 * gap `#94` already documents), and it does not run conflict
 * detection/resolution through a composed `DataLoomBuilder` instance.
 */
@RunWith(AndroidJUnit4::class)
class AndroidProcessTerminationResolvedConflictDecisionLogInstrumentedTest {

    @Test
    fun resolvedConflictDecisionRecordSurvivesGenuineProcessKillAndRelaunch() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "dataloom-resolved-conflict-proof-process-kill-${UUID.randomUUID()}"
        context.deleteDatabase(databaseName)
        val resolvedConflictProofProcessName =
            "${context.packageName}${ResolvedConflictDecisionLogProcessTerminationContract.PROCESS_SUFFIX}"

        try {
            val recorded = callProvider(
                context,
                ResolvedConflictDecisionLogProcessTerminationContract.METHOD_RECORD_DECISION,
                databaseName,
            )
            assertEquals(
                "RECORDED",
                recorded.getString(ResolvedConflictDecisionLogProcessTerminationContract.KEY_STATUS),
            )
            val pidBeforeKill = recorded.getInt(ResolvedConflictDecisionLogProcessTerminationContract.KEY_PID)
            assertNotEquals(
                android.os.Process.myPid(),
                pidBeforeKill,
                "The resolved-conflict-proof provider must run in a separate :resolvedconflictproof " +
                    "process, not this instrumentation test's own process.",
            )

            ProcessTerminationTestSupport.killAndAwaitProcessDeath(context, resolvedConflictProofProcessName)

            val reread = callProvider(
                context,
                ResolvedConflictDecisionLogProcessTerminationContract.METHOD_READ_DECISION,
                databaseName,
            )
            val pidAfterRelaunch = reread.getInt(ResolvedConflictDecisionLogProcessTerminationContract.KEY_PID)
            assertNotEquals(
                pidBeforeKill,
                pidAfterRelaunch,
                "Android must have relaunched :resolvedconflictproof as a genuinely new OS process " +
                    "after the kill, not reused the terminated one.",
            )

            assertEquals(
                "RECORDED",
                reread.getString(ResolvedConflictDecisionLogProcessTerminationContract.KEY_STATUS),
            )
            assertFieldsEqual(recorded, reread)
        } finally {
            context.deleteDatabase(databaseName)
        }
    }

    private fun assertFieldsEqual(expected: Bundle, actual: Bundle) {
        assertEquals(
            expected.getString(ResolvedConflictDecisionLogProcessTerminationContract.KEY_CONFLICT_TYPE),
            actual.getString(ResolvedConflictDecisionLogProcessTerminationContract.KEY_CONFLICT_TYPE),
        )
        assertEquals(
            expected.getString(ResolvedConflictDecisionLogProcessTerminationContract.KEY_ENTITY_TYPE),
            actual.getString(ResolvedConflictDecisionLogProcessTerminationContract.KEY_ENTITY_TYPE),
        )
        assertEquals(
            expected.getString(ResolvedConflictDecisionLogProcessTerminationContract.KEY_ENTITY_ID),
            actual.getString(ResolvedConflictDecisionLogProcessTerminationContract.KEY_ENTITY_ID),
        )
        assertEquals(
            expected.getString(ResolvedConflictDecisionLogProcessTerminationContract.KEY_LOCAL_CHANGE_EVENT_ID),
            actual.getString(ResolvedConflictDecisionLogProcessTerminationContract.KEY_LOCAL_CHANGE_EVENT_ID),
        )
        assertEquals(
            expected.getString(ResolvedConflictDecisionLogProcessTerminationContract.KEY_REMOTE_CHANGE_EVENT_ID),
            actual.getString(ResolvedConflictDecisionLogProcessTerminationContract.KEY_REMOTE_CHANGE_EVENT_ID),
        )
        assertEquals(
            expected.getString(ResolvedConflictDecisionLogProcessTerminationContract.KEY_RESOLVER_ID),
            actual.getString(ResolvedConflictDecisionLogProcessTerminationContract.KEY_RESOLVER_ID),
        )
        assertEquals(
            expected.getString(ResolvedConflictDecisionLogProcessTerminationContract.KEY_DECISION_KIND),
            actual.getString(ResolvedConflictDecisionLogProcessTerminationContract.KEY_DECISION_KIND),
        )
        assertEquals(
            expected.getLong(ResolvedConflictDecisionLogProcessTerminationContract.KEY_COMMITTED_AT_MILLIS),
            actual.getLong(ResolvedConflictDecisionLogProcessTerminationContract.KEY_COMMITTED_AT_MILLIS),
        )
    }

    private fun callProvider(context: Context, method: String, databaseName: String): Bundle {
        return requireNotNull(
            context.contentResolver.call(
                ResolvedConflictDecisionLogProcessTerminationContract.AUTHORITY,
                method,
                databaseName,
                null,
            ),
        ) { "ContentProvider call '$method' returned no result." }
    }
}
