package io.dataloom.runtime.observation

import io.dataloom.api.identifier.SynchronizationObserverId
import io.dataloom.api.observation.SynchronizationObserver

/**
 * Immutable registry of [SynchronizationObserver] instances, keyed by
 * [SynchronizationObserverId].
 *
 * ## Purpose
 *
 * [SynchronizationObserverRegistry] holds all application-supplied
 * synchronization observers and provides exact ID-based lookup and ordered
 * iteration for [SynchronizationEventDispatcher]. Each registered observer ID
 * maps to exactly one observer instance.
 *
 * ## Defensive copy
 *
 * The caller-provided collection is defensively copied at construction time.
 * Mutations to the original collection after construction have no effect on
 * this registry.
 *
 * ## Duplicate ID rejection
 *
 * Construction throws [IllegalArgumentException] when the supplied observer
 * collection contains more than one observer with the same
 * [SynchronizationObserverId]. ID uniqueness is required for unambiguous
 * observer selection and diagnostic identification.
 *
 * ## Lookup
 *
 * [lookup] returns the [SynchronizationObserver] registered for the given
 * [SynchronizationObserverId], or `null` when no observer is registered for
 * that ID.
 *
 * ## Registration order preservation
 *
 * Observer insertion order is preserved. Observers are stored and iterated in
 * the order they appear in the supplied collection. This order determines the
 * delivery sequence in [SynchronizationEventDispatcher].
 *
 * ## No mutable collection exposure
 *
 * No mutable collection is exposed through any property or method. [observers]
 * returns a read-only snapshot.
 *
 * ## Selection key
 *
 * The explicit [SynchronizationObserverId] returned by
 * [SynchronizationObserver.id] is the selection and registration key.
 * Observers are never selected by class name, collection hash order,
 * `toString()`, [io.dataloom.api.synchronization.SynchronizationPhase],
 * event type, identifier sorting, or platform service discovery.
 *
 * ## Construction restrictions
 *
 * Construction performs no event delivery, invokes no observer callback,
 * uses no global registry, uses no reflection, uses no ServiceLoader, and
 * uses no dependency-injection framework.
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
 * @param observers The application-supplied [SynchronizationObserver] instances
 *   to register. Each observer must have a unique [SynchronizationObserver.id].
 *   The collection is defensively copied at construction time.
 * @throws IllegalArgumentException if [observers] contains duplicate
 *   [SynchronizationObserverId] values.
 */
public class SynchronizationObserverRegistry(
    observers: Collection<SynchronizationObserver>,
) {

    private val observerMap: Map<SynchronizationObserverId, SynchronizationObserver>

    init {
        val snapshot = observers.toList()
        val map = LinkedHashMap<SynchronizationObserverId, SynchronizationObserver>(snapshot.size)
        for (observer in snapshot) {
            require(!map.containsKey(observer.id)) {
                "SynchronizationObserverRegistry: duplicate observer registration for " +
                    "'${observer.id.value}'. Each SynchronizationObserverId must be unique."
            }
            map[observer.id] = observer
        }
        observerMap = map
    }

    /**
     * Returns the [SynchronizationObserver] registered for [id], or `null`
     * when no observer is registered for that ID.
     *
     * The lookup uses the exact [SynchronizationObserverId] value as the key.
     * It never uses class names, ordinals, event type,
     * [io.dataloom.api.synchronization.SynchronizationPhase], or service
     * discovery.
     *
     * @param id The [SynchronizationObserverId] to look up.
     * @return The registered [SynchronizationObserver], or `null`.
     */
    public fun lookup(id: SynchronizationObserverId): SynchronizationObserver? = observerMap[id]

    /**
     * Returns a read-only ordered view of all registered observers in the
     * order they were supplied at construction time.
     *
     * The returned list is a defensive snapshot. Caller mutations have no
     * effect on the registry.
     */
    public val observers: List<SynchronizationObserver>
        get() = observerMap.values.toList()

    /**
     * Returns the number of observers registered in this registry.
     */
    public val size: Int
        get() = observerMap.size

    /**
     * Returns `true` if this registry contains no observers.
     */
    public fun isEmpty(): Boolean = observerMap.isEmpty()

    /**
     * Returns a safe diagnostic string listing the registered observer IDs.
     *
     * Does not invoke any observer's `toString()` method or expose observer
     * implementation state.
     */
    override fun toString(): String {
        val ids = observerMap.keys.joinToString { it.value }
        return "SynchronizationObserverRegistry(observerIds=[$ids])"
    }
}
