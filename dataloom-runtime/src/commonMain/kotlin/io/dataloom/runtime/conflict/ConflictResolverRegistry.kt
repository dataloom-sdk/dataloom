package io.dataloom.runtime.conflict

import io.dataloom.api.conflict.ConflictResolver
import io.dataloom.api.identifier.ConflictResolverId

/**
 * Immutable registry of [ConflictResolver] instances, keyed by
 * [ConflictResolverId].
 *
 * ## Purpose
 *
 * [ConflictResolverRegistry] holds all application-supplied conflict resolvers
 * and provides exact ID-based lookup for the
 * [SynchronizationConflictOrchestrator]. Each registered resolver ID maps to
 * exactly one resolver.
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
 * ID uniqueness is required for unambiguous resolver selection.
 *
 * ## Lookup
 *
 * [lookup] returns the [ConflictResolver] registered for the given
 * [ConflictResolverId], or `null` when no resolver is registered for that ID.
 *
 * ## Registration order preservation
 *
 * Insertion order is preserved for diagnostic purposes. Resolvers are stored
 * in the order they appear in the supplied collection.
 *
 * ## No mutable collection exposure
 *
 * No mutable collection is exposed through any property or method.
 *
 * ## Construction restrictions
 *
 * Construction performs no detection, no resolution, no provider operation,
 * no lifecycle operation, no automatic resolver discovery, no reflection, and
 * no ServiceLoader usage.
 *
 * ## Selection key
 *
 * The explicit [ConflictResolverId] returned by [ConflictResolver.id] is the
 * selection key. Resolvers are never selected automatically by ConflictType,
 * class name, first registration, last registration, or ID sorting.
 * Resolution policy remains application-controlled through explicit binding.
 *
 * ## No global state
 *
 * The registry contains no global state and uses no service locator.
 *
 * ## KMP compatibility
 *
 * Uses Kotlin standard-library and DataLoom API types only. Safe for use in
 * Kotlin Multiplatform common code.
 *
 * @param resolvers the application-supplied [ConflictResolver] instances to
 *   register. Each resolver must have a unique [ConflictResolver.id]. The
 *   collection is defensively copied.
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
     * Returns the [ConflictResolver] registered for [id], or `null` when no
     * resolver is registered for that ID.
     *
     * The lookup uses the exact [ConflictResolverId] value as the key. It
     * never uses class names, ordinals, ConflictType, first/last registration,
     * or ID sorting.
     *
     * @param id the [ConflictResolverId] to look up.
     * @return the registered [ConflictResolver], or `null`.
     */
    public fun lookup(id: ConflictResolverId): ConflictResolver? = resolverMap[id]

    /**
     * Returns an unmodifiable view of all registered resolvers in the order
     * they were supplied at construction time.
     *
     * The returned collection is read-only and reflects a defensive snapshot.
     */
    public val resolvers: List<ConflictResolver>
        get() = resolverMap.values.toList()

    /**
     * Returns a safe diagnostic string listing the registered resolver IDs.
     *
     * Does not invoke any resolver's `toString()` method.
     */
    override fun toString(): String {
        val ids = resolverMap.keys.joinToString { it.value }
        return "ConflictResolverRegistry(resolverIds=[$ids])"
    }
}
