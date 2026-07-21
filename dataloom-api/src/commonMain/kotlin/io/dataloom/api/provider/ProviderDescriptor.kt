package io.dataloom.api.provider

import io.dataloom.api.context.DataLoomMetadata

/**
 * Immutable metadata describing a provider contract.
 *
 * This descriptor is a declaration-only model. It does not inspect provider
 * implementations, perform logging, read environment variables, or generate
 * identifiers and versions.
 *
 * `toString()` is for diagnostics only and is not a serialization format.
 */
public class ProviderDescriptor(
    /** Required stable machine-readable provider identifier. */
    public val id: ProviderId,

    /** Required stable human-readable provider name. */
    public val name: ProviderName,

    /** Required provider category. */
    public val type: ProviderType,

    /** Required provider version label. */
    public val version: ProviderVersion,

    capabilities: Set<ProviderCapability> = emptySet(),

    /** Optional immutable descriptor metadata. */
    public val metadata: DataLoomMetadata = DataLoomMetadata.Empty,
) {
    private val capabilitiesSnapshot: Set<ProviderCapability> = capabilities.toSet()

    /**
     * Immutable snapshot of declared provider capabilities.
     *
     * A defensive copy is returned on access.
     */
    public val capabilities: Set<ProviderCapability>
        get() = capabilitiesSnapshot.toSet()

    override fun equals(other: Any?): Boolean {
        if (this === other) {
            return true
        }
        if (other !is ProviderDescriptor) {
            return false
        }

        return id == other.id &&
            name == other.name &&
            type == other.type &&
            version == other.version &&
            capabilitiesSnapshot == other.capabilitiesSnapshot &&
            metadata == other.metadata
    }

    override fun hashCode(): Int {
        var result: Int = id.hashCode()
        result = (31 * result) + name.hashCode()
        result = (31 * result) + type.hashCode()
        result = (31 * result) + version.hashCode()
        result = (31 * result) + capabilitiesSnapshot.hashCode()
        result = (31 * result) + metadata.hashCode()
        return result
    }

    override fun toString(): String {
        return "ProviderDescriptor(" +
            "id=$id, " +
            "name=$name, " +
            "type=$type, " +
            "version=$version, " +
            "capabilities=$capabilitiesSnapshot, " +
            "metadata=$metadata" +
            ")"
    }
}
