package io.dataloom.queue.room

import android.app.ActivityManager
import android.content.Context
import kotlin.test.fail

/**
 * Shared genuine-OS-process-kill mechanics reused by every instrumented test
 * that proves a durable structure survives a real Android application-process
 * kill and relaunch (for example
 * [AndroidProcessTerminationCircuitBreakerInstrumentedTest] and
 * [AndroidProcessTerminationRetryBudgetInstrumentedTest]).
 *
 * This repository has no AndroidX Test Orchestrator or equivalent
 * host-controlled runner (confirmed via investigation: no orchestrator/
 * test-services dependency anywhere in `gradle/libs.versions.toml`, no
 * `killProcess`/shell-command utility in the codebase), so a single
 * instrumented test method cannot literally kill and relaunch its own
 * process without aborting the test run. Every test that needs a genuine
 * process kill instead talks to a second component Android hosts in its own
 * named process (declared in `src/androidTest/AndroidManifest.xml`) and uses
 * [killAndAwaitProcessDeath] to terminate that separate process from within
 * this instrumentation process, which never stops running the test.
 *
 * Contains no logic specific to any one durable structure -- only the
 * generic "ask the OS to kill a named process, then poll for confirmation"
 * mechanics -- so it is safe to share across domains without distorting any
 * single test's naming.
 */
internal object ProcessTerminationTestSupport {

    private const val TERMINATION_TIMEOUT_MILLIS: Long = 10_000L
    private const val POLL_INTERVAL_MILLIS: Long = 200L

    /**
     * Repeatedly asks the OS to kill background processes of this package and
     * polls [ActivityManager.getRunningAppProcesses] until [processName] is
     * confirmed gone. Fails the test explicitly on timeout instead of
     * silently proceeding -- if the OS ever declines to kill the process,
     * that must surface as a real test failure, not a false pass.
     */
    fun killAndAwaitProcessDeath(context: Context, processName: String) {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val deadline = System.currentTimeMillis() + TERMINATION_TIMEOUT_MILLIS
        while (System.currentTimeMillis() < deadline) {
            activityManager.killBackgroundProcesses(context.packageName)
            if (activityManager.runningAppProcesses.orEmpty().none { it.processName == processName }) {
                return
            }
            Thread.sleep(POLL_INTERVAL_MILLIS)
        }
        fail("Timed out waiting for $processName to terminate.")
    }
}
