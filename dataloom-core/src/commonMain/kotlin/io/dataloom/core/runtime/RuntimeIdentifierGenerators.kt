package io.dataloom.core.runtime

import io.dataloom.api.identifier.ConflictId
import io.dataloom.api.identifier.IdentifierGenerator
import io.dataloom.api.identifier.QueueEntryId
import io.dataloom.api.identifier.QueueLeaseId
import io.dataloom.api.identifier.SynchronizationEventId

/**
 * Immutable container that groups the [IdentifierGenerator] instances
 * required by the DataLoom shared runtime.
 *
 * ## Purpose
 *
 * [RuntimeIdentifierGenerators] provides a single point of injection for all
 * identifier generators consumed by runtime components. Each generator is
 * strongly typed to a specific DataLoom identifier value class so that
 * identifiers of different types cannot be accidentally interchanged.
 *
 * ## Construction
 *
 * Every generator is required and must be supplied at construction time. No
 * default generator is provided. Construction does not invoke any generator,
 * cache any produced value, or access the system clock.
 *
 * ## No global state
 *
 * [RuntimeIdentifierGenerators] does not wrap generators in global singletons
 * or companion-object state. All generators are passed explicitly and held as
 * immutable properties.
 *
 * ## Dependency-injection neutrality
 *
 * [RuntimeIdentifierGenerators] does not depend on Hilt, Dagger, Koin, or any
 * other dependency-injection framework. Applications may construct instances
 * using any approach.
 *
 * Example:
 * ```kotlin
 * val identifiers = RuntimeIdentifierGenerators(
 *     synchronizationEventIds = eventIdGenerator,
 *     queueEntryIds = queueEntryIdGenerator,
 *     queueLeaseIds = queueLeaseIdGenerator,
 *     conflictIds = conflictIdGenerator,
 * )
 * ```
 *
 * @param synchronizationEventIds generator for [SynchronizationEventId] values
 *   used when the runtime emits synchronization lifecycle events.
 * @param queueEntryIds generator for [QueueEntryId] values used when the
 *   runtime enqueues new synchronization work items.
 * @param queueLeaseIds generator for [QueueLeaseId] values used when the
 *   runtime acquires exclusive leases over queue entries.
 * @param conflictIds generator for [ConflictId] values used when the runtime
 *   records detected synchronization conflicts.
 */
public class RuntimeIdentifierGenerators(
    /** Generator for [SynchronizationEventId] values. */
    public val synchronizationEventIds: IdentifierGenerator<SynchronizationEventId>,

    /** Generator for [QueueEntryId] values. */
    public val queueEntryIds: IdentifierGenerator<QueueEntryId>,

    /** Generator for [QueueLeaseId] values. */
    public val queueLeaseIds: IdentifierGenerator<QueueLeaseId>,

    /** Generator for [ConflictId] values. */
    public val conflictIds: IdentifierGenerator<ConflictId>,
) {

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RuntimeIdentifierGenerators) return false
        return synchronizationEventIds == other.synchronizationEventIds &&
            queueEntryIds == other.queueEntryIds &&
            queueLeaseIds == other.queueLeaseIds &&
            conflictIds == other.conflictIds
    }

    override fun hashCode(): Int {
        var result = synchronizationEventIds.hashCode()
        result = 31 * result + queueEntryIds.hashCode()
        result = 31 * result + queueLeaseIds.hashCode()
        result = 31 * result + conflictIds.hashCode()
        return result
    }

    override fun toString(): String =
        "RuntimeIdentifierGenerators(" +
            "synchronizationEventIds=$synchronizationEventIds, " +
            "queueEntryIds=$queueEntryIds, " +
            "queueLeaseIds=$queueLeaseIds, " +
            "conflictIds=$conflictIds)"
}
