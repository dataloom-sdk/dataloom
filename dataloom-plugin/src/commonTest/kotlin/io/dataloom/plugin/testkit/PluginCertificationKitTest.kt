package io.dataloom.plugin.testkit

import io.dataloom.api.identifier.RuntimeVersion
import io.dataloom.api.plugin.DataLoomPlugin
import io.dataloom.api.plugin.PluginCompatibilityRange
import io.dataloom.api.plugin.PluginExecutionBounds
import io.dataloom.api.plugin.PluginManifest
import io.dataloom.plugin.PluginLifecycleAdministrationAuthorizationDecision
import io.dataloom.plugin.PluginLifecycleAdministrationAuthorizer
import io.dataloom.plugin.PluginLifecycleTransitionRequest
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Verifies [PluginCertificationKit] itself: that a conforming
 * [DataLoomPlugin] implementation passes the full suite, and -- the
 * "revert-and-observe" proof for a reusable test kit -- that a deliberately
 * broken implementation of each certified extension point makes the kit's
 * own assertions fail, so the kit is proven to actually catch violations
 * rather than passing vacuously.
 */
class PluginCertificationKitTest {

    /** A plain, conforming [DataLoomPlugin]: exposes exactly the manifest and bounds it was given. */
    private class ConformingPlugin(
        override val manifest: PluginManifest,
        override val executionBounds: PluginExecutionBounds,
    ) : DataLoomPlugin

    private fun kit(
        pluginFactory: (PluginManifest, PluginExecutionBounds) -> DataLoomPlugin = { m, b -> ConformingPlugin(m, b) },
        authorizedAuthorizerFactory: () -> PluginLifecycleAdministrationAuthorizer = { DefaultAuthorize },
        deniedAuthorizerFactory: (String) -> PluginLifecycleAdministrationAuthorizer = { code -> DefaultDeny(code) },
    ): PluginCertificationKit = PluginCertificationKit(
        sdkVersion = RuntimeVersion("1.0.0"),
        pluginFactory = pluginFactory,
        authorizedAuthorizerFactory = authorizedAuthorizerFactory,
        deniedAuthorizerFactory = deniedAuthorizerFactory,
    )

    private object DefaultAuthorize : PluginLifecycleAdministrationAuthorizer {
        override suspend fun authorize(
            request: PluginLifecycleTransitionRequest,
        ): PluginLifecycleAdministrationAuthorizationDecision = PluginLifecycleAdministrationAuthorizationDecision.Authorized
    }

    private class DefaultDeny(private val reasonCode: String) : PluginLifecycleAdministrationAuthorizer {
        override suspend fun authorize(
            request: PluginLifecycleTransitionRequest,
        ): PluginLifecycleAdministrationAuthorizationDecision =
            PluginLifecycleAdministrationAuthorizationDecision.Denied(reasonCode)
    }

    // -------------------------------------------------------------------------
    // A conforming implementation passes
    // -------------------------------------------------------------------------

    @Test
    fun `a conforming plugin implementation passes the full certification kit`() = runTest {
        val report = kit().run()

        report.assertAllPassed()
        assertTrue(report.results.size >= 14, "kit should exercise at least 14 scenarios, ran ${report.results.size}")
    }

    @Test
    fun `a scenario failure is reported not thrown and every other scenario still runs`() = runTest {
        val report = kit(
            pluginFactory = { m, b -> IncompatibleSdkPlugin(m, b) },
        ).run()

        assertTrue(report.results.size >= 14)
        assertTrue(report.failures.isNotEmpty())
        // Every scenario ran to completion despite the earlier failures.
        assertTrue(report.results.size == report.results.count { it.passed } + report.failures.size)
    }

    // -------------------------------------------------------------------------
    // Broken fakes: lifecycle category
    // -------------------------------------------------------------------------

    /**
     * Broken plugin implementation: ignores the SDK compatibility range it is
     * constructed with and always substitutes one that excludes the kit's
     * configured SDK version -- simulating a plugin wrapper that corrupts its
     * own declared manifest.
     */
    private class IncompatibleSdkPlugin(
        manifest: PluginManifest,
        override val executionBounds: PluginExecutionBounds,
    ) : DataLoomPlugin {
        override val manifest: PluginManifest = PluginManifest(
            id = manifest.id,
            version = manifest.version,
            vendor = manifest.vendor,
            compatibleSdkRange = PluginCompatibilityRange(minimumSdkVersion = RuntimeVersion("999.0.0")),
            capabilities = manifest.capabilities,
            permissions = manifest.permissions,
            dependencies = manifest.dependencies,
        )
    }

