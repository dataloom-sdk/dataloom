package io.dataloom.api.strategy

import io.dataloom.api.change.ChangeSet
import io.dataloom.api.identifier.EntityType
import io.dataloom.api.model.SynchronizationDirection
import io.dataloom.api.model.SynchronizationRequest
import io.dataloom.api.synchronization.ChangeSetAcknowledgement
import io.dataloom.api.synchronization.SynchronizationCheckpoint
import io.dataloom.api.transport.PullChangesResult

/** Origin of one strategy admission without conflating it with strategy. */
public enum class StrategyExecutionTrigger {
    DIRECT,
    DURABLE_QUEUE,
    PLATFORM_SCHEDULE,
    LIFECYCLE,
    CONNECTIVITY,
    MANUAL,
    EXTERNAL,
}

/** Explicit operation input selected independently from synchronization strategy. */
public sealed interface StrategyOperationInput {
    /**
     * Input is obtained through application-owned providers selected by the
     * evaluated plan.
     */
    public data object ProviderBacked : StrategyOperationInput

    /**
     * Caller-owned input for a direct transport operation.
     *
     * [outboundChangeSet] is mandatory for PUSH and BIDIRECTIONAL requests and
     * prohibited for PULL requests. Pull cursors remain caller/remote-owned;
     * DataLoom does not persist them for network-only execution.
     */
    public class DirectTransport(
        public val outboundChangeSet: ChangeSet? = null,
        entityTypes: Set<EntityType> = emptySet(),
        public val maxEvents: Int? = null,
        public val checkpoint: SynchronizationCheckpoint? = null,
    ) : StrategyOperationInput {
        private val entityTypesSnapshot: Set<EntityType> = entityTypes.toSet()

        init {
            require(maxEvents == null || maxEvents > 0) {
                "Direct transport maxEvents must be greater than zero when supplied."
            }
        }

        public val entityTypes: Set<EntityType>
            get() = entityTypesSnapshot.toSet()

        override fun equals(other: Any?): Boolean =
            other is DirectTransport &&
                outboundChangeSet == other.outboundChangeSet &&
                entityTypesSnapshot == other.entityTypesSnapshot &&
                maxEvents == other.maxEvents &&
                checkpoint == other.checkpoint

        override fun hashCode(): Int {
            var result = outboundChangeSet?.hashCode() ?: 0
            result = (31 * result) + entityTypesSnapshot.hashCode()
            result = (31 * result) + (maxEvents ?: 0)
            result = (31 * result) + (checkpoint?.hashCode() ?: 0)
            return result
        }

        override fun toString(): String =
            "StrategyOperationInput.DirectTransport(" +
                "hasOutboundChangeSet=${outboundChangeSet != null}, " +
                "entityTypeCount=${entityTypesSnapshot.size}, " +
                "maxEvents=$maxEvents, " +
                "hasCheckpoint=${checkpoint != null})"
    }
}

/**
 * Complete immutable input to strategy admission and execution.
 *
 * Direction, mode, trigger, profile, evidence, and operation input remain
 * separate axes. Construction performs no provider call or policy evaluation.
 */
public data class StrategySynchronizationRequest(
    public val request: SynchronizationRequest,
    public val decisionId: StrategyDecisionId,
    public val planId: StrategyPlanId,
    public val profile: SynchronizationStrategyProfile,
    public val evidence: StrategyRuntimeEvidence,
    public val trigger: StrategyExecutionTrigger = StrategyExecutionTrigger.DIRECT,
    public val input: StrategyOperationInput = StrategyOperationInput.ProviderBacked,
) {
    init {
        if (input is StrategyOperationInput.DirectTransport) {
            when (request.direction) {
                SynchronizationDirection.PUSH,
                SynchronizationDirection.BIDIRECTIONAL,
                -> require(input.outboundChangeSet != null) {
                    "Direct transport PUSH and BIDIRECTIONAL requests require outboundChangeSet."
                }
                SynchronizationDirection.PULL -> require(input.outboundChangeSet == null) {
                    "Direct transport PULL requests must not define outboundChangeSet."
                }
            }
        }
    }

    /** Pure evaluator input derived without changing any caller-supplied axis. */
    public fun evaluationRequest(): StrategyEvaluationRequest =
        StrategyEvaluationRequest(
            decisionId = decisionId,
            planId = planId,
            profile = profile,
            direction = request.direction,
            mode = request.mode,
            evidence = evidence,
        )
}

/** Canonical successful output from direct transport-only execution. */
public sealed interface StrategyTransportOutput {
    public data class Pushed(
        public val acknowledgement: ChangeSetAcknowledgement,
    ) : StrategyTransportOutput

    public data class Pulled(
        public val result: PullChangesResult,
    ) : StrategyTransportOutput

    public data class Bidirectional(
        public val acknowledgement: ChangeSetAcknowledgement,
        public val pullResult: PullChangesResult,
    ) : StrategyTransportOutput
}
