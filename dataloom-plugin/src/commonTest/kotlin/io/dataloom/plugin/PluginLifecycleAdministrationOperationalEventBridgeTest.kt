package io.dataloom.plugin

import io.dataloom.api.identifier.RuntimeVersion
import io.dataloom.api.operational.OperationalEventCategory
import io.dataloom.api.plugin.PluginCompatibilityRange
import io.dataloom.api.plugin.PluginId
import io.dataloom.api.plugin.PluginLifecycleState
import io.dataloom.api.plugin.PluginPermission
import io.dataloom.api.time.DataLoomInstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Verifies [PluginLifecycleAdministrationOperationalEventBridge.toEnvelope]
 * maps every [PluginLifecycleTransitionResult] variant to a correctly
 * classified, identity-stable [io.dataloom.api.operational.OperationalEventEnvelope].
 */
class PluginLifecycleAdministrationOperationalEventBridgeTest {

    private val request = PluginLifecycleTransitionRequest(
        commandId = PluginLifecycleAdministrationCommandId("cmd-42"),
        pluginId = PluginId("acme.sync"),
        target = PluginLifecycleState.DISABLED,
        principalId = PluginLifecycleAdministrationPrincipalId("operator-7"),
        requestedAt = DataLoomInstant(5_000L),
        reason = PluginLifecycleAdministrationReason("secret rollout rollback context"),
    )

    @Test
    fun `envelope id is derived from the command id and never freshly generated`() {
        val envelope = PluginLifecycleAdministrationOperationalEventBridge.toEnvelope(
            request,
            PluginLifecycleTransitionResult.Allowed(PluginLifecycleState.ACTIVE, PluginLifecycleState.DISABLED),
        )

        assertEquals("plugin.lifecycle.cmd-42", envelope.id.value)
    }

    @Test
    fun `correlation id reuses the command id unchanged`() {
        val envelope = PluginLifecycleAdministrationOperationalEventBridge.toEnvelope(
            request,
            PluginLifecycleTransitionResult.Allowed(PluginLifecycleState.ACTIVE, PluginLifecycleState.DISABLED),
        )

        assertEquals("cmd-42", envelope.correlationId.value)
    }

    @Test
    fun `occurredAt is the request's requestedAt and never a fresh clock read`() {
        val envelope = PluginLifecycleAdministrationOperationalEventBridge.toEnvelope(
            request,
            PluginLifecycleTransitionResult.Allowed(PluginLifecycleState.ACTIVE, PluginLifecycleState.DISABLED),
        )

        assertEquals(5_000L, envelope.occurredAt.epochMilliseconds)
    }

    @Test
    fun `category is AUDIT for every result variant`() {
        val results = listOf(
            PluginLifecycleTransitionResult.Allowed(PluginLifecycleState.ACTIVE, PluginLifecycleState.DISABLED),
            PluginLifecycleTransitionResult.Rejected(PluginLifecycleState.DISABLED, PluginLifecycleState.ACTIVE, "nope"),
            PluginLifecycleTransitionResult.PermissionDenied(
                PluginLifecycleState.INITIALIZING,
                PluginLifecycleState.ACTIVE,
                setOf(PluginPermission("storage.read")),
            ),
            PluginLifecycleTransitionResult.AuthorizationDenied(
                PluginLifecycleState.ACTIVE,
                PluginLifecycleState.DISABLED,
                "NOT_AN_OPERATOR",
            ),
            incompatibleRuntime,
        )

        results.forEach { result ->
            val envelope = PluginLifecycleAdministrationOperationalEventBridge.toEnvelope(request, result)
            assertEquals(OperationalEventCategory.AUDIT, envelope.category)
        }
    }

    private val incompatibleRuntime = PluginLifecycleTransitionResult.IncompatibleRuntime(
        from = PluginLifecycleState.LOADED,
        to = PluginLifecycleState.VALIDATED,
        incompatibility = PluginCompatibilityResult.Incompatible(
            sdkVersion = RuntimeVersion("1.0.0"),
            range = PluginCompatibilityRange(
                minimumSdkVersion = RuntimeVersion("2.0.0"),
                maximumSdkVersion = RuntimeVersion("3.0.0"),
            ),
            reason = PluginIncompatibilityReason.BELOW_MINIMUM,
        ),
    )

    @Test
    fun `IncompatibleRuntime is bridged as an AUDIT event with its own type`() {
        val envelope = PluginLifecycleAdministrationOperationalEventBridge.toEnvelope(request, incompatibleRuntime)

        assertEquals(OperationalEventCategory.AUDIT, envelope.category)
        assertEquals("dataloom.plugin.lifecycle.administration.incompatible_runtime", envelope.type.value)
    }

    @Test
    fun `IncompatibleRuntime exposes the SDK version and reason but redacts plugin-declared bounds`() {
        val envelope = PluginLifecycleAdministrationOperationalEventBridge.toEnvelope(request, incompatibleRuntime)

        assertEquals("LOADED", envelope.attributes["result.from"])
        assertEquals("VALIDATED", envelope.attributes["result.to"])
        assertEquals("BELOW_MINIMUM", envelope.attributes["result.incompatibilityReason"])
        assertEquals("1.0.0", envelope.attributes["result.sdkVersion"])
        assertEquals("[REDACTED]", envelope.attributes["result.minimumSdkVersion"])
        assertEquals("[REDACTED]", envelope.attributes["result.maximumSdkVersion"])
    }

