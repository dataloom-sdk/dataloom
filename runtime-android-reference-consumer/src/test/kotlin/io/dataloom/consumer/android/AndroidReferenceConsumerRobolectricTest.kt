package io.dataloom.consumer.android

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import io.dataloom.api.provider.ProviderLifecycleResult
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals

/**
 * Robolectric-backed runtime proof for [buildReferenceDataLoom], the first
 * real (non-compile-only) evidence closing part of `#101`'s
 * "Robolectric/instrumented runtime proof for the reference consumer"
 * gap.
 *
 * ## What this proves
 *
 * Everything below runs against a real, Robolectric-simulated Android
 * runtime, not a fake or a mock: [WorkManagerSchedulerProvider][io.dataloom.scheduler.workmanager.WorkManagerSchedulerProvider]
 * resolves a real `WorkManager` instance, [RoomStorageProvider][io.dataloom.storage.room.RoomStorageProvider]
 * and [RoomQueueProvider][io.dataloom.queue.room.RoomQueueProvider] each open
 * a real Room-backed SQLite database, and [AndroidConnectivityProvider][io.dataloom.connectivity.android.AndroidConnectivityProvider]
 * resolves a real `ConnectivityManager` service reference. If any of the
 * four providers' constructors or `initialize()`/`shutdown()`
 * implementations genuinely fail against Android framework code (not just
 * against DataLoom's own contracts), this test fails.
 *
 * ## What this does not prove
 *
 * [DataLoom.synchronize] is never called — that would exercise the full
 * synchronization pipeline (storage reads/writes, queue admission,
 * transport calls), which is out of scope for this bounded slice. This
 * also does not run on a physical device or emulator; Robolectric
 * simulates the Android framework on the JVM, which is a real and useful
 * runtime signal but not identical to on-device behavior (timing,
 * process-death, and OS-version-specific quirks can still differ). The
 * full foreground/offline/retry/circuit/conflict/event/asset/
 * cancellation/concurrency/resource-limit/migration/termination/relaunch
 * matrix `#101`'s acceptance criteria require remains open.
 */
@RunWith(RobolectricTestRunner::class)
class AndroidReferenceConsumerRobolectricTest {

    @Test
    fun realAndroidProvidersInitializeAndShutDownCleanlyUnderRobolectric() = runTest {
        val context: Context = ApplicationProvider.getApplicationContext()

        // WorkManagerSchedulerProvider's constructor calls
        // WorkManager.getInstance(context) directly, so WorkManager must
        // already be initialized before buildReferenceDataLoom runs.
        // WorkManagerTestInitHelper is androidx.work's own documented
        // Robolectric/instrumented-test entry point for this — the real
        // production WorkManager.initialize() call requires a
        // Configuration.Provider-backed Application, which this bare test
        // context does not supply.
        WorkManagerTestInitHelper.initializeTestWorkManager(context)

        val dataLoom = buildReferenceDataLoom(context)

        val initializeResult = dataLoom.initialize()
        assertEquals(ProviderLifecycleResult.InitializeSuccess, initializeResult)

        val shutdownResult = dataLoom.shutdown()
        assertEquals(ProviderLifecycleResult.ShutdownSuccess, shutdownResult)
    }
}
