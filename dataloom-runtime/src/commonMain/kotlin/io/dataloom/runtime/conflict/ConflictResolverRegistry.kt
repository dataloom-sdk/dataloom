package io.dataloom.runtime.conflict

import io.dataloom.api.conflict.ConflictResolver
import io.dataloom.api.identifier.ConflictResolverId

/**
 * Immutable registry of [ConflictResolver] instances, keyed by
 * [ConflictResolverId].
 *
 * ## Purpose
 *
 * [ConflictResolverRegistry] holds application-supplied conflict resolvers and
 * provides exact ID-based lookup for the
 * [SynchronizationConflictOrchestrator]. Each application registration maps
 * one identifier to exactly one resolver.
 *
 * The runtime also exposes a bounded set of deterministic built-in policies by
 * documented IDs. [lookup] checks application registrations first and then the
 * built-in catalog. This preserves explicit application control: registering a
 * custom resolver under a built-in ID overrides the reference implementation.
 * No built-in is selected implicitly; callers must still bind its exact ID.
 *
 * ## Defensive copy
 *
 * The supplier-provided collection is defensively copied at construction time.
 * Mutations to the original collection after construction have no effect on
 * this registry.
 *
 * ## Duplicate ID rejection
 *
 * Construction throws [IllegalArgumentException] when the supplied resolver
 * collection contains more than one resolver with the same [ConflictResolverId].
 * ID uniqueness is required for unambiguous application registration. A single
 * application resolver may intentionally use a built-in ID to override it.
 *
 * ## Lookup
 *
 * [lookup] returns the application resolver registered for the exact ID, then
 * falls back to a deterministic built-in resolver with that exact ID, or
 * returns `null` when neither exists.
 *
 * ## Registration order preservation
 *
 * Insertion order is preserved for diagnostic purposes. [resolvers] contains
 * only the application-supplied snapshot, in supplied order; built-ins are not
 * injected into that collection and therefore do not change its historical
 * size or iteration semantics.
 *
 * ## No mutable collection exposure
 *
 * No mutable collection is exposed through any property or method.
 *
 * ## Construction restrictions
 *
 * Construction performs no detection, no resolution, no provider operation,
 * no lifecycle operation, no reflection, no ServiceLoader usage, and no
 * platform discovery. Built-ins are fixed common-code objects resolved only by
 * an explicit ID lookup.
 *
 * ## Selection key
 *
 * The explicit [ConflictResolverId] returned by [ConflictResolver.id] is the
 * only selection key. Resolvers are never selected automatically by conflict
 * type, class name, registration position, or ID sorting. Resolution policy
 * remains caller-controlled through explicit binding.
 *
 * ## No global mutable state
 *
 * The registry contains no global mutable state and uses no service locator.
 *
 * ## KMP compatibility
 *
 * Uses Kotlin standard-library and DataLoom API types only. Safe for use in
 * Kotlin Multiplatform common code.
 *
 * @param resolvers the application-supplied [ConflictResolver] instances to
 *   register. Each supplied resolver must have a unique [ConflictResolver.id].
 *   The collection is defensively copied.
 * @throws IllegalArgumentException if [resolvers] contains duplicate
 *   [ConflictResolverId] values.
 */
public class ConflictResolverRegistry(
    resolvers: Collection<ConflictResolver>,
) {

    private val resolverMap: Map<ConflictResolverId, ConflictResolver>

    init {
        val snapshot = resolvers.toList()
        val map = LinkedHashMap<ConflictResolverId, ConflictResolver>(snapshot.size)
        for (resolver in snapshot) {
            require(!map.containsKey(resolver.id)) {
                "ConflictResolverRegistry: duplicate resolver registration for " +
                    "'${resolver.id.value}'. Each ConflictResolverId must be unique."
            }
            map[resolver.id] = resolver
        }
        resolverMap = map
    }

    /**
     * Returns the resolver selected by the exact [id], or `null` when neither
     * an application registration nor a built-in policy exists for that ID.
     *
     * Application registrations take precedence over the built-in catalog, so
     * an application can intentionally replace a reference policy without a
     * second selection mechanism. Lookup never uses class names, ordinals,
     * conflict type, registration position, or ID sorting.
     *
     * @param id the [ConflictResolverId] to look up.
     * @return the selected [ConflictResolver], or `null`.
     */
    public fun lookup(id: ConflictResolverId): ConflictResolver? =
        resolverMap[id] ?: builtInConflictResolver(id)

    /**
     * Returns an unmodifiable snapshot of application-supplied resolvers in
     * registration order.
     *
     * Built-in policies are intentionally not added to this list, preserving
     * the property's historical meaning and collection size.
     */
    public val resolvers: List<ConflictResolver>
        get() = resolverMap.values.toList()

    /**
     * Returns a safe diagnostic string listing application-registered resolver
     * IDs. Built-in availability is static and is not expanded into diagnostics.
     *
     * Does not invoke any resolver's `toString()` method.
     */
    override fun toString(): String {
        val ids = resolverMap.keys.joinToString { it.value }
        return "ConflictResolverRegistry(resolverIds=[$ids])"
    }
}
