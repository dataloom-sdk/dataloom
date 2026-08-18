package io.dataloom.queue.room

import android.app.ActivityManager
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
 * Proves that persisted circuit-breaker state survives a genuine Android OS
 * process kill and relaunch, closing the gap the existing
 * [RoomCircuitBreakerStateStoreInstrumentedTest] and
 * [RoomRetryCircuitFunctionalQualificationInstrumentedTest] leave open --
 * both already run on a real Gradle Managed Device emulator, but both
 * simulate a "restart" only by closing and reopening a Room connection
 * inside the *same* Android application process. See each file's own
 * `docs/audits/DL-040-ac-func-004-android-room-qualification.md` boundary
 * note.
 *
 * This test instead talks to [CircuitBreakerProcessTerminationContentProvider],
 * a real second component that Android hosts in a separate `:circuitproof`
 * process (declared in `src/androidTest/AndroidManifest.xml`):
 *
 * 1. A [android.content.ContentResolver.call] into that provider drives two
 *    real circuit-breaker failures through the production
 *    `CircuitBreakerExecutionGate`, opening the circuit, and returns the
 *    persisted state plus the provider process's real pid.
 * 2. [ActivityManager.killBackgroundProcesses] terminates that `:circuitproof`
 *    process outright -- a genuine OS-level kill of a real Android process
 *    that this JVM/instrumentation process (which never stops running the
 *    test) does not control the internals of. The test polls
 *    [ActivityManager.getRunningAppProcesses] until it is confirmed gone,
 *    failing loudly on timeout rather than assuming success.
 * 3. A second [android.content.ContentResolver.call] to the same authority
 *    causes Android to relaunch the `:circuitproof` process from scratch
 *    (ContentProviders start their host process on first access). The
 *    returned pid is asserted to differ from the first -- proof this is a
 *    genuinely new OS process, not a warm one that merely serviced a second
 *    request -- and the circuit-breaker state it reads back from a brand
 *    new Room connection to the same on-disk database is asserted to match
 *    exactly what was persisted before the kill.
 *
 * Boundary: this proves process termination/relaunch for the Android Room
 * circuit-breaker store specifically. It does not exercise cross-process
 * *contention* for the half-open probe lease (a separate, still-open
 * acceptance item), and it does not run the full retry-scheduling/
 * transport-provider AC-FUNC-004 flow through a composed `DataLoomBuilder`
 * instance -- see `docs/audits/DL-040-ac-func-004-android-room-qualification.md`.
 */
@RunWith(AndroidJUnit4::class)
class AndroidProcessTerminationCircuitBreakerInstrumentedTest {

    @Test
    fun circuitBreakerStateSurvivesGenuineProcessKillAndRelaunch() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "dataloom-circuit-proof-process-kill-${UUID.randomUUID()}"
        context.deleteDatabase(databaseName)
        val circuitProofProcessName = "${context.packageName}${CircuitBreakerProcessTerminationContract.PROCESS_SUFFIX}"

        try {
            val opened = callProvider(
                context,
                CircuitBreakerProcessTerminationContract.METHOD_OPEN_CIRCUIT,
                databaseName,
            )
            assertEquals(
                "OPEN",
                opened.getString(CircuitBreakerProcessTerminationContract.KEY_PHASE),
            )
            val pidBeforeKill = opened.getInt(CircuitBreakerProcessTerminationContract.KEY_PID)
            assertNotEquals(
                android.os.Process.myPid(),
                pidBeforeKill,
                "The circuit-proof provider must run in a separate :circuitproof process, " +
                    "not this instrumentation test's own process.",
            )

            ProcessTerminationTestSupport.killAndAwaitProcessDeath(context, circuitProofProcessName)

            val reread = callProvider(
                context,
                CircuitBreakerProcessTerminationContract.METHOD_READ_CIRCUIT_STATE,
                databaseName,
            )
            val pidAfterRelaunch = reread.getInt(CircuitBreakerProcessTerminationContract.KEY_PID)
            assertNotEquals(
                pidBeforeKill,
                pidAfterRelaunch,
                "Android must have relaunched :circuitproof as a genuinely new OS process " +
                    "after the kill, not reused the terminated one.",
            )

            assertEquals(
                opened.getString(CircuitBreakerProcessTerminationContract.KEY_PHASE),
                reread.getString(CircuitBreakerProcessTerminationContract.KEY_PHASE),
            )
            assertEquals(
                opened.getLong(CircuitBreakerProcessTerminationContract.KEY_OPEN_UNTIL_MILLIS),
                reread.getLong(CircuitBreakerProcessTerminationContract.KEY_OPEN_UNTIL_MILLIS),
            )
            assertEquals(
                opened.getInt(CircuitBreakerProcessTerminationContract.KEY_CONSECUTIVE_FAILURES),
                reread.getInt(CircuitBreakerProcessTerminationContract.KEY_CONSECUTIVE_FAILURES),
            )
            assertEquals(
                opened.getLong(CircuitBreakerProcessTerminationContract.KEY_PROBE_GENERATION),
                reread.getLong(CircuitBreakerProcessTerminationContract.KEY_PROBE_GENERATION),
            )
        } finally {
            context.deleteDatabase(databaseName)
        }
    }

    private fun callProvider(context: Context, method: String, databaseName: String): Bundle {
        return requireNotNull(
            context.contentResolver.call(
                CircuitBreakerProcessTerminationContract.AUTHORITY,
                method,
                databaseName,
                null,
            ),
        ) { "ContentProvider call '$method' returned no result." }
    }
}