    @Test
    fun `IncompatibleRuntime with no maximum omits the maximum attribute`() {
        val noMaximum = PluginLifecycleTransitionResult.IncompatibleRuntime(
            from = PluginLifecycleState.LOADED,
            to = PluginLifecycleState.VALIDATED,
            incompatibility = incompatibleRuntime.incompatibility.copy(
                range = PluginCompatibilityRange(minimumSdkVersion = RuntimeVersion("2.0.0")),
            ),
        )

        val envelope = PluginLifecycleAdministrationOperationalEventBridge.toEnvelope(request, noMaximum)

        assertNull(envelope.attributes["result.maximumSdkVersion"])
    }

    @Test
    fun `event type differs per result variant`() {
        assertEquals(
            "dataloom.plugin.lifecycle.administration.allowed",
            PluginLifecycleAdministrationOperationalEventBridge.toEnvelope(
                request,
                PluginLifecycleTransitionResult.Allowed(PluginLifecycleState.ACTIVE, PluginLifecycleState.DISABLED),
            ).type.value,
        )
        assertEquals(
            "dataloom.plugin.lifecycle.administration.rejected",
            PluginLifecycleAdministrationOperationalEventBridge.toEnvelope(
                request,
                PluginLifecycleTransitionResult.Rejected(
                    PluginLifecycleState.DISABLED,
                    PluginLifecycleState.ACTIVE,
                    "nope",
                ),
            ).type.value,
        )
        assertEquals(
            "dataloom.plugin.lifecycle.administration.permission_denied",
            PluginLifecycleAdministrationOperationalEventBridge.toEnvelope(
                request,
                PluginLifecycleTransitionResult.PermissionDenied(
                    PluginLifecycleState.INITIALIZING,
                    PluginLifecycleState.ACTIVE,
                    setOf(PluginPermission("storage.read")),
                ),
            ).type.value,
        )
        assertEquals(
            "dataloom.plugin.lifecycle.administration.authorization_denied",
            PluginLifecycleAdministrationOperationalEventBridge.toEnvelope(
                request,
                PluginLifecycleTransitionResult.AuthorizationDenied(
                    PluginLifecycleState.ACTIVE,
                    PluginLifecycleState.DISABLED,
                    "NOT_AN_OPERATOR",
                ),
            ).type.value,
        )
    }

    @Test
    fun `PUBLIC enum-name attributes pass through unredacted`() {
        val envelope = PluginLifecycleAdministrationOperationalEventBridge.toEnvelope(
            request,
            PluginLifecycleTransitionResult.Allowed(PluginLifecycleState.ACTIVE, PluginLifecycleState.DISABLED),
        )

        assertEquals("DISABLED", envelope.attributes["request.target"])
        assertEquals("ACTIVE", envelope.attributes["result.from"])
        assertEquals("DISABLED", envelope.attributes["result.to"])
    }

    @Test
    fun `INTERNAL identifier attributes are masked and never disclosed in the clear`() {
        val envelope = PluginLifecycleAdministrationOperationalEventBridge.toEnvelope(
            request,
            PluginLifecycleTransitionResult.AuthorizationDenied(
                PluginLifecycleState.ACTIVE,
                PluginLifecycleState.DISABLED,
                "NOT_AN_OPERATOR",
            ),
        )

        assertEquals("[REDACTED]", envelope.attributes["request.pluginId"])
        assertEquals("[REDACTED]", envelope.attributes["request.principalId"])
        assertEquals("[REDACTED]", envelope.attributes["result.reasonCode"])
    }

    @Test
    fun `the caller-supplied free-text reason is never included as an attribute`() {
        val envelope = PluginLifecycleAdministrationOperationalEventBridge.toEnvelope(
            request,
            PluginLifecycleTransitionResult.Allowed(PluginLifecycleState.ACTIVE, PluginLifecycleState.DISABLED),
        )

        assertNull(envelope.attributes["request.reason"])
        assertNull(envelope.attributes["reason"])
    }

    @Test
    fun `PermissionDenied missing permissions are included but masked`() {
        val envelope = PluginLifecycleAdministrationOperationalEventBridge.toEnvelope(
            request,
            PluginLifecycleTransitionResult.PermissionDenied(
                PluginLifecycleState.INITIALIZING,
                PluginLifecycleState.ACTIVE,
                setOf(PluginPermission("storage.read")),
            ),
        )

        assertEquals("[REDACTED]", envelope.attributes["result.missingPermissions"])
    }

    @Test
    fun `two different transition requests never collide on envelope id`() {
        val other = request.copy(commandId = PluginLifecycleAdministrationCommandId("cmd-43"))

        val first = PluginLifecycleAdministrationOperationalEventBridge.toEnvelope(
            request,
            PluginLifecycleTransitionResult.Allowed(PluginLifecycleState.ACTIVE, PluginLifecycleState.DISABLED),
        )
        val second = PluginLifecycleAdministrationOperationalEventBridge.toEnvelope(
            other,
            PluginLifecycleTransitionResult.Allowed(PluginLifecycleState.ACTIVE, PluginLifecycleState.DISABLED),
        )

        assertEquals("plugin.lifecycle.cmd-42", first.id.value)
        assertEquals("plugin.lifecycle.cmd-43", second.id.value)
    }
}
