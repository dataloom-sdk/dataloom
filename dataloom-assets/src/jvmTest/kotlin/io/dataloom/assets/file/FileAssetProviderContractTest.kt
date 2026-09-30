package io.dataloom.assets.file

import io.dataloom.assets.AssetProvider
import io.dataloom.assets.AssetQuota
import io.dataloom.assets.platformDigests
import io.dataloom.assets.testkit.AssetProviderContractKit
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Proves [FileAssetProvider] satisfies the same behavioural contract as
 * [io.dataloom.assets.memory.InMemoryAssetProvider] by running the shared,
 * framework-neutral [AssetProviderContractKit] against it. Each scenario gets
 * a fresh, empty provider backed by its own temporary directory, matching the
 * kit's "fresh provider per scenario" contract.
 *
 * JVM-only (Android consumes this `jvmTest` source set; the module has no
 * separate Android target or Android-specific storage path for this
 * provider, so this run *is* the Android-applicable verification).
 */
class FileAssetProviderContractTest {

    private fun kit(): AssetProviderContractKit =
        AssetProviderContractKit(platformDigests()) { quota: AssetQuota ->
            val base = Files.createTempDirectory("file-asset-provider-contract")
            FileAssetProvider(base, platformDigests(), quota) as AssetProvider
        }

    @Test
    fun `the file-backed provider passes the full provider contract kit`() = runTest {
        val report = kit().run()
        report.assertAllPassed()
        assertTrue(report.results.size >= 20, "kit should exercise at least 20 scenarios, ran ${report.results.size}")
    }
}
