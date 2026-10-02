package io.dataloom.plugin.reference

import io.dataloom.api.identifier.RuntimeVersion
import io.dataloom.api.plugin.DataLoomPlugin
import io.dataloom.api.plugin.PluginExecutionBounds
import io.dataloom.api.plugin.PluginManifest
import io.dataloom.plugin.testkit.PluginCertificationKit
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Certifies [DiagnosticsCounterPlugin] -- `#98`'s reference non-provider
 * plugin -- against the real [PluginCertificationKit], as its first genuine,
 * non-fixture consumer. `PluginCertificationKitTest` already proves the kit
 * against synthetic fakes written only for its own test suite; this test
 * proves it against a real, documented, independently useful
 * [DataLoomPlugin] implementation instead.
 */
class DiagnosticsCounterPluginCertificationTest {

    private fun kit(
        sdkVersion: RuntimeVersion = RuntimeVersion("1.0.0"),
        pluginFactory: (PluginManifest, PluginExecutionBounds) -> DataLoomPlugin =
            { manifest, bounds -> DiagnosticsCounterPlugin(manifest, bounds) },
    ): PluginCertificationKit = PluginCertificationKit(
        sdkVersion = sdkVersion,
        pluginFactory = pluginFactory,
    )

    @Test
    fun `the reference diagnostics counter plugin passes the full certification kit`() = runTest {
        val report = kit().run()

        report.assertAllPassed()
        assertTrue(report.results.size >= 14, "kit should exercise at least 14 scenarios, ran ${report.results.size}")
    }

    // -------------------------------------------------------------------------
    // Revert-and-observe, applied to a real plugin rather than a synthetic
    // fake: a wrapper that drops this plugin's own declared permissions is
    // caught by the exact same permission-gated-activation scenario
    // `PluginCertificationKitTest` already proves the kit enforces.
    // -------------------------------------------------------------------------

    /**
     * Broken wrapper around [DiagnosticsCounterPlugin]: silently drops every
     * declared permission from its own manifest, simulating a plugin wrapper
     * that forgets to forward its own declared permission requests.
     */
    private class PermissionBlindDiagnosticsCounterPlugin(
        manifest: PluginManifest,
        override val executionBounds: PluginExecutionBounds,
    ) : DataLoomPlugin {
        override val manifest: PluginManifest = PluginManifest(
            id = manifest.id,
            version = manifest.version,
            vendor = manifest.vendor,
            compatibleSdkRange = manifest.compatibleSdkRange,
            capabilities = manifest.capabilities,
            permissions = emptySet(),
            dependencies = manifest.dependencies,
        )
    }

    @Test
    fun `the certification kit catches a wrapper that drops this plugin's own declared permissions`() = runTest {
        val report = kit(
            pluginFactory = { manifest, bounds -> PermissionBlindDiagnosticsCounterPlugin(manifest, bounds) },
        ).run()

        assertTrue(
            report.failures.any { "missing declared permission" in it.name },
            "expected the missing-permission scenario to fail, failures: ${report.failures.map { it.name }}",
        )
    }
}
