package io.dataloom.core.plugin

import io.dataloom.api.plugin.PluginId
import io.dataloom.api.plugin.PluginLifecycleState
import io.dataloom.api.security.GrantedCapabilities
import io.dataloom.api.security.isAuthorized

/**
 * Tracks each plugin registered in a [PluginRegistry] through its
 * [PluginLifecycleState], enforcing [PluginLifecycleTransitions]'s legality
 * rules on every requested transition.
 *
 * ## Deny-by-default
 *
 * Every plugin registered in [registry] starts tracked at
 * [PluginLifecycleState.LOADED] — "the plugin's manifest has been
 * discovered or registered but not yet validated," per
 * [PluginLifecycleState]'s own KDoc. No plugin is ever implicitly granted
 * [PluginLifecycleState.ACTIVE] (or any state past `LOADED`) by
 * registration alone: reaching `ACTIVE` requires an explicit, individually
 * legal `LOADED -> VALIDATED -> INITIALIZING -> ACTIVE` sequence of
 * [transition] calls. This is the deny-by-default registration/enablement
 * behavior `#98`'s own acceptance criteria require and
 * `dataloom-plugin-api`'s contract types deliberately do not implement
 * themselves.
 *
 * ## What this does not do
 *
 * [PluginLifecycleStateTracker] enforces transition *legality* only via its
 * two-argument [transition] overload. Its three-argument, capability-aware
 * [transition] overload additionally enforces that a plugin's declared
 * [io.dataloom.api.plugin.PluginPermission]s are held by a caller-supplied
 * [GrantedCapabilities] before allowing a transition *into*
 * [PluginLifecycleState.ACTIVE] — see that overload's own KDoc. Its
 * authorizer-aware `transition(request, authorizer)` overload separately
 * enforces whether the *caller* requesting a transition is authorized to
 * request it at all — `#98`'s "authorized hot disable" acceptance criterion
 * — see that overload's own KDoc and
 * [PluginLifecycleAdministrationAuthorizer]. None of these overloads perform
 * any actual plugin initialization/shutdown work (there is no
 * [io.dataloom.api.plugin.DataLoomPlugin] lifecycle callback to invoke —
 * those signatures are not yet frozen, per `docs/api/plugin-api.md`), and
 * none write audit records themselves — see
 * [PluginLifecycleAdministrationOperationalEventBridge] for turning a
 * transition request and result into a durable audit record.
 *
 * ## Thread-safety boundary
 *
 * [PluginLifecycleStateTracker] does not provide concurrency control.
 * Callers must serialize [transition] calls per plugin ID; concurrent
 * calls without external coordination produce undefined behavior — the
 * same boundary `io.dataloom.core.provider.ProviderLifecycleCoordinator`
 * documents for itself.
 *
 * @param registry the plugin registry whose registered plugins this tracker
 *   tracks lifecycle state for.
 */
public class PluginLifecycleStateTracker(private val registry: PluginRegistry) {

    private val states: MutableMap<PluginId, PluginLifecycleState> =
        registry.plugins.associate { it.manifest.id to PluginLifecycleState.LOADED }.toMutableMap()

    /**
     * Returns the current tracked [PluginLifecycleState] for [id].
     *
     * @throws IllegalArgumentException if [id] is not registered in
     *   [registry].
     */
    public fun stateOf(id: PluginId): PluginLifecycleState =
        states[id] ?: throw IllegalArgumentException(
            "PluginLifecycleStateTracker: '$id' is not registered in this tracker's registry.",
        )

    /**
     * Requests a transition of [id]'s tracked state to [target].
     *
     * When [PluginLifecycleTransitions.validate] reports the transition as
     * [io.dataloom.core.plugin.PluginLifecycleTransitionResult.Allowed],
     * the tracked state for [id] is updated to [target] and the same
     * result is returned. When it reports
     * [io.dataloom.core.plugin.PluginLifecycleTransitionResult.Rejected],
     * the tracked state is left unchanged and that result is returned —
     * this method never throws for an illegal transition.
     *
     * @throws IllegalArgumentException if [id] is not registered in
     *   [registry].
     */
    public fun transition(id: PluginId, target: PluginLifecycleState): PluginLifecycleTransitionResult {
        val current = stateOf(id)
        val result = PluginLifecycleTransitions.validate(current, target)
        if (result is PluginLifecycleTransitionResult.Allowed) {
            states[id] = target
        }
        return result
    }

