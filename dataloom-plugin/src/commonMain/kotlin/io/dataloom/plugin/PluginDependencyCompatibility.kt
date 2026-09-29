package io.dataloom.plugin

import io.dataloom.api.plugin.PluginDependency
import io.dataloom.api.plugin.PluginId
import io.dataloom.api.plugin.PluginLifecycleState
import io.dataloom.api.plugin.PluginVersion
import io.dataloom.api.plugin.PluginVersionRange

/**
 * Why one declared dependency of a plugin does not allow that plugin to enter a
 * lifecycle state. A closed vocabulary: no reason carries free text, a plugin
 * identifier, or a version.
 *
 * When one dependency fails several checks, exactly one reason is reported,
 * chosen in the order the entries below are listed: presence, then version,
 * then lifecycle state.
 */
public enum class PluginDependencyIssueReason {
    /** The depended-upon plugin is not registered in the registry. */
    NOT_REGISTERED,

    /** The declared version range has a minimum above its maximum, so no version satisfies it. */
    EMPTY_VERSION_RANGE,

    /** The depended-upon plugin's version has lower precedence than the range's minimum. */
    VERSION_BELOW_MINIMUM,

    /** The depended-upon plugin's version has higher precedence than the range's maximum. */
    VERSION_ABOVE_MAXIMUM,

    /** The depended-upon plugin is [PluginLifecycleState.DISABLED], which is never re-enabled. */
    DISABLED,

    /** The depended-upon plugin is [PluginLifecycleState.UNLOADED]. */
    UNLOADED,

    /**
     * Reported only when the dependent enters [PluginLifecycleState.ACTIVE]: the
     * depended-upon plugin is registered, in range, and not retired, but is
     * `LOADED`, `VALIDATED`, `INITIALIZING`, or `DEGRADED` rather than `ACTIVE`.
     */
    NOT_ACTIVE,
}

/**
 * One declared dependency that blocked a lifecycle transition.
 *
 * @property dependencyId the depended-upon plugin, exactly as the dependent's
 *   manifest declares it (so it may name a plugin that is not registered).
 * @property reason why that dependency blocked the transition.
 */
public data class PluginDependencyIssue(
    public val dependencyId: PluginId,
    public val reason: PluginDependencyIssueReason,
)

/** What is observed about a registered, depended-upon plugin at the moment of a transition. */
internal class ObservedPluginDependency(
    val version: PluginVersion,
    val state: PluginLifecycleState,
)

/**
 * Decides whether one declared dependency allows its dependent to enter
 * [target]. Pure: no registry, no clock, no state.
 *
 * [observed] is `null` when the depended-upon plugin is not registered. Returns
 * `null` when the dependency is satisfied.
 *
 * The version check reuses [rangeViolation], the same inclusive,
 * semver-precedence range logic [PluginCompatibilityValidator] applies to the
 * SDK version.
 *
 * Whether a dependency must be `ACTIVE` depends on [target]: entering
 * `VALIDATED` only needs the dependency to exist, be in range, and not be
 * retired (it may itself still be `LOADED`), so a host may validate every
 * plugin before activating any. Entering `ACTIVE` additionally needs it to be
 * `ACTIVE`.
 */
internal fun unsatisfiedReason(
    range: PluginVersionRange,
    observed: ObservedPluginDependency?,
    target: PluginLifecycleState,
): PluginDependencyIssueReason? {
    if (observed == null) return PluginDependencyIssueReason.NOT_REGISTERED

    val violation = rangeViolation(range.minimum, range.maximum, observed.version) { left, right ->
        left.precedenceCompareTo(right)
    }
    if (violation != null) {
        return when (violation) {
            PluginIncompatibilityReason.EMPTY_RANGE -> PluginDependencyIssueReason.EMPTY_VERSION_RANGE
            PluginIncompatibilityReason.BELOW_MINIMUM -> PluginDependencyIssueReason.VERSION_BELOW_MINIMUM
            PluginIncompatibilityReason.ABOVE_MAXIMUM -> PluginDependencyIssueReason.VERSION_ABOVE_MAXIMUM
        }
    }

    return when (observed.state) {
        PluginLifecycleState.DISABLED -> PluginDependencyIssueReason.DISABLED
        PluginLifecycleState.UNLOADED -> PluginDependencyIssueReason.UNLOADED
        PluginLifecycleState.ACTIVE -> null
        PluginLifecycleState.LOADED,
        PluginLifecycleState.VALIDATED,
        PluginLifecycleState.INITIALIZING,
        PluginLifecycleState.DEGRADED,
        -> if (target == PluginLifecycleState.ACTIVE) PluginDependencyIssueReason.NOT_ACTIVE else null
    }
}

/**
 * Evaluates every dependency a plugin declares against what [registry] and
 * [stateOf] report right now, for a transition into [target].
 *
 * Only `VALIDATED` and `ACTIVE` are gated; every other target (in particular
 * `DISABLED`, so a plugin can always be stopped) returns no issues. Only the
 * plugin's own declared dependencies are checked, not the transitive closure:
 * a dependency can only be `ACTIVE` if its own gate passed when it entered
 * `ACTIVE`, so requiring it to be `ACTIVE` carries the check down the chain.
 * A dependency disabled *after* its dependents activated is not cascaded; see
 * `docs/api/plugin-registry.md`.
 *
 * The result is deterministic: one entry per distinct (dependency, reason),
 * ordered by dependency id and then reason name, independent of the order the
 * manifest declared them in.
 */
internal fun dependencyIssues(
    dependencies: Set<PluginDependency>,
    target: PluginLifecycleState,
    registry: PluginRegistry,
    stateOf: (PluginId) -> PluginLifecycleState,
): List<PluginDependencyIssue> {
    if (target != PluginLifecycleState.VALIDATED && target != PluginLifecycleState.ACTIVE) return emptyList()
    return dependencies
        .mapNotNull { dependency ->
            val observed = registry.findById(dependency.pluginId)?.let { plugin ->
                ObservedPluginDependency(plugin.manifest.version, stateOf(dependency.pluginId))
            }
            unsatisfiedReason(dependency.supportedVersionRange, observed, target)
                ?.let { reason -> PluginDependencyIssue(dependency.pluginId, reason) }
        }
        .distinct()
        .sortedWith(compareBy({ it.dependencyId.value }, { it.reason.name }))
}
