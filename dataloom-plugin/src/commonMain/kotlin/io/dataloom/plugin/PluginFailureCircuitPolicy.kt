package io.dataloom.plugin

/**
 * Opt-in policy under which [PluginExecutionBoundsEnforcer] automatically moves
 * a repeatedly failing plugin from [io.dataloom.api.plugin.PluginLifecycleState.ACTIVE]
 * to [io.dataloom.api.plugin.PluginLifecycleState.DEGRADED], which already
 * refuses new invocations.
 *
 * ## What counts
 *
 * - A failure is a [PluginExecutionBoundsResult.Failed] or a
 *   [PluginExecutionBoundsResult.TimedOut]: both are a fault of the plugin's own
 *   invocation.
 * - [PluginExecutionBoundsResult.ConcurrencyLimitExceeded] and
 *   [PluginExecutionBoundsResult.NotActive] never count: they are back-pressure
 *   and lifecycle gating, the operation never ran, so they say nothing about the
 *   plugin's health. They also do not reset the counter.
 * - A [PluginExecutionBoundsResult.Completed] resets the plugin's consecutive
 *   failure count to zero.
 *
 * "Consecutive" is in the order results are observed by the enforcer; with
 * concurrent invocations of one plugin that order is the order they finish.
 * Failures that finish while the plugin is no longer `ACTIVE` (in-flight work
 * draining after a degradation or a manual disable) are not counted. The
 * counter is lock-free, so a failure racing the very moment of a trip can leave
 * the count off by at most the number of invocations in flight at that moment.
 *
 * ## Recovery
 *
 * There is no automatic recovery. A degraded plugin returns to `ACTIVE` only
 * through the existing authorized, manual lifecycle transition, which also
 * re-runs that transition's permission and dependency checks. The count is
 * reset to zero when the circuit trips, so a recovered plugin starts afresh.
 *
 * @param consecutiveFailureThreshold how many consecutive failures degrade the
 *   plugin. Must be positive. Defaults to [DEFAULT_CONSECUTIVE_FAILURE_THRESHOLD].
 */
public data class PluginFailureCircuitPolicy(
    public val consecutiveFailureThreshold: Int = DEFAULT_CONSECUTIVE_FAILURE_THRESHOLD,
) {
    init {
        require(consecutiveFailureThreshold > 0) {
            "consecutiveFailureThreshold must be positive, but was $consecutiveFailureThreshold."
        }
    }

    public companion object {
        /** Consecutive failures that degrade a plugin when no threshold is chosen. */
        public const val DEFAULT_CONSECUTIVE_FAILURE_THRESHOLD: Int = 5
    }
}
