package io.dataloom.core.plugin

import io.dataloom.api.plugin.DataLoomPlugin
import io.dataloom.api.plugin.PluginId

/**
 * Immutable registry of application-supplied [DataLoomPlugin] instances,
 * with deterministic dependency-respecting resolution ordering.
 *
 * ## Purpose
 *
 * [PluginRegistry] is `#98` (DL-044 plugin platform)'s first structural
 * runtime component built on top of `dataloom-plugin-api`'s contract types.
 * `dataloom-plugin-api`'s own `build.gradle.kts` and
 * `docs/api/plugin-api.md` are explicit that it "intentionally contains no
 * plugin loading, registration, enforcement, isolation, or certification
 * behavior — that engine is `#98`... job, built on top of these contracts."
 * [PluginRegistry] is exactly that: it accepts declared [PluginManifest]
 * dependency edges and turns them into a validated, deterministic
 * dependency graph, mirroring
 * `io.dataloom.core.provider.ProviderRegistry`'s own role for providers.
 *
 * ## Duplicate PluginId rejection
 *
 * Each [PluginId] must be unique within one registry. Construction throws
 * [IllegalArgumentException] when duplicate [PluginId] values are detected,
 * mirroring `ProviderRegistry`'s own duplicate-id rule.
 *
 * ## Dependency resolution
 *
 * Every [io.dataloom.api.plugin.PluginDependency] declared in a registered
 * plugin's manifest must reference another [PluginId] present in this same
 * registry. Construction throws [IllegalArgumentException] naming the
 * missing dependency when it does not — an unresolved dependency is
 * rejected outright rather than silently ignored, consistent with this
 * project's deny-by-default discipline.
 *
 * [resolutionOrder] is a deterministic topological ordering of every
 * registered [PluginId] such that each plugin appears after all of the
 * plugins it declares a dependency on. Ties (plugins with no dependency
 * relationship to each other) are broken by registration order, the same
 * determinism rule `ProviderRegistry` applies to its own `providers` list.
 *
 * ## Dependency cycle rejection
 *
 * A dependency cycle (including a plugin depending on itself, directly or
 * transitively) is rejected at construction with
 * [IllegalArgumentException] naming the full cycle path. No partial
 * [resolutionOrder] is ever exposed for a registry containing a cycle.
 *
 * ## What this does not do
 *
 * - **Compatibility-range comparison.** A [io.dataloom.api.plugin.PluginDependency]'s
 *   declared [io.dataloom.api.plugin.PluginCompatibilityRange] is not
 *   parsed or compared against the depended-upon plugin's actual
 *   [io.dataloom.api.plugin.PluginManifest.version] here.
 *   `io.dataloom.api.identifier.RuntimeVersion` (`dataloom-model`) is a plain
 *   non-blank string with no guaranteed semantic-version shape across this
 *   codebase's existing call sites (`"1.0.0"`, `"runtime-1.0.0"`,
 *   `"1.2.3"` all appear), so a real comparison requires a canonical
 *   parseable version format decision this registry does not make
 *   unilaterally. This registry validates the dependency *graph shape*
 *   only.
 * - **Lifecycle state.** Registering a plugin here does not grant it any
 *   [io.dataloom.api.plugin.PluginLifecycleState]. Use
 *   [PluginLifecycleStateTracker] to track and transition each registered
 *   plugin's lifecycle state; every plugin starts untracked until a
 *   tracker is created for this registry.
 * - **Permission enforcement, execution-bounds enforcement, hook-point
 *   dispatch, audit recording, or certification.** All remain open `#98`
 *   work items with their own unresolved design surface — see
 *   `docs/api/plugin-platform-first-slice-investigation.md`.
 *
 * ## No global state
 *
 * [PluginRegistry] does not use process-wide singletons, companion-object
 * mutable fields, service locators, or reflection.
 *
 * @param plugins ordered list of [DataLoomPlugin] instances to register.
 * @throws IllegalArgumentException if [plugins] contains duplicate
 *   [PluginId] values, an unresolved dependency, or a dependency cycle.
 */
public class PluginRegistry(plugins: List<DataLoomPlugin>) {

    private val pluginList: List<DataLoomPlugin> = plugins.toList()
    private val byId: Map<PluginId, DataLoomPlugin>

    /**
     * Deterministic dependency-respecting resolution order: every
     * [PluginId] appears after every [PluginId] it (directly or
     * transitively) depends on. Ties are broken by registration order.
     */
    public val resolutionOrder: List<PluginId>

    init {
        val seen = mutableSetOf<PluginId>()
        val duplicates = pluginList.mapNotNull { plugin ->
            val id = plugin.manifest.id
            if (!seen.add(id)) id else null
        }
        require(duplicates.isEmpty()) {
            "PluginRegistry: duplicate PluginId values detected: ${duplicates.joinToString()}"
        }

        byId = pluginList.associateBy { it.manifest.id }

        for (plugin in pluginList) {
            for (dependency in plugin.manifest.dependencies) {
                require(byId.containsKey(dependency.pluginId)) {
                    "PluginRegistry: plugin '${plugin.manifest.id}' declares a dependency on " +
                        "'${dependency.pluginId}', which is not registered in this registry."
                }
            }
        }

        resolutionOrder = computeResolutionOrder()
    }

    /** All registered plugins, in registration order. */
    public val plugins: List<DataLoomPlugin>
        get() = pluginList

    /** Number of registered plugins. */
    public val size: Int
        get() = pluginList.size

    /** Returns `true` when no plugins are registered. */
    public fun isEmpty(): Boolean = pluginList.isEmpty()

    /**
     * Returns the [DataLoomPlugin] with the given [id], or `null` when no
     * plugin with that ID is registered.
     */
    public fun findById(id: PluginId): DataLoomPlugin? = byId[id]

    /**
     * Depth-first topological sort over the declared dependency graph,
     * iterating registered plugins in registration order for determinism.
     * Throws [IllegalArgumentException] naming the full cycle path if a
     * cycle is found.
     */
    private fun computeResolutionOrder(): List<PluginId> {
        val result = mutableListOf<PluginId>()
        val visited = mutableSetOf<PluginId>()
        val onPath = mutableSetOf<PluginId>()
        val path = mutableListOf<PluginId>()

        fun visit(id: PluginId) {
            if (id in visited) return
            require(id !in onPath) {
                val cycleStart = path.indexOf(id)
                val cycle = (path.subList(cycleStart, path.size) + id).joinToString(" -> ")
                "PluginRegistry: dependency cycle detected: $cycle"
            }

            onPath.add(id)
            path.add(id)

            for (dependency in byId.getValue(id).manifest.dependencies) {
                visit(dependency.pluginId)
            }

            path.removeAt(path.lastIndex)
            onPath.remove(id)
            visited.add(id)
            result.add(id)
        }

        for (plugin in pluginList) {
            visit(plugin.manifest.id)
        }

        return result
    }
}
