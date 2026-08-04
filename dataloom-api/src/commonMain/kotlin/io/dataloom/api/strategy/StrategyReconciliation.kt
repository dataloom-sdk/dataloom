package io.dataloom.api.strategy

import io.dataloom.api.model.SynchronizationRequest
import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.api.storage.StorageProvider

/**
 * Immutable request to reconcile state after an accepted strategy continuation.
 *
 * It carries only bounded plan identity and operation evidence. Payloads,
 * credentials, provider values, exception text, and arbitrary metadata are not
 * accepted by this contract.
 */
public class StrategyReconciliationRequest(
    public val request: SynchronizationRequest,
    public val decisionId: StrategyDecisionId,
    public val planId: StrategyPlanId,
    public val profileId: StrategyProfileId,
    public val configurationVersion: StrategyConfigurationVersion,
    completedOperations: List<StrategyOperation>,
) {
    private val completedSnapshot: List<StrategyOperation> =
        completedOperations.toList()

    init {
        require(completedSnapshot.isNotEmpty()) {
            "StrategyReconciliationRequest requires completed operation evidence."
        }
        require(completedSnapshot.size <= StrategyOperation.entries.size) {
            "StrategyReconciliationRequest operation evidence exceeds the finite operation set."
        }
        require(completedSnapshot.size == completedSnapshot.distinct().size) {
            "StrategyReconciliationRequest operation evidence must be unique and ordered."
        }
        require(StrategyOperation.RECONCILE !in completedSnapshot) {
            "StrategyReconciliationRequest must describe work completed before reconciliation."
        }
    }

    public val completedOperations: List<StrategyOperation>
        get() = completedSnapshot.toList()

    override fun equals(other: Any?): Boolean =
        other is StrategyReconciliationRequest &&
            request == other.request &&
            decisionId == other.decisionId &&
            planId == other.planId &&
            profileId == other.profileId &&
            configurationVersion == other.configurationVersion &&
            completedSnapshot == other.completedSnapshot

    override fun hashCode(): Int {
        var result = request.hashCode()
        result = 31 * result + decisionId.hashCode()
        result = 31 * result + planId.hashCode()
        result = 31 * result + profileId.hashCode()
        result = 31 * result + configurationVersion.hashCode()
        result = 31 * result + completedSnapshot.hashCode()
        return result
    }

    /** Bounded diagnostics that exclude request payload and dynamic identifiers. */
    override fun toString(): String =
        "StrategyReconciliationRequest(" +
            "operationCount=${completedSnapshot.size}, " +
            "configurationVersion=${configurationVersion.value})"
}

/** Terminal result of the bounded strategy reconciliation hook. */
public sealed interface StrategyReconciliationResult {
    /** Reconciliation was applied successfully. */
    public data object Applied : StrategyReconciliationResult

    /** Existing state already satisfied the accepted plan. */
    public data object NotRequired : StrategyReconciliationResult
}

/**
 * Optional narrow storage capability for the built-in `RECONCILE` operation.
 *
 * This is not a replacement synchronization pipeline. Implementations apply
 * only the bounded reconciliation requested by an already accepted immutable
 * strategy plan.
 */
public interface StrategyReconciliationProvider : StorageProvider {
    public suspend fun reconcileStrategy(
        request: StrategyReconciliationRequest,
    ): ProviderOperationResult<StrategyReconciliationResult>
}
