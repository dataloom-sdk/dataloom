package io.dataloom.runtime.facade

import io.dataloom.api.conflict.ConflictQuarantinePolicy
import io.dataloom.api.conflict.ConflictQuarantineRecord
import io.dataloom.api.conflict.ConflictQuarantineScope
import io.dataloom.api.state.DurableStateStore

/**
 * Application-owned opt-in configuration for loop/non-convergence quarantine
 * of repeatedly conflicting entities.
 *
 * Supplied to [DataLoomConflictDetectionSpec] to turn counting and quarantine
 * on for inbound pull, and to [DataLoomConflictAdministrationSpec] to make the
 * release command available. Both specs must be given the **same** [store] (or
 * two views of the same durable state) for a release to affect the counter
 * detection writes. When neither spec carries one, behavior is exactly as
 * before quarantine existed.
 *
 * Quarantine only has an effect when inbound pull applies resolved decisions,
 * so [DataLoomConflictDetectionSpec] requires its `resolvedConflictDecisionStore`
 * whenever this is supplied.
 *
 * @param store durable per-entity counter and quarantine state. The
 *   application chooses the backing implementation (for example a
 *   `RoomDurableStateStore` with `ConflictQuarantineRecordCodec` and
 *   `ConflictQuarantineScope.KeyEncoder`).
 * @param policy threshold (default 5 occurrences) and optional window. Used by
 *   detection; ignored by administration, which only releases.
 * @param schemaVersion the schema version written to [store].
 * @param maximumStateUpdateAttempts bounded compare-and-set attempts per
 *   counter or release write.
 */
public class DataLoomConflictQuarantineSpec(
    public val store: DurableStateStore<ConflictQuarantineScope, ConflictQuarantineRecord>,
    public val policy: ConflictQuarantinePolicy = ConflictQuarantinePolicy(),
    public val schemaVersion: Int = 1,
    public val maximumStateUpdateAttempts: Int = 8,
) {
    init {
        require(maximumStateUpdateAttempts >= 1) {
            "DataLoomConflictQuarantineSpec maximumStateUpdateAttempts must be at least one."
        }
    }

    /** Avoids rendering collaborator implementation state in diagnostics. */
    override fun toString(): String =
        "DataLoomConflictQuarantineSpec(policy=$policy, maximumStateUpdateAttempts=$maximumStateUpdateAttempts)"
}