    @Test
    fun `the kit catches a plugin implementation that corrupts its own declared SDK compatibility range`() = runTest {
        val report = kit(pluginFactory = { m, b -> IncompatibleSdkPlugin(m, b) }).run()

        assertTrue(
            report.failures.any { "legal transition" in it.name },
            "expected a lifecycle-transition scenario to fail, failures: ${report.failures.map { it.name }}",
        )
    }

    // -------------------------------------------------------------------------
    // Broken fakes: dependency category
    // -------------------------------------------------------------------------

    /**
     * Broken plugin implementation: silently drops every declared dependency,
     * so the engine's dependency gate never sees them -- simulating a plugin
     * wrapper that forgets to forward its own declared dependency edges.
     */
    private class DependencyBlindPlugin(
        manifest: PluginManifest,
        override val executionBounds: PluginExecutionBounds,
    ) : DataLoomPlugin {
        override val manifest: PluginManifest = PluginManifest(
            id = manifest.id,
            version = manifest.version,
            vendor = manifest.vendor,
            compatibleSdkRange = manifest.compatibleSdkRange,
            capabilities = manifest.capabilities,
            permissions = manifest.permissions,
            dependencies = emptySet(),
        )
    }

    @Test
    fun `the kit catches a plugin implementation that drops its own declared dependencies`() = runTest {
        val report = kit(pluginFactory = { m, b -> DependencyBlindPlugin(m, b) }).run()

        assertTrue(
            report.failures.any { "dependency" in it.name },
            "expected a dependency-gating scenario to fail, failures: ${report.failures.map { it.name }}",
        )
    }

    // -------------------------------------------------------------------------
    // Broken fakes: execution-bounds category
    // -------------------------------------------------------------------------

    /**
     * Broken plugin implementation: ignores the execution bounds it is
     * constructed with and always substitutes a far larger concurrency
     * ceiling and timeout -- simulating a plugin wrapper that does not
     * honour the bounds the host configured it with.
     */
    private class BoundsIgnoringPlugin(
        override val manifest: PluginManifest,
        @Suppress("UNUSED_PARAMETER") bounds: PluginExecutionBounds,
    ) : DataLoomPlugin {
        override val executionBounds: PluginExecutionBounds =
            PluginExecutionBounds(maximumExecutionMillis = 60_000L, maximumConcurrentInvocations = 1_000)
    }

    @Test
    fun `the kit catches a plugin implementation that ignores its own declared execution bounds`() = runTest {
        val report = kit(pluginFactory = { m, b -> BoundsIgnoringPlugin(m, b) }).run()

        assertTrue(
            report.failures.any { "bulkhead" in it.name },
            "expected the concurrency-ceiling scenario to fail, failures: ${report.failures.map { it.name }}",
        )
    }

    // -------------------------------------------------------------------------
    // Broken fakes: authorized-transition gating category
    // -------------------------------------------------------------------------

    /**
     * Broken authorizer implementation: authorizes every request regardless
     * of policy -- simulating a host that stubbed out its authorizer and
     * never implemented real denial logic, supplied here where the kit
     * expects a *denying* authorizer.
     */
    private object AlwaysAuthorizeEvenWhenMeantToDeny : PluginLifecycleAdministrationAuthorizer {
        override suspend fun authorize(
            request: PluginLifecycleTransitionRequest,
        ): PluginLifecycleAdministrationAuthorizationDecision = PluginLifecycleAdministrationAuthorizationDecision.Authorized
    }

    @Test
    fun `the kit catches a broken authorizer that authorizes every request even when it should deny`() = runTest {
        val report = kit(deniedAuthorizerFactory = { AlwaysAuthorizeEvenWhenMeantToDeny }).run()

        assertTrue(
            report.failures.any { "denied request" in it.name },
            "expected the denied-request scenario to fail, failures: ${report.failures.map { it.name }}",
        )
    }
}
