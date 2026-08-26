package io.dataloom.core.plugin

import io.dataloom.api.plugin.PluginLifecycleState

/**
 * Outcome of requesting a [PluginLifecycleState] transition against
 * [PluginLifecycleTransitions].
 */
public sealed class PluginLifecycleTransitionResult {

    /** The requested transition is legal. */
    public data class Allowed(
        public val from: PluginLifecycleState,
        public val to: PluginLifecycleState,
    ) : PluginLifecycleTransitionResult()

    /** The requested transition is not legal from [from]. */
    public data class Rejected(
        public val from: PluginLifecycleState,
        public val to: PluginLifecycleState,
        public val reason: String,
    ) : PluginLifecycleTransitionResult()
}

/**
 * Enforces legal [PluginLifecycleState] transitions for the plugin lifecycle
 * engine (`#98`).
 *
 * ## Purpose
 *
 * [PluginLifecycleState] itself (`dataloom-plugin-api`) documents lifecycle
 * *labels* only and deliberately does not enforce transitions — see its own
 * KDoc. [PluginLifecycleTransitions] is the first piece of `#98`'s own
 * runtime behavior built on top of that contract: a pure, stateless
 * transition-legality function requiring no plugin instance, no manifest,
 * and no other subsystem.
 *
 * ## Transition graph
 *
 * The legal graph mirrors [PluginLifecycleState]'s own documented state
 * order (`LOADED → VALIDATED → INITIALIZING → ACTIVE ⇄ DEGRADED → DISABLED
 * → UNLOADED`), plus explicit failure-escape edges to [PluginLifecycleState.DISABLED]
 * from every pre-[PluginLifecycleState.ACTIVE] state — mirroring
 * `io.dataloom.core.provider.ProviderLifecycleCoordinator`'s own documented
 * exceptional transitions (`INITIALIZING → FAILED`, `SHUTTING_DOWN →
 * FAILED`) for the same reason: validation or initialization can fail, and
 * a failed plugin must land in a definite, inert state rather than an
 * undefined one.
 *
 * ```text
 * LOADED       -> VALIDATED, DISABLED
 * VALIDATED    -> INITIALIZING, DISABLED
 * INITIALIZING -> ACTIVE, DISABLED
 * ACTIVE       -> DEGRADED, DISABLED
 * DEGRADED     -> ACTIVE, DISABLED
 * DISABLED     -> UNLOADED
 * UNLOADED     -> (terminal; no outgoing transitions)
 * ```
 *
 * ## Deliberately out of scope
 *
 * This object answers *whether a transition is structurally legal*. It does
 * not decide:
 *
 * - **Who** may request a transition, or whether that requester is
 *   authorized — `#98`'s "authorized hot disable" acceptance criterion is
 *   still open and is a separate, later concern.
 * - **Re-enablement**: [PluginLifecycleState.DISABLED] has exactly one legal
 *   outgoing edge here (`-> UNLOADED`). Re-activating a disabled plugin
 *   (`DISABLED -> VALIDATED` or similar) is not in [PluginLifecycleState]'s
 *   own documented chain and is not defined by this object; adding it later
 *   requires its own authorization design, not a change to this state
 *   graph alone.
 * - Same-state requests (e.g. `ACTIVE -> ACTIVE`) are rejected, not treated
 *   as an idempotent no-op — this object does not invent an idempotency
 *   policy nobody has specified.
 */
public object PluginLifecycleTransitions {

    private val legalTargets: Map<PluginLifecycleState, Set<PluginLifecycleState>> = mapOf(
        PluginLifecycleState.LOADED to setOf(
            PluginLifecycleState.VALIDATED,
            PluginLifecycleState.DISABLED,
        ),
        PluginLifecycleState.VALIDATED to setOf(
            PluginLifecycleState.INITIALIZING,
            PluginLifecycleState.DISABLED,
        ),
        PluginLifecycleState.INITIALIZING to setOf(
            PluginLifecycleState.ACTIVE,
            PluginLifecycleState.DISABLED,
        ),
        PluginLifecycleState.ACTIVE to setOf(
            PluginLifecycleState.DEGRADED,
            PluginLifecycleState.DISABLED,
        ),
        PluginLifecycleState.DEGRADED to setOf(
            PluginLifecycleState.ACTIVE,
            PluginLifecycleState.DISABLED,
        ),
        PluginLifecycleState.DISABLED to setOf(
            PluginLifecycleState.UNLOADED,
        ),
        PluginLifecycleState.UNLOADED to emptySet(),
    )

    /**
     * Returns `true` when transitioning from [from] to [to] is structurally
     * legal per this object's transition graph.
     */
    public fun isLegal(from: PluginLifecycleState, to: PluginLifecycleState): Boolean =
        legalTargets.getValue(from).contains(to)

    /**
     * Validates a requested transition from [from] to [to].
     *
     * @return [PluginLifecycleTransitionResult.Allowed] when the transition
     *   is legal, or [PluginLifecycleTransitionResult.Rejected] with a
     *   human-readable reason otherwise.
     */
    public fun validate(
        from: PluginLifecycleState,
        to: PluginLifecycleState,
    ): PluginLifecycleTransitionResult =
        if (isLegal(from, to)) {
            PluginLifecycleTransitionResult.Allowed(from, to)
        } else {
            PluginLifecycleTransitionResult.Rejected(
                from = from,
                to = to,
                reason = "Illegal plugin lifecycle transition: $from -> $to is not permitted. " +
                    "Legal targets from $from are: ${legalTargets.getValue(from).ifEmpty { setOf("<terminal>") }}.",
            )
        }
}
