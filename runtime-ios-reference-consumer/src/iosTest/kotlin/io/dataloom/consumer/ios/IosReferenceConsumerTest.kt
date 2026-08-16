@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.dataloom.consumer.ios

import io.dataloom.api.provider.ProviderLifecycleResult
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID

/**
 * Kotlin/Native runtime proof for [buildReferenceDataLoom], executed by
 * `iosSimulatorArm64Test`/`iosX64Test` on macOS CI — the iOS-side
 * counterpart to `AndroidReferenceConsumerRobolectricTest`.
 *
 * ## What this proves
 *
 * Everything below runs against a real Kotlin/Native iOS Simulator
 * runtime, not a fake or a mock: [AppleConnectivityProvider][io.dataloom.platform.ios.connectivity.AppleConnectivityProvider]
 * queries the real `NWPathMonitor` network path, [AppleSchedulerProvider][io.dataloom.platform.ios.scheduling.AppleSchedulerProvider]
 * constructs against real `BGTaskScheduler` bindings, [SqlDelightStorageProvider][io.dataloom.storage.sqldelight.SqlDelightStorageProvider]
 * opens a real SQLite database file via SQLDelight's native driver, and
 * [AppleFileQueueProvider][io.dataloom.runtime.queue.AppleFileQueueProvider]
 * reads/writes a real file under the supplied temporary directory. If any
 * of the four providers' constructors or `initialize()`/`shutdown()`
 * implementations genuinely fail against iOS platform APIs (not just
 * against DataLoom's own contracts), this test fails. Each test run uses a
 * fresh, UUID-suffixed directory and database/queue file names so repeated
 * runs never read stale state left by a previous run.
 *
 * ## What this does not prove
 *
 * [DataLoom.synchronize][io.dataloom.runtime.facade.DataLoom.synchronize]
 * is never called — storage/queue/transport I/O stays out of scope for
 * this bounded slice, the same boundary
 * `AndroidReferenceConsumerRobolectricTest` documents for Android. This
 * also does not run on a physical device; the iOS Simulator is a real,
 * Apple-provided runtime, not a shadow layer, but device-only behavior
 * (background execution limits, real network conditions, memory pressure)
 * can still differ.
 *
 * ## A note on how this was verified
 *
 * This test can be cross-compiled from a Windows development host
 * (`compileTestKotlinIosArm64`/`IosSimulatorArm64`/`IosX64`), which
 * catches type errors and API drift, but **cannot be executed** there —
 * only a real macOS host with Xcode and the iOS Simulator can run
 * `iosSimulatorArm64Test`/`iosX64Test`. This repository's
 * `apple-validation.yml` CI job (`macos-15`) is the actual pass/fail
 * signal for this file's runtime behavior, not local cross-compilation
 * alone.
 */
class IosReferenceConsumerTest {

    @Test
    fun realIosProvidersInitializeAndShutDownCleanlyOnTheSimulator() = runTest {
        val runId = NSUUID().UUIDString
        val directoryPath = buildString {
            append(NSTemporaryDirectory().trimEnd('/'))
            append("/dataloom-ios-reference-consumer-")
            append(runId)
        }

        val dataLoom = buildReferenceDataLoom(
            directoryPath = directoryPath,
            storageDatabaseName = "dataloom-storage-$runId.db",
            queueFileName = "dataloom-queue-$runId.tsv",
        )

        val initializeResult = dataLoom.initialize()
        assertEquals(ProviderLifecycleResult.InitializeSuccess, initializeResult)

        val shutdownResult = dataLoom.shutdown()
        assertEquals(ProviderLifecycleResult.ShutdownSuccess, shutdownResult)
    }
}
