package io.dataloom.runtime.strategy

import io.dataloom.api.error.DataLoomError
import io.dataloom.api.identifier.QueueEntryId
import io.dataloom.api.identifier.ScheduleId
import io.dataloom.api.model.SynchronizationDirection
import io.dataloom.api.queue.QueueEntryState
import io.dataloom.api.scheduling.ScheduleReceipt
import io.dataloom.api.strategy.BuiltInSynchronizationStrategy
import io.dataloom.api.strategy.StrategyCacheFreshnessEvidence
import io.dataloom.api.strategy.StrategyCacheState
import io.dataloom.api.strategy.StrategyDataOrigin
import io.dataloom.api.strategy.StrategyDisposition
import io.dataloom.api.strategy.StrategyEvaluationResult
import io.dataloom.api.strategy.StrategyOperation
import io.dataloom.api.strategy.StrategyProviderCapability
import io.dataloom.api.time.DataLoomInstant

/** Whether durable queue admission was new or reconciled with existing work. */
public enum class StrategyCacheDurableQueueAdmissionDisposition {
    ACCEPTED,
    ALREADY_ACCEPTED,
}

/** Terminal caller-visible disposition of durable cache-refresh admission. */
public enum class StrategyCacheDurableRefreshDisposition {
    SCHEDULED,
    ALREADY_IN_PROGRESS,
    ALREADY_TERMINAL,
    IDENTITY_CONFLICT,
    QUEUE_FAILED,
    SCHEDULE_FAILED,
}

/**
 * Payload-free result of admitting and scheduling one durable cache refresh.
 *
 * Queue admission always precedes scheduler invocation. Therefore
 * [ScheduleFailed] still means the immutable queue entry is durably present and
 * can be reconciled by retrying the same stable identities.
 */
public sealed interface StrategyCacheDurableRefreshResult {
    public val queueEntryId: QueueEntryId
    public val scheduleId: ScheduleId
    public val completedAt: DataLoomInstant
    public val disposition: StrategyCacheDurableRefreshDisposition

    /** Queue work is durable and the platform scheduler accepted its wake-up. */
    public data class Scheduled(
        override val queueEntryId: QueueEntryId,
        override val scheduleId: ScheduleId,
        public val queueAdmissionDisposition:
            StrategyCacheDurableQueueAdmissionDisposition,
        public val queueState: QueueEntryState,
        public val receipt: ScheduleReceipt,
        override val completedAt: DataLoomInstant,
    ) : StrategyCacheDurableRefreshResult {
        init {
            require(queueState == QueueEntryState.PENDING) {
                "A newly scheduled durable refresh must be PENDING."
            }
            require(receipt.id == scheduleId) {
                "Durable refresh schedule receipt must preserve schedule identity."
            }
        }

        override val disposition: StrategyCacheDurableRefreshDisposition =
            StrategyCacheDurableRefreshDisposition.SCHEDULED

        override fun toString(): String =
            "StrategyCacheDurableRefreshResult.Scheduled(" +
                "queueAdmissionDisposition=$queueAdmissionDisposition, " +
                "queueState=$queueState, disposition=$disposition)"
    }

    /**
     * Existing work is leased or waiting under retry ownership.
     *
     * The initial refresh scheduler is not invoked again.
     */
    public data class AlreadyInProgress(
        override val queueEntryId: QueueEntryId,
        override val scheduleId: ScheduleId,
        public val queueState: QueueEntryState,
        override val completedAt: DataLoomInstant,
    ) : StrategyCacheDurableRefreshResult {
        init {
            require(
                queueState == QueueEntryState.LEASED ||
                    queueState == QueueEntryState.RETRY_WAITING,
            ) {
                "In-progress durable refresh must be LEASED or RETRY_WAITING."
            }
        }

        override val disposition: StrategyCacheDurableRefreshDisposition =
            StrategyCacheDurableRefreshDisposition.ALREADY_IN_PROGRESS

        override fun toString(): String =
            "StrategyCacheDurableRefreshResult.AlreadyInProgress(" +
                "queueState=$queueState, disposition=$disposition)"
    }

    /** Existing work has reached a retained terminal queue state. */
    public data class AlreadyTerminal(
        override val queueEntryId: QueueEntryId,
        override val scheduleId: ScheduleId,
        public val queueState: QueueEntryState,
        override val completedAt: DataLoomInstant,
    ) : StrategyCacheDurableRefreshResult {
        init {
            require(
                queueState == QueueEntryState.COMPLETED ||
                    queueState == QueueEntryState.FAILED ||
                    queueState == QueueEntryState.CANCELLED ||
                    queueState == QueueEntryState.DEAD_LETTER,
            ) {
                "Terminal durable refresh requires a terminal queue state."
            }
        }

        override val disposition: StrategyCacheDurableRefreshDisposition =
            StrategyCacheDurableRefreshDisposition.ALREADY_TERMINAL

        override fun toString(): String =
            "StrategyCacheDurableRefreshResult.AlreadyTerminal(" +
                "queueState=$queueState, disposition=$disposition)"
    }

    /** The queue ID already belongs to different immutable work. */
    public data class IdentityConflict(
        override val queueEntryId: QueueEntryId,
        override val scheduleId: ScheduleId,
        public val currentState: QueueEntryState,
        override val completedAt: DataLoomInstant,
    ) : StrategyCacheDurableRefreshResult {
        override val disposition: StrategyCacheDurableRefreshDisposition =
            StrategyCacheDurableRefreshDisposition.IDENTITY_CONFLICT

        override fun toString(): String =
            "StrategyCacheDurableRefreshResult.IdentityConflict(" +
                "currentState=$currentState, disposition=$disposition)"
    }