    /**
     * Requests a transition of [id]'s tracked state to [target], additionally
     * enforcing that [grantedCapabilities] holds every capability the
     * plugin's [io.dataloom.api.plugin.PluginManifest.permissions] declares,
     * whenever [target] is [PluginLifecycleState.ACTIVE].
     *
     * ## Why gate on `ACTIVE` specifically
     *
     * [PluginLifecycleState.ACTIVE] is the one state in which a plugin
     * actually executes — every earlier state (`LOADED`, `VALIDATED`,
     * `INITIALIZING`) is preparatory and performs no plugin-owned work per
     * [PluginLifecycleState]'s own KDoc. Gating permission enforcement on
     * entry to `ACTIVE` (covering both `INITIALIZING -> ACTIVE` and the
     * `DEGRADED -> ACTIVE` recovery edge) is therefore the one point that
     * actually protects something, mirroring this same tracker's own
     * deny-by-default posture for lifecycle state itself: a plugin never
     * reaches `ACTIVE` by default, and now it never reaches `ACTIVE` holding
     * less than its full declared permission set by default either.
     *
     * ## Result
     *
     * - If the requested transition is not structurally legal per
     *   [PluginLifecycleTransitions], that [PluginLifecycleTransitionResult.Rejected]
     *   is returned unchanged and no permission check runs — structural
     *   legality is checked first.
     * - If [target] is [PluginLifecycleState.ACTIVE] and [grantedCapabilities]
     *   does not hold every one of the plugin's declared permissions (checked
     *   via [io.dataloom.api.security.isAuthorized], mapping each
     *   [io.dataloom.api.plugin.PluginPermission] onto an
     *   [io.dataloom.api.security.Capability] of the same label via
     *   [asCapability]), [PluginLifecycleTransitionResult.PermissionDenied] is
     *   returned naming exactly the missing permissions, and the tracked
     *   state is left unchanged — the same "reject, don't throw, leave state
     *   alone" posture the two-argument overload already establishes for
     *   structural rejection.
     * - Otherwise the tracked state for [id] is updated to [target] and
     *   [PluginLifecycleTransitionResult.Allowed] is returned.
     *
     * This method never throws for an illegal transition or a denied
     * permission set.
     *
     * @throws IllegalArgumentException if [id] is not registered in
     *   [registry].
     */
    public fun transition(
        id: PluginId,
        target: PluginLifecycleState,
        grantedCapabilities: GrantedCapabilities,
    ): PluginLifecycleTransitionResult {
        val current = stateOf(id)
        val structuralResult = PluginLifecycleTransitions.validate(current, target)
        if (structuralResult !is PluginLifecycleTransitionResult.Allowed) {
            return structuralResult
        }

        if (target == PluginLifecycleState.ACTIVE) {
            val manifest = requireNotNull(registry.findById(id)) {
                "PluginLifecycleStateTracker: '$id' is tracked but not found in its registry."
            }.manifest
            val requestedCapabilities = manifest.permissions.mapTo(mutableSetOf()) { it.asCapability() }
            if (!isAuthorized(requestedCapabilities, grantedCapabilities)) {
                val missingPermissions = manifest.permissions.filterTo(mutableSetOf()) { permission ->
                    !grantedCapabilities.holds(permission.asCapability())
                }
                return PluginLifecycleTransitionResult.PermissionDenied(
                    from = current,
                    to = target,
                    missingPermissions = missingPermissions,
                )
            }
        }

        states[id] = target
        return structuralResult
    }

    /**
     * Requests a transition of [PluginLifecycleTransitionRequest.pluginId]'s
     * tracked state to [PluginLifecycleTransitionRequest.target], additionally
     * requiring [authorizer] to authorize the *caller* making the request —
     * `#98`'s "authorized hot disable" acceptance criterion.
     *
     * ## Order of checks
     *
     * 1. Structural legality is checked first, exactly as the two-argument
     *    overload does: an illegal transition returns
     *    [PluginLifecycleTransitionResult.Rejected] immediately and
     *    [authorizer] is never called. A caller is never asked to authorize a
     *    request this tracker would have rejected anyway.
     * 2. Only once the transition is structurally legal is [authorizer]
     *    consulted. A [PluginLifecycleAdministrationAuthorizationDecision.Denied]
     *    result leaves tracked state unchanged and returns
     *    [PluginLifecycleTransitionResult.AuthorizationDenied] naming the
     *    denial's reason code.
     * 3. Otherwise tracked state is updated to the requested target and
     *    [PluginLifecycleTransitionResult.Allowed] is returned.
     *
     * This method never throws for an illegal transition or a denied
     * authorization — the same "reject, don't throw, leave state alone"
     * posture the other two overloads already establish.
     *
     * ## Relationship to the capability-aware overload
     *
     * This overload does not perform the capability-aware overload's
     * permission check against [PluginLifecycleState.ACTIVE] — the two
     * concerns are orthogonal (who may ask, versus what the plugin itself
     * may do once active) and are deliberately not fused into one overload,
     * mirroring how this page's own documentation already distinguishes
     * them. A caller that needs both protections for entry into `ACTIVE`
     * calls both overloads' checks itself before applying the transition, or
     * a future slice may compose them once a real call site makes the
     * composition concrete.
     *
     * @throws IllegalArgumentException if
     *   [PluginLifecycleTransitionRequest.pluginId] is not registered in
     *   [registry].
     */
    public suspend fun transition(
        request: PluginLifecycleTransitionRequest,
        authorizer: PluginLifecycleAdministrationAuthorizer,
    ): PluginLifecycleTransitionResult {
        val current = stateOf(request.pluginId)
        val structuralResult = PluginLifecycleTransitions.validate(current, request.target)
        if (structuralResult !is PluginLifecycleTransitionResult.Allowed) {
            return structuralResult
        }

        return when (val decision = authorizer.authorize(request)) {
            is PluginLifecycleAdministrationAuthorizationDecision.Denied -> {
                PluginLifecycleTransitionResult.AuthorizationDenied(
                    from = current,
                    to = request.target,
                    reasonCode = decision.reasonCode,
                )
            }
            PluginLifecycleAdministrationAuthorizationDecision.Authorized -> {
                states[request.pluginId] = request.target
                structuralResult
            }
        }
    }
}
