package io.dataloom.api.runtime

import io.dataloom.api.time.DataLoomClock

/**
 * Immutable container for the explicit dependencies required by DataLoom
 * shared runtime components.
 *
 * ## Purpose
 *
 * [RuntimeDependencies] is the initial dependency container for the DataLoom
 * shared runtime. It groups the [clock] and [identifiers] that runtime
 * components need to produce timestamps and identifiers for persisted values.
 *
 * ## Explicit injection
 *
 * Every dependency is required and must be supplied at construction time.
 * There are no default values, no platform clock, and no implicit identifier
 * generator. This makes every runtime dependency visible and replaceable,
 * enabling deterministic testing and flexible production configuration.
 *
 * ## Construction
 *
 * Construction performs no clock read, no identifier generation, and no
 * runtime initialization. All behavior is deferred to the point of use.
 *
 * ## No global state
 *
 * [RuntimeDependencies] does not access global singletons, companion-object
 * state, or service locators. All dependencies are passed explicitly.
 *
 * ## Dependency-injection neutrality
 *
 * [RuntimeDependencies] does not depend on Hilt, Dagger, Koin, or any other
 * dependency-injection framework. Applications may construct instances using
 * any approach.
 *
 * Example with manual construction:
 * ```kotlin
 * val dependencies = RuntimeDependencies(
 *     clock = applicationClock,
 *     identifiers = RuntimeIdentifierGenerators(
 *         synchronizationEventIds = eventIdGenerator,
 *         queueEntryIds = queueEntryIdGenerator,
 *         queueLeaseIds = queueLeaseIdGenerator,
 *         conflictIds = conflictIdGenerator,
 *     ),
 * )
 * ```
 *
 * ## Future extensions
 *
 * Future issues may extend runtime construction with providers, policies,
 * observers, and additional configuration. Those extensions are out of scope
 * for DL-017.
 *
 * @param clock the wall-clock source used by runtime components when an
 *   explicit [io.dataloom.api.time.DataLoomInstant] is required.
 * @param identifiers the grouped identifier generators used by runtime
 *   components when producing strongly typed runtime-owned identifiers.
 */
public class RuntimeDependencies(
    /** Wall-clock source for obtaining the current [io.dataloom.api.time.DataLoomInstant]. */
    public val clock: DataLoomClock,

    /** Grouped identifier generators for all runtime-owned identifier types. */
    public val identifiers: RuntimeIdentifierGenerators,
) {

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RuntimeDependencies) return false
        return clock == other.clock && identifiers == other.identifiers
    }

    override fun hashCode(): Int {
        var result = clock.hashCode()
        result = 31 * result + identifiers.hashCode()
        return result
    }

    override fun toString(): String =
        "RuntimeDependencies(clock=$clock, identifiers=$identifiers)"
}
