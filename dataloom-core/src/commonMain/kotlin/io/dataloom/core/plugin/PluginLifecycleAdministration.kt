package io.dataloom.core.plugin

import io.dataloom.api.plugin.PluginId
import io.dataloom.api.plugin.PluginLifecycleState
import io.dataloom.api.time.DataLoomInstant
import kotlin.jvm.JvmInline

/**
 * Stable idempotency/correlation key for one authorized plugin
 * lifecycle-transition request.
 *
 * Mirrors [io.dataloom.api.retry.RetryAdministrationCommandId]/
 * [io.dataloom.api.circuit.CircuitAdministrationCommandId]/
 * [io.dataloom.api.conflict.ConflictAdministrationCommandId] in shape and
 * validation. Unlike those types, nothing in this module's own
 * [PluginLifecycleStateTracker] uses this value for compare-and-set replay
 * detection today -- the tracker is deliberately non-durable, in-memory, and
 * caller-serialized (see its own "Thread-safety boundary" documentation), so
 * there is no durable command store for a duplicate [PluginLifecycleAdministrationCommandId]
 * to be detected against. This value exists so a caller and
 * [PluginLifecycleAdministrationOperationalEventBridge] share one stable
 * correlation identity for one logical request, the same reason
 * [io.dataloom.api.operational.OperationalEventEnvelope.id] must always be
 * derived from something already unique rather than freshly generated.
 */
@JvmInline
public value class PluginLifecycleAdministrationCommandId(
    public val value: String,
) {
    init {
        require(value.isNotBlank()) { "PluginLifecycleAdministrationCommandId must not be blank." }
    }

    override fun toString(): String = value
}

/** Stable identifier for the principal requesting a plugin lifecycle transition. */
@JvmInline
public value class PluginLifecycleAdministrationPrincipalId(
    public val value: String,
) {
    init {
        require(value.isNotBlank()) { "PluginLifecycleAdministrationPrincipalId must not be blank." }
    }

    override fun toString(): String = value
}

/** Bounded sanitized reason for an authorized plugin lifecycle-transition request. */
@JvmInline
public value class PluginLifecycleAdministrationReason(
    public val value: String,
) {
    init {
        require(value.isNotBlank()) { "PluginLifecycleAdministrationReason must not be blank." }
        require(value.length <= MAXIMUM_LENGTH) {
            "PluginLifecycleAdministrationReason must not exceed $MAXIMUM_LENGTH characters."
        }
    }

    override fun toString(): String = value

    private companion object {
        const val MAXIMUM_LENGTH: Int = 512
    }
}

/**
 * Immutable request to transition [pluginId]'s tracked
 * [PluginLifecycleState] to [target], on behalf of [principalId].
 *
 * This is the *caller's* request to invoke
 * [PluginLifecycleStateTracker.transition] -- distinct from what that
 * transition itself checks. [PluginLifecycleTransitions] already decides
 * whether `from -> target` is structurally legal, and
 * [PluginLifecycleStateTracker]'s capability-aware `transition` overload
 * already decides whether the *plugin's own* declared
 * [io.dataloom.api.plugin.PluginPermission]s are held when entering
 * [PluginLifecycleState.ACTIVE]. Neither of those checks has any notion of
 * who is asking -- this type is what supplies that notion to
 * [PluginLifecycleAdministrationAuthorizer].
 */
public data class PluginLifecycleTransitionRequest(
    public val commandId: PluginLifecycleAdministrationCommandId,
    public val pluginId: PluginId,
    public val target: PluginLifecycleState,
    public val principalId: PluginLifecycleAdministrationPrincipalId,
    public val requestedAt: DataLoomInstant,
    public val reason: PluginLifecycleAdministrationReason,
)

/** Authorization result for a requested plugin lifecycle transition. */
public sealed interface PluginLifecycleAdministrationAuthorizationDecision {
    public data object Authorized : PluginLifecycleAdministrationAuthorizationDecision

    public data class Denied(
        public val reasonCode: String,
    ) : PluginLifecycleAdministrationAuthorizationDecision {
        init {
            require(reasonCode.isNotBlank()) {
                "PluginLifecycleAdministrationAuthorizationDecision.Denied reasonCode must not be blank."
            }
            require(reasonCode.length <= MAX_REASON_CODE_LENGTH) {
                "PluginLifecycleAdministrationAuthorizationDecision.Denied reasonCode must not exceed " +
                    "$MAX_REASON_CODE_LENGTH characters."
            }
        }

        private companion object {
            const val MAX_REASON_CODE_LENGTH: Int = 128
        }
    }
}

/**
 * Host-owned authorization boundary for *who* may request a plugin
 * lifecycle-state transition -- `#98`'s "authorized hot disable" acceptance
 * criterion.
 *
 * ## Why this applies to any transition, not only entry into `DISABLED`
 *
 * "Hot disable" is the motivating case named in `#98`'s own acceptance
 * criteria, but nothing about *who may command a lifecycle transition* is
 * specific to the `DISABLED` target. The directly analogous precedent this
 * type mirrors --
 * [io.dataloom.api.circuit.CircuitAdministrationAuthorizer] -- authorizes
 * every [io.dataloom.api.circuit.CircuitAdministrationAction] (`OPEN`,
 * `CLOSE`, `RESET`) through one uniform boundary rather than singling out
 * one privileged action; a caller forcing an `ACTIVE` plugin into
 * `DEGRADED`, or recovering a `DEGRADED` plugin back to `ACTIVE`, is exactly
 * as privileged an operation as disabling it outright. Scoping this
 * authorizer to `DISABLED` alone would have meant inventing an inconsistent
 * boundary -- protected for one target, wide open for every other
 * transition -- for no reason connected to the actual security question
 * ("who may drive this plugin's lifecycle"), so this type is written to
 * gate [PluginLifecycleStateTracker.transition] generally.
 *
 * ## Deny-by-default, no invented identity system
 *
 * Exactly like [io.dataloom.api.retry.RetryAdministrationAuthorizer]/
 * [io.dataloom.api.circuit.CircuitAdministrationAuthorizer]/
 * [io.dataloom.api.conflict.ConflictAdministrationAuthorizer]: DataLoom
 * invents no identity, role, or permission system of its own here. A host
 * application supplies its own implementation backed by whatever
 * authentication/authorization system it already has; there is no default
 * implementation, and the absence of one is deliberate, not an oversight.
 */
public interface PluginLifecycleAdministrationAuthorizer {
    /**
     * Returns an authorization decision for [request].
     *
     * Implementations must be side-effect free or idempotent by
     * [PluginLifecycleTransitionRequest.commandId]. Cancellation must
     * propagate.
     */
    public suspend fun authorize(
        request: PluginLifecycleTransitionRequest,
    ): PluginLifecycleAdministrationAuthorizationDecision
}
