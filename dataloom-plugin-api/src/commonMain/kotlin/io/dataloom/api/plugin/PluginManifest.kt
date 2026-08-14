package io.dataloom.api.plugin

/**
 * Versioned identity, compatibility, capability, permission, and dependency
 * declaration for one plugin.
 *
 * [PluginManifest] is a pure data shape — it declares what a plugin *is* and
 * what it *requests*, not what it is *granted* or *doing*. Deny-by-default
 * registration and enablement, least-privilege permission grants, and
 * dependency-cycle rejection are all runtime behavior owned by the plugin
 * lifecycle engine (#98), not this contract module.
 *
 * ## Bounds
 *
 * [capabilities], [permissions], and [dependencies] are each capped at 64
 * entries and defensively copied — the same bounded-cardinality discipline
 * `PolicySet` already applies, and a direct instance of #93's own
 * "no ... unbounded-cardinality values" acceptance criterion. A manifest
 * needing more than 64 of any one kind is a design smell this contract
 * deliberately does not accommodate.
 *
 * @param id stable machine-readable identifier for this plugin.
 * @param version this plugin's own version label.
 * @param vendor the publishing vendor or author.
 * @param compatibleSdkRange the DataLoom SDK version range this plugin
 *   declares support for.
 * @param capabilities capability labels this plugin declares it provides.
 * @param permissions permission labels this plugin declares it requires.
 * @param dependencies other plugins this plugin declares a dependency on.
 * @throws IllegalArgumentException if [capabilities], [permissions], or
 *   [dependencies] exceeds 64 entries.
 */
public class PluginManifest(
    public val id: PluginId,
    public val version: PluginVersion,
    public val vendor: PluginVendor,
    public val compatibleSdkRange: PluginCompatibilityRange,
    capabilities: Set<PluginCapability> = emptySet(),
    permissions: Set<PluginPermission> = emptySet(),
    dependencies: Set<PluginDependency> = emptySet(),
) {
    /** Defensive read-only copy of the declared capabilities. */
    public val capabilities: Set<PluginCapability> = capabilities.toSet()

    /** Defensive read-only copy of the declared permissions. */
    public val permissions: Set<PluginPermission> = permissions.toSet()

    /** Defensive read-only copy of the declared dependencies. */
    public val dependencies: Set<PluginDependency> = dependencies.toSet()

    init {
        require(this.capabilities.size <= MAX_ENTRIES) {
            "PluginManifest capabilities must not exceed $MAX_ENTRIES entries."
        }
        require(this.permissions.size <= MAX_ENTRIES) {
            "PluginManifest permissions must not exceed $MAX_ENTRIES entries."
        }
        require(this.dependencies.size <= MAX_ENTRIES) {
            "PluginManifest dependencies must not exceed $MAX_ENTRIES entries."
        }
    }

    override fun equals(other: Any?): Boolean =
        this === other ||
            other is PluginManifest &&
            id == other.id &&
            version == other.version &&
            vendor == other.vendor &&
            compatibleSdkRange == other.compatibleSdkRange &&
            capabilities == other.capabilities &&
            permissions == other.permissions &&
            dependencies == other.dependencies

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + version.hashCode()
        result = 31 * result + vendor.hashCode()
        result = 31 * result + compatibleSdkRange.hashCode()
        result = 31 * result + capabilities.hashCode()
        result = 31 * result + permissions.hashCode()
        result = 31 * result + dependencies.hashCode()
        return result
    }

    override fun toString(): String =
        "PluginManifest(id=$id, version=$version, vendor=$vendor, " +
            "capabilityCount=${capabilities.size}, permissionCount=${permissions.size}, " +
            "dependencyCount=${dependencies.size})"

    private companion object {
        const val MAX_ENTRIES: Int = 64
    }
}
