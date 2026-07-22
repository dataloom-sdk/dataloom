package io.dataloom.core.provider

import io.dataloom.api.provider.DataLoomProvider
import io.dataloom.api.provider.ProviderId
import io.dataloom.api.provider.ProviderType

/**
 * Immutable registry of application-supplied [DataLoomProvider] instances.
 *
 * ## Purpose
 *
 * [ProviderRegistry] owns references to all [DataLoomProvider] instances that
 * the application registers with the DataLoom runtime. It provides
 * deterministic lookup by [ProviderId] and [ProviderType], and preserves
 * registration order for deterministic lifecycle orchestration.
 *
 * ## Immutability
 *
 * The registry is immutable after construction. The supplied [providers]
 * collection is defensively copied on construction. Mutations to the original
 * collection after construction have no effect on the registry.
 *
 * ## Duplicate ProviderId rejection
 *
 * Each [ProviderId] must be unique within one registry. Construction throws
 * [IllegalArgumentException] when duplicate [ProviderId] values are detected.
 * The exception message identifies the duplicate IDs without exposing provider
 * internals or credentials.
 *
 * ## Multiple providers per ProviderType
 *
 * [ProviderType] values are not unique within a registry. Multiple providers
 * of the same type may be registered. Provider-selection policy — choosing
 * which provider to use when multiple providers share the same type — is
 * deferred and is not part of [ProviderRegistry].
 *
 * ## Registration order
 *
 * Providers are stored in the order they appear in the supplied [providers]
 * list. [ProviderLifecycleCoordinator] uses this order to determine
 * initialization sequence.
 *
 * ## No lifecycle operations
 *
 * Construction performs no provider initialization, no provider shutdown,
 * and no provider health check. [ProviderRegistry] is a pure data structure.
 *
 * ## No global state
 *
 * [ProviderRegistry] does not use process-wide singletons, companion-object
 * mutable fields, service locators, or reflection.
 *
 * ## KMP compatibility
 *
 * [ProviderRegistry] uses Kotlin standard-library types only and is safe for
 * use in Kotlin Multiplatform common code.
 *
 * ## Security restrictions
 *
 * Do not place credentials, tokens, encryption keys, or personal data in
 * provider metadata or descriptors.
 *
 * @param providers ordered list of [DataLoomProvider] instances to register.
 * @throws IllegalArgumentException if [providers] contains duplicate [ProviderId] values.
 */
public class ProviderRegistry(providers: List<DataLoomProvider>) {

    init {
        val seen = mutableSetOf<ProviderId>()
        val duplicates = providers.mapNotNull { provider ->
            val id = provider.descriptor.id
            if (!seen.add(id)) id else null
        }
        require(duplicates.isEmpty()) {
            "ProviderRegistry: duplicate ProviderId values detected: ${duplicates.joinToString()}"
        }
    }

    private val providerList: List<DataLoomProvider> = providers.toList()
    private val byId: Map<ProviderId, DataLoomProvider> = providerList.associateBy { it.descriptor.id }
    private val byType: Map<ProviderType, List<DataLoomProvider>> = providerList.groupBy { it.descriptor.type }

    /**
     * All registered providers in registration order.
     *
     * The returned list is immutable and reflects the registration order
     * supplied at construction time.
     */
    public val providers: List<DataLoomProvider>
        get() = providerList

    /**
     * Number of registered providers.
     */
    public val size: Int
        get() = providerList.size

    /**
     * Returns `true` when no providers are registered.
     */
    public fun isEmpty(): Boolean = providerList.isEmpty()

    /**
     * Returns the [DataLoomProvider] with the given [id], or `null` when no
     * provider with that ID is registered.
     *
     * @param id the [ProviderId] to look up.
     * @return the matching provider, or `null` if absent.
     */
    public fun findById(id: ProviderId): DataLoomProvider? = byId[id]

    /**
     * Returns all [DataLoomProvider] instances registered with the given
     * [type], in registration order.
     *
     * Returns an empty list when no provider with the given type is registered.
     *
     * @param type the [ProviderType] to look up.
     * @return an ordered, immutable list of matching providers; empty if none.
     */
    public fun findByType(type: ProviderType): List<DataLoomProvider> =
        byType[type] ?: emptyList()
}
