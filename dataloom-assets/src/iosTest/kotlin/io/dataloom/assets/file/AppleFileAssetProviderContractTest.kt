package io.dataloom.assets.file

import io.dataloom.assets.AssetProvider
import io.dataloom.assets.AssetQuota
import io.dataloom.assets.platformDigests
import io.dataloom.assets.testkit.AssetProviderContractKit
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Proves [AppleFileAssetProvider] satisfies the same behavioural contract as
 * [io.dataloom.assets.memory.InMemoryAssetProvider] and the JVM
 * `FileAssetProvider`, by running the same shared, framework-neutral
 * [AssetProviderContractKit] against it — no parallel contract test was
 * written; this reuses the exact kit the JVM implementation passes. Each
 * scenario gets a fresh, empty provider backed by its own unique temporary
 * directory, matching the kit's "fresh provider per scenario" contract.
 *
 * Compile-verified only for `iosArm64`/`iosSimulatorArm64`/`iosX64`: this
 * suite cannot be executed on a Windows host (Kotlin/Native binaries for
 * Apple targets link but do not run here), and no macOS/Xcode runner was
 * available in this session either. It is written to run and to be exercised
 * on the first macOS CI pass, exactly like the rest of this module's `iosTest`
 * sources.
 */
class AppleFileAssetProviderContractTest {

    private fun kit(): AssetProviderContractKit =
        AssetProviderContractKit(platformDigests()) { quota: AssetQuota ->
            val base = uniqueTestDirectory("apple-file-asset-provider-contract")
            AppleFileAssetProvider(base, platformDigests(), quota) as AssetProvider
        }

    @Test
    fun `the apple file-backed provider passes the full provider contract kit`() = runTest {
        val report = kit().run()
        report.assertAllPassed()
        assertTrue(report.results.size >= 20, "kit should exercise at least 20 scenarios, ran ${report.results.size}")
    }
}
