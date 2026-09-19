package io.dataloom.processterminationproof

/**
 * Primitive-typed snapshot of one persisted [io.dataloom.api.conflict.ResolvedConflictDecisionRecord],
 * returned by [AppleResolvedConflictDecisionLogProcessTerminationProof].
 *
 * Every field is a String/Long -- the same interop-boundary design
 * [UnresolvedConflictLogProcessTerminationProofState] uses for the other
 * conflict-log domain. The real, richer production type this is derived from
 * is `io.dataloom.api.conflict.ResolvedConflictDecisionRecord` (`dataloom-api`).
 *
 * @property conflictType [io.dataloom.api.conflict.ConflictType.name] of the persisted record.
 * @property entityType the persisted [io.dataloom.api.change.EntityReference.type]'s
 *   [io.dataloom.api.identifier.EntityType.value].
 * @property entityId the persisted [io.dataloom.api.change.EntityReference.id]'s
 *   [io.dataloom.api.identifier.EntityId.value].
 * @property resolverId the persisted [io.dataloom.api.conflict.ResolvedConflictDecisionRecord.resolverId]'s
 *   [io.dataloom.api.identifier.ConflictResolverId.value].
 * @property decisionKind [io.dataloom.api.conflict.ResolvedConflictDecisionKind.name] of the persisted record.
 * @property committedAtEpochMillis the persisted [io.dataloom.api.conflict.ResolvedConflictDecisionRecord.committedAt]
 *   in epoch milliseconds.
 * @property version the persisted [io.dataloom.api.state.DurableStateRecord.version]
 *   compare-and-set generation counter.
 */
public class ResolvedConflictDecisionLogProcessTerminationProofState(
    public val conflictType: String,
    public val entityType: String,
    public val entityId: String,
    public val resolverId: String,
    public val decisionKind: String,
    public val committedAtEpochMillis: Long,
    public val version: Long,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ResolvedConflictDecisionLogProcessTerminationProofState) return false
        return conflictType == other.conflictType &&
            entityType == other.entityType &&
            entityId == other.entityId &&
            resolverId == other.resolverId &&
            decisionKind == other.decisionKind &&
            committedAtEpochMillis == other.committedAtEpochMillis &&
            version == other.version
    }

    override fun hashCode(): Int {
        var result = conflictType.hashCode()
        result = 31 * result + entityType.hashCode()
        result = 31 * result + entityId.hashCode()
        result = 31 * result + resolverId.hashCode()
        result = 31 * result + decisionKind.hashCode()
        result = 31 * result + committedAtEpochMillis.hashCode()
        result = 31 * result + version.hashCode()
        return result
    }

    override fun toString(): String = "ResolvedConflictDecisionLogProcessTerminationProofState(" +
        "conflictType=$conflictType, " +
        "entityType=$entityType, " +
        "entityId=$entityId, " +
        "resolverId=$resolverId, " +
        "decisionKind=$decisionKind, " +
        "committedAtEpochMillis=$committedAtEpochMillis, " +
        "version=$version)"
}
