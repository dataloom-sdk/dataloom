package io.dataloom.queue.room

import android.app.ActivityManager
import android.content.Context
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
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
 * After the relaunch, this test also re-drives the real production
 * `CircuitBreakerCoordinator.acquire`/`recordSuccess` gate itself -- not just
 * [RoomCircuitBreakerStateStore.load], which the read-back above (and every
 * assertion before it) is limited to -- three ways, all against the same
 * relaunched `:circuitproof` process and on-disk database:
 *
 * 4. **Reject-before-deadline**: one millisecond before the circuit's
 *    persisted open-deadline, the real gate still returns `Rejected(OPEN)`.
 * 5. **Probe-granted-at-deadline**: exactly at the deadline, the real gate
 *    grants exactly one half-open probe permission -- the same
 *    single-probe-permit semantics
 *    [AndroidCircuitBreakerProbeContentionInstrumentedTest] already proves
 *    for the non-relaunch case, now also proven to survive a genuine process
 *    kill/relaunch.
 * 6. **Recovery-after-success**: after that probe is recorded as a real
 *    success, the real gate returns to normal `Allowed` state.
 *
 * These three re-drives close the specific gap the 2026-09-29 audit found
 * still open: no test previously re-drove the real circuit-breaker execution
 * gate after a genuine process kill/relaunch, proving only that the raw
 * persisted row survived, never the actual ALLOWED/REJECTED/PROBE_ALLOWED
 * decision the gate would make from it.
 *
 * Boundary: this proves process termination/relaunch for the Android Room
 * circuit-breaker store and its real execution gate specifically. It does
 * not exercise cross-process *contention* for the half-open probe lease
 * after a relaunch (contention itself is proven, without a relaunch, by
 * [AndroidCircuitBreakerProbeContentionInstrumentedTest]), it does not run
 * the full retry-scheduling/transport-provider AC-FUNC-004 flow through a
 * composed `DataLoomBuilder` instance -- see
 * `docs/audits/DL-040-ac-func-004-android-room-qualification.md` -- and it
 * does not prove the equivalent for the Apple/Kotlin-Native circuit-breaker
 * store, which remains a separate, still-open item.
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

            // Re-drive the real gate, not just the persisted row, against the
            // relaunched process -- the specific gap this test previously left
            // open (see class KDoc). All three calls below are served by the
            // same already-relaunched :circuitproof process (pidAfterRelaunch);
            // none of them re-kills it.

            val beforeDeadline = callProvider(
                context,
                CircuitBreakerProcessTerminationContract.METHOD_ATTEMPT_ACCESS_BEFORE_DEADLINE,
                databaseName,
            )
            assertEquals(
                pidAfterRelaunch,
                beforeDeadline.getInt(CircuitBreakerProcessTerminationContract.KEY_PID),
                "The before-deadline gate re-drive must be served by the already-relaunched " +
                    ":circuitproof process, not a third process.",
            )
            assertEquals(
                "REJECTED",
                beforeDeadline.getString(CircuitBreakerProcessTerminationContract.KEY_OUTCOME),
                "One millisecond before the circuit's persisted open-deadline, the relaunched " +
                    "process's real gate must still reject access, not just report a persisted " +
                    "OPEN phase.",
            )
            assertEquals(
                "OPEN",
                beforeDeadline.getString(CircuitBreakerProcessTerminationContract.KEY_REJECTION_REASON),
            )

            val atDeadline = callProvider(
                context,
                CircuitBreakerProcessTerminationContract.METHOD_ATTEMPT_PROBE_AT_DEADLINE,
                databaseName,
            )
            assertEquals(
                pidAfterRelaunch,
                atDeadline.getInt(CircuitBreakerProcessTerminationContract.KEY_PID),
                "The at-deadline probe re-drive must be served by the already-relaunched " +
                    ":circuitproof process, not a third process.",
            )
            assertEquals(
                "PROBE_ALLOWED",
                atDeadline.getString(CircuitBreakerProcessTerminationContract.KEY_OUTCOME),
                "Exactly at the circuit's persisted open-deadline, the relaunched process's " +
                    "real gate must grant exactly one half-open probe permission, not just " +
                    "report a persisted phase transition.",
            )
            val grantedGeneration = atDeadline.getLong(CircuitBreakerProcessTerminationContract.KEY_GENERATION)
            assertTrue(
                grantedGeneration > 0L,
                "The relaunched process's real gate must have granted a real probe generation, " +
                    "was: $grantedGeneration.",
            )

            val recovered = callProvider(
                context,
                CircuitBreakerProcessTerminationContract.METHOD_RECORD_PROBE_SUCCESS_AND_REVERIFY_RECOVERY,
                databaseName,
            )
            assertEquals(
                pidAfterRelaunch,
                recovered.getInt(CircuitBreakerProcessTerminationContract.KEY_PID),
                "The recovery re-drive must be served by the already-relaunched " +
                    ":circuitproof process, not a third process.",
            )
            assertEquals(
                "ALLOWED",
                recovered.getString(CircuitBreakerProcessTerminationContract.KEY_OUTCOME),
                "After the relaunched process's real gate records the half-open probe as a " +
                    "success, its real gate must return to normal ALLOWED state, not just " +
                    "report a persisted CLOSED phase.",
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
