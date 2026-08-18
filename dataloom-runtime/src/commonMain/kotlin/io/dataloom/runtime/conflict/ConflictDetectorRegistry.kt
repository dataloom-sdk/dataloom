package io.dataloom.runtime.conflict

import io.dataloom.api.conflict.ConflictDetector
import io.dataloom.api.identifier.ConflictDetectorId

/**
 * Immutable registry of [ConflictDetector] instances, keyed by
 * [ConflictDetectorId].
 *
 * ## Purpose
 *
 * [ConflictDetectorRegistry] holds application-supplied conflict detectors and
 * provides exact ID-based lookup for the
 * [SynchronizationConflictOrchestrator]. Each application registration maps
 * one identifier to exactly one detector.
 *
 * The runtime also exposes a bounded set of deterministic reference detectors
 * by documented IDs. [lookup] checks application registrations first and then
 * the built-in catalog. A detector is never selected implicitly: the caller
 * must still bind its exact [ConflictDetectorId]. Registering an application
 * detector under a built-in ID explicitly replaces that reference detector.
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
 * ID uniqueness is required for unambiguous application registration. A single
 * application detector may intentionally use a built-in ID as an override.
 *
 * ## Lookup
 *
 * [lookup] returns the application detector registered for the exact ID, then
 * falls back to the deterministic built-in detector with that ID, or returns
 * `null` when neither exists.
 *
 * ## Registration order preservation
 *
 * Insertion order is preserved for diagnostics. [detectors] contains only the
 * application-supplied snapshot in supplied order. Built-ins are not injected
 * into that collection and therefore do not change its historical size or
 * iteration semantics.
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
 * The explicit [ConflictDetectorId] returned by [ConflictDetector.id] is the
 * only selection key. Detectors are never selected by class name, collection
 * hash order, `toString()`, conflict type, entity type, registration position,
 * or platform service discovery.
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
 * @param detectors the application-supplied [ConflictDetector] instances to
 *   register. Each supplied detector must have a unique [ConflictDetector.id].
 *   The collection is defensively copied.
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
     * Returns the detector selected by exact [id], or `null` when neither an
     * application registration nor a built-in detector exists for that ID.
     *
     * Application registrations take precedence over the built-in catalog.
     * Lookup never uses class names, ordinals, conflict type, entity type,
     * registration position, or service discovery.
     *
     * @param id the [ConflictDetectorId] to look up.
     * @return the selected [ConflictDetector], or `null`.
     */
    public fun lookup(id: ConflictDetectorId): ConflictDetector? =
        detectorMap[id] ?: builtInConflictDetector(id)

    /**
     * Returns an unmodifiable snapshot of application-supplied detectors in
     * registration order.
     *
     * Built-in detectors are intentionally not added to this list, preserving
     * the property's historical meaning and collection size.
     */
    public val detectors: List<ConflictDetector>
        get() = detectorMap.values.toList()

    /**
     * Returns a safe diagnostic string listing application-registered detector
     * IDs. Static built-in availability is not expanded into diagnostics.
     *
     * Does not invoke any detector's `toString()` method.
     */
    override fun toString(): String {
        val ids = detectorMap.keys.joinToString { it.value }
        return "ConflictDetectorRegistry(detectorIds=[$ids])"
    }
}