    /** Queue admission could not determine or persist a durable outcome. */
    public data class QueueFailed(
        override val queueEntryId: QueueEntryId,
        override val scheduleId: ScheduleId,
        public val error: DataLoomError,
        override val completedAt: DataLoomInstant,
    ) : StrategyCacheDurableRefreshResult {
        override val disposition: StrategyCacheDurableRefreshDisposition =
            StrategyCacheDurableRefreshDisposition.QUEUE_FAILED

        override fun toString(): String =
            "StrategyCacheDurableRefreshResult.QueueFailed(" +
                "errorCode=${error.code}, disposition=$disposition)"
    }

    /**
     * Queue admission succeeded but scheduler acceptance failed.
     *
     * The durable entry is intentionally not deleted or reset.
     */
    public data class ScheduleFailed(
        override val queueEntryId: QueueEntryId,
        override val scheduleId: ScheduleId,
        public val queueAdmissionDisposition:
            StrategyCacheDurableQueueAdmissionDisposition,
        public val queueState: QueueEntryState,
        public val error: DataLoomError,
        override val completedAt: DataLoomInstant,
    ) : StrategyCacheDurableRefreshResult {
        init {
            require(queueState == QueueEntryState.PENDING) {
                "A failed initial schedule must preserve PENDING queue work."
            }
        }

        override val disposition: StrategyCacheDurableRefreshDisposition =
            StrategyCacheDurableRefreshDisposition.SCHEDULE_FAILED

        override fun toString(): String =
            "StrategyCacheDurableRefreshResult.ScheduleFailed(" +
                "queueAdmissionDisposition=$queueAdmissionDisposition, " +
                "queueState=$queueState, errorCode=${error.code}, " +
                "disposition=$disposition)"
    }
}

/**
 * Provider-verified local cache use plus durable refresh admission evidence.
 *
 * DataLoom exposes no application domain value. The local cache truth remains
 * independent from queue or scheduler failure, and the accepted continuation
 * is replayed later without current-policy evaluation.
 */
public class StrategyCacheServedWithDurableRefreshResult internal constructor(
    override val evaluation: StrategyEvaluationResult,
    public val evaluatedCacheState: StrategyCacheState,
    public val freshness: StrategyCacheFreshnessEvidence,
    public val refresh: StrategyCacheDurableRefreshResult,
) : StrategySynchronizationExecutionResult {
    init {
        val plan = evaluation.plan
        val continuation = requireNotNull(plan.durableContinuation) {
            "Durable cache refresh requires an immutable continuation."
        }
        require(
            plan.effectiveStrategy == BuiltInSynchronizationStrategy.CACHE_FIRST &&
                plan.direction == SynchronizationDirection.PULL &&
                plan.disposition == StrategyDisposition.SERVE_AND_REFRESH &&
                plan.operations == listOf(
                    StrategyOperation.SERVE_LOCAL,
                    StrategyOperation.ENQUEUE_DURABLE_WORK,
                    StrategyOperation.SCHEDULE_REFRESH,
                ) &&
                plan.requiredCapabilities == setOf(
                    StrategyProviderCapability.STORAGE,
                    StrategyProviderCapability.CACHE_ACCESS,
                    StrategyProviderCapability.QUEUE,
                    StrategyProviderCapability.SCHEDULER,
                ) &&
                plan.dataOrigin == StrategyDataOrigin.LOCAL &&
                plan.fallbackPlan == null,
        ) {
            "Durable cache refresh result requires the exact cache-first PULL admission plan."
        }
        require(
            continuation.operations == listOf(
                StrategyOperation.READ_CHECKPOINT,
                StrategyOperation.PULL_REMOTE,
                StrategyOperation.PERSIST_REMOTE,
            ) &&
                continuation.requiredCapabilities == setOf(
                    StrategyProviderCapability.STORAGE,
                    StrategyProviderCapability.TRANSPORT,
                ) &&
                continuation.dataOrigin == StrategyDataOrigin.REMOTE &&
                continuation.consistency == plan.consistency &&
                continuation.evaluatedCacheState == evaluatedCacheState &&
                continuation.fallbackPlan == null,
        ) {
            "Durable cache refresh continuation must be the exact canonical PULL replay."
        }
        require(
            evaluatedCacheState == StrategyCacheState.FRESH ||
                evaluatedCacheState == StrategyCacheState.STALE,
        ) {
            "Durable cache refresh requires evaluated FRESH or STALE state."
        }
        require(
            evaluatedCacheState != StrategyCacheState.FRESH ||
                freshness.cacheState == StrategyCacheState.FRESH,
        ) {
            "A fresh admission must not be served from stale provider evidence."
        }
    }

    override val completedAt: DataLoomInstant
        get() = refresh.completedAt

    public val dataOrigin: StrategyDataOrigin = StrategyDataOrigin.LOCAL

    override fun equals(other: Any?): Boolean =
        other is StrategyCacheServedWithDurableRefreshResult &&
            evaluation == other.evaluation &&
            evaluatedCacheState == other.evaluatedCacheState &&
            freshness == other.freshness &&
            refresh == other.refresh

    override fun hashCode(): Int {
        var result = evaluation.hashCode()
        result = (31 * result) + evaluatedCacheState.hashCode()
        result = (31 * result) + freshness.hashCode()
        result = (31 * result) + refresh.hashCode()
        return result
    }

    override fun toString(): String =
        "StrategyCacheServedWithDurableRefreshResult(" +
            "decisionId=${evaluation.decisionId}, " +
            "planId=${evaluation.plan.id}, " +
            "evaluatedCacheState=$evaluatedCacheState, " +
            "providerCacheState=${freshness.cacheState}, " +
            "refreshDisposition=${refresh.disposition}, " +
            "dataOrigin=$dataOrigin)"
}
