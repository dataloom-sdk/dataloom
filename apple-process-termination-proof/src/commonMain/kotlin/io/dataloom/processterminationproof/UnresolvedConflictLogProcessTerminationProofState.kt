package io.dataloom.processterminationproof

/**
 * Primitive-typed snapshot of one persisted [io.dataloom.api.conflict.UnresolvedConflictRecord],
 * returned by [AppleUnresolvedConflictLogProcessTerminationProof].
 *
 * Every field is a String/Long so this type crosses the Kotlin/Native
 * Objective-C/Swift interop boundary in the simplest shape available -- the
 * same design [ProcessTerminationProofState] and
 * [RetryBudgetProcessTerminationProofState] already use for the circuit-breaker
 * and retry-budget domains. The real, richer production type this is derived
 * from is `io.dataloom.api.conflict.UnresolvedConflictRecord` (`dataloom-api`)
 * -- this class exists only so the Apple Simulator proof app can read the
 * outcome back through plain Swift-visible fields, without needing to
 * construct or pattern-match [io.dataloom.api.conflict.DurableUnresolvedConflictRecordOutcome]'s
 * sealed variants from Swift.
 *
 * @property conflictType [io.dataloom.api.conflict.ConflictType.name] of the persisted record.
 * @property entityType the persisted [io.dataloom.api.change.EntityReference.type]'s
 *   [io.dataloom.api.identifier.EntityType.value].
 * @property entityId the persisted [io.dataloom.api.change.EntityReference.id]'s
 *   [io.dataloom.api.identifier.EntityId.value].
 * @property reason [io.dataloom.api.conflict.UnresolvedConflictReason.name] of the persisted record.
 * @property committedAtEpochMillis the persisted [io.dataloom.api.conflict.UnresolvedConflictRecord.committedAt]
 *   in epoch milliseconds.
 * @property version the persisted [io.dataloom.api.state.DurableStateRecord.version]
 *   compare-and-set generation counter.
 */
public class UnresolvedConflictLogProcessTerminationProofState(
    public val conflictType: String,
    public val entityType: String,
    public val entityId: String,
    public val reason: String,
    public val committedAtEpochMillis: Long,
    public val version: Long,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is UnresolvedConflictLogProcessTerminationProofState) return false
        return conflictType == other.conflictType &&
            entityType == other.entityType &&
            entityId == other.entityId &&
            reason == other.reason &&
            committedAtEpochMillis == other.committedAtEpochMillis &&
            version == other.version
    }

    override fun hashCode(): Int {
        var result = conflictType.hashCode()
        result = 31 * result + entityType.hashCode()
        result = 31 * result + entityId.hashCode()
        result = 31 * result + reason.hashCode()
        result = 31 * result + committedAtEpochMillis.hashCode()
        result = 31 * result + version.hashCode()
        return result
    }

    override fun toString(): String = "UnresolvedConflictLogProcessTerminationProofState(" +
        "conflictType=$conflictType, " +
        "entityType=$entityType, " +
        "entityId=$entityId, " +
        "reason=$reason, " +
        "committedAtEpochMillis=$committedAtEpochMillis, " +
        "version=$version)"
}
