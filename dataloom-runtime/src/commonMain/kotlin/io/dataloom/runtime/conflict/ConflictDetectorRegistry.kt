package io.dataloom.runtime.conflict

import io.dataloom.api.conflict.ConflictDetector
import io.dataloom.api.identifier.ConflictDetectorId

/**
 * Immutable registry of [ConflictDetector] instances, keyed by
 * [ConflictDetectorId].
 *
 * ## Purpose
 *
 * [ConflictDetectorRegistry] holds all application-supplied conflict detectors
 * and provides exact ID-based lookup for the
 * [SynchronizationConflictOrchestrator]. Each registered detector ID maps to
 * exactly one detector.
 *
 * ## Defensive copy
 *
 * The supplier-provided collection is defensively copied at construction time.
 * Mutations to the original collection after construction have no effect on
 * this registry.
 *
 * ## Duplicate ID rejection
 *
 * Construction throws [IllegalArgumentException] when the supplied detector
 * collection contains more than one detector with the same [ConflictDetectorId].
 * ID uniqueness is required for unambiguous detector selection.
 *
 * ## Lookup
 *
 * [lookup] returns the [ConflictDetector] registered for the given
 * [ConflictDetectorId], or `null` when no detector is registered for that ID.
 *
 * ## Registration order preservation
 *
 * Insertion order is preserved for diagnostic purposes. Detectors are stored
 * in the order they appear in the supplied collection.
 *
 * ## No mutable collection exposure
 *
 * No mutable collection is exposed through any property or method.
 *
 * ## Construction restrictions
 *
 * Construction performs no detection, no resolution, no provider operation,
 * no lifecycle operation, no automatic detector discovery, no reflection, and
 * no ServiceLoader usage.
 *
 * ## Selection key
 *
 * The explicit [ConflictDetectorId] returned by [ConflictDetector.id] is the
 * selection key. Detectors are never selected by class name, collection hash
 * order, `toString()`, ConflictType, entity type, or platform service
 * discovery.
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
 * @param detectors the application-supplied [ConflictDetector] instances to
 *   register. Each detector must have a unique [ConflictDetector.id]. The
 *   collection is defensively copied.
 * @throws IllegalArgumentException if [detectors] contains duplicate
 *   [ConflictDetectorId] values.
 */
public class ConflictDetectorRegistry(
    detectors: Collection<ConflictDetector>,
) {

    private val detectorMap: Map<ConflictDetectorId, ConflictDetector>

    init {
        val snapshot = detectors.toList()
        val map = LinkedHashMap<ConflictDetectorId, ConflictDetector>(snapshot.size)
        for (detector in snapshot) {
            require(!map.containsKey(detector.id)) {
                "ConflictDetectorRegistry: duplicate detector registration for " +
                    "'${detector.id.value}'. Each ConflictDetectorId must be unique."
            }
            map[detector.id] = detector
        }
        detectorMap = map
    }

    /**
     * Returns the [ConflictDetector] registered for [id], or `null` when no
     * detector is registered for that ID.
     *
     * The lookup uses the exact [ConflictDetectorId] value as the key. It
     * never uses class names, ordinals, ConflictType, entity type, or service
     * discovery.
     *
     * @param id the [ConflictDetectorId] to look up.
     * @return the registered [ConflictDetector], or `null`.
     */
    public fun lookup(id: ConflictDetectorId): ConflictDetector? = detectorMap[id]

    /**
     * Returns an unmodifiable view of all registered detectors in the order
     * they were supplied at construction time.
     *
     * The returned collection is read-only and reflects a defensive snapshot.
     */
    public val detectors: List<ConflictDetector>
        get() = detectorMap.values.toList()

    /**
     * Returns a safe diagnostic string listing the registered detector IDs.
     *
     * Does not invoke any detector's `toString()` method.
     */
    override fun toString(): String {
        val ids = detectorMap.keys.joinToString { it.value }
        return "ConflictDetectorRegistry(detectorIds=[$ids])"
    }
}
