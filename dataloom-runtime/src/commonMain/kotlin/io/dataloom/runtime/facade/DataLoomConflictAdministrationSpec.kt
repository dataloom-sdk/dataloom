package io.dataloom.runtime.facade

import io.dataloom.api.conflict.ConflictAdministrationAuthorizer
import io.dataloom.api.conflict.ConflictAdministrationExecutor
import io.dataloom.api.conflict.ConflictAdministrationStateStore
import io.dataloom.api.conflict.ResolvedConflictDecisionRecord
import io.dataloom.api.conflict.UnresolvedConflictRecord
import io.dataloom.api.identifier.ConflictId
import io.dataloom.api.state.DurableStateStore

/**
 * Immutable configuration for conflict-administration operations assembly.
 *
 * [unresolvedConflictStore] and [resolvedConflictDecisionStore] should be the
 * same durable stores supplied to [DataLoomConflictDetectionSpec] (when that
 * capability is also configured) so administration and live conflict
 * detection agree on the same durable facts -- this capability constructs
 * its own independent [io.dataloom.api.conflict.DurableUnresolvedConflictLog]/
 * [io.dataloom.api.conflict.DurableResolvedConflictDecisionLog] instances
 * over them rather than reaching into
 * [io.dataloom.runtime.conflict.DurableConflictDetectionCoordinator]'s
 * private ones.
 *
 * Both stores are required here -- unlike
 * [DataLoomConflictDetectionSpec.resolvedConflictDecisionStore], durably
 * recording the manual decision is this capability's entire purpose, so
 * there is no optional "observational-only" mode.
 *
 * All collaborators are explicit application/platform dependencies. The
 * builder uses its existing runtime clock and does not invoke any
 * collaborator while constructing [DataLoomConflictAdministration].
 */
public class DataLoomConflictAdministrationSpec(
    /** Host-owned authorization boundary for privileged manual conflict commands. */
    public val authorizer: ConflictAdministrationAuthorizer,

    /** Durable command-state and immutable audit boundary. */
    public val stateStore: ConflictAdministrationStateStore,

    /** Host-owned application and staleness-check boundary for an authorized manual decision. */
    public val executor: ConflictAdministrationExecutor,

    /** Durable store backing eligibility checks and the source of a conflict's structural facts. */
    public val unresolvedConflictStore: DurableStateStore<ConflictId, UnresolvedConflictRecord>,

    /** Durable store backing eligibility checks and the manual decision's own durable record. */
    public val resolvedConflictDecisionStore: DurableStateStore<ConflictId, ResolvedConflictDecisionRecord>,

    /** Passed through to this capability's own [io.dataloom.api.conflict.DurableUnresolvedConflictLog] schema-version parameter. */
    public val unresolvedConflictLogSchemaVersion: Int = 1,

    /** Passed through to this capability's own [io.dataloom.api.conflict.DurableUnresolvedConflictLog] retry-bound parameter. */
    public val unresolvedConflictLogMaximumStateUpdateAttempts: Int = 8,

    /** Passed through to this capability's own [io.dataloom.api.conflict.DurableResolvedConflictDecisionLog] schema-version parameter. */
    public val resolvedConflictDecisionLogSchemaVersion: Int = 1,

    /** Passed through to this capability's own [io.dataloom.api.conflict.DurableResolvedConflictDecisionLog] retry-bound parameter. */
    public val resolvedConflictDecisionLogMaximumStateUpdateAttempts: Int = 8,

    /** Maximum bounded compare-and-set attempts for one command execution. */
    public val maximumStateUpdateAttempts: Int = 8,
) {
    init {
        require(maximumStateUpdateAttempts >= 1) {
            "DataLoomConflictAdministrationSpec maximumStateUpdateAttempts must be at least one."
        }
        require(unresolvedConflictLogMaximumStateUpdateAttempts >= 1) {
            "DataLoomConflictAdministrationSpec unresolvedConflictLogMaximumStateUpdateAttempts must be at least one."
        }
        require(resolvedConflictDecisionLogMaximumStateUpdateAttempts >= 1) {
            "DataLoomConflictAdministrationSpec resolvedConflictDecisionLogMaximumStateUpdateAttempts must be at least one."
        }
    }

    /** Avoids rendering collaborator implementation state in diagnostics. */
    override fun toString(): String =
        "DataLoomConflictAdministrationSpec(maximumStateUpdateAttempts=$maximumStateUpdateAttempts)"
}
