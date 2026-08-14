package io.dataloom.runtime.strategy

import io.dataloom.api.error.DataLoomError
import io.dataloom.api.identifier.QueueEntryId
import io.dataloom.api.provider.ProviderBindingFailure
import io.dataloom.api.strategy.StrategyCacheState
import io.dataloom.api.strategy.StrategyEvaluationResult
import io.dataloom.api.strategy.StrategyLocalFallbackResult
import io.dataloom.api.strategy.StrategyOperation
import io.dataloom.api.strategy.StrategyProviderCapability
import io.dataloom.api.strategy.StrategyRemoteOutcome
import io.dataloom.api.strategy.StrategyTransportOutput
import io.dataloom.api.time.DataLoomInstant

/** Runtime reason for rejecting a strategy request before transport execution. */
public enum class StrategyExecutionRejectionReason {
    PROVIDERS_NOT_INITIALIZED,
    STRATEGY_REJECTED,
    INCOMPATIBLE_TRIGGER,
    INCOMPATIBLE_INPUT,
    PROVIDER_RESOLUTION_FAILED,
    PROVIDER_PROTECTION_NOT_CONFIGURED,
    PROVIDER_PROTECTION_SCOPE_MISMATCH,
    LOCAL_FALLBACK_PROVIDER_NOT_CONFIGURED,
    ACCEPTED_PLAN_MISMATCH,
    ACCEPTED_PLAN_CONTINUATION_MISSING,
    RECONCILIATION_PROVIDER_NOT_CONFIGURED,
    UNSUPPORTED_PLAN,

    /**
     * The evaluated plan requires durable queue admission
     * (`ENQUEUE_DURABLE_WORK`, alongside `SCHEDULE_REFRESH` for cache-first
     * or on its own for offline-first/hybrid), but no
     * [io.dataloom.runtime.submission.QueuedSynchronizationWorkEncoder] is
     * configured ([io.dataloom.runtime.facade.DataLoomBuilder.queueSubmissionEncoder]
     * / [io.dataloom.runtime.facade.DataLoomBuilder.queueSubmissionConfiguration]).
     * [StrategyDurableQueueAdmitter] is the real admission path once an
     * encoder is configured; this reason is what every caller received
     * before an encoder existed, and is still what an unconfigured caller
     * receives today — durable admission is opt-in, not a breaking
     * requirement. Named for the first executor that needed it; the
     * underlying gap (no encoder configured) is identical across the three
     * strategies that can produce `ENQUEUE_DURABLE_WORK` plans, so this one
     * reason covers all of them rather than near-duplicate reasons for the
     * same root cause.
     */
    DURABLE_REFRESH_NOT_YET_SUPPORTED,

    /**
     * A [StrategyDurableQueueAdmitter] admission attempt could not resolve a
     * queue provider, or the resolved storage/transport providers lack a
     * [io.dataloom.api.provider.ProviderId] to persist on the durable queue
     * entry. This is defensive: the plan's `requiredCapabilities` already
     * demand these roles, so provider resolution should already have failed
     * with [PROVIDER_RESOLUTION_FAILED] before reaching admission. Reserved
     * for the case where resolution nominally succeeded but the resolved
     * provider set is still structurally incomplete.
     */
    DURABLE_ADMISSION_PROVIDERS_INCOMPLETE,

    /**
     * No longer produced. A [HybridStrategyExecutor] plan that selects
     * `HybridSource.LOCAL` for a PUSH-direction request (operations
     * `[READ_LOCAL]` only — no `SERVE_LOCAL`, no remote operation) now
     * returns [StrategySynchronizationExecutionResult.AcceptedLocally]
     * instead of this rejection. Kept as a public enum entry rather than
     * removed, since removing it would be an ABI break; retained only for
     * historical/diagnostic reference by callers that logged it before this
     * change.
     */
    HYBRID_LOCAL_PUSH_NOT_YET_SUPPORTED,
}

/** Observable result of strategy admission and execution. */
public sealed interface StrategySynchronizationExecutionResult {
    public val evaluation: StrategyEvaluationResult
    public val completedAt: DataLoomInstant

    public data class Executed(
        override val evaluation: StrategyEvaluationResult,
        override val completedAt: DataLoomInstant,
        public val output: StrategyTransportOutput,
    ) : StrategySynchronizationExecutionResult

    /**
     * Local cache state was served as the primary, expected outcome — not a
     * fallback after a remote failure.
     *
     * Distinct from [FallbackActivated]: that variant means a remote attempt
     * failed and local state was substituted afterward. This variant means
     * cache-first policy chose to serve local state directly, by design,
     * with no implication that remote was ever attempted or unavailable.
     *
     * [refreshOutput] is non-null only when the plan also admitted a
     * synchronous, non-durable refresh alongside serving local state
     * (`CacheFirstStrategyProfile.staleCachePolicy = SERVE_STALE_AND_REFRESH`
     * or `refreshOnFreshHit = true`, with `requireDurableRefresh = false`).
     * When present, it carries the refresh's own terminal output; the refresh
     * having run does not change [cacheState], which always describes what
     * was actually served to evidence at admission time.
     *
     * [durableQueueEntryId] is non-null only when the plan also admitted a
     * *durable* refresh/reconciliation alongside serving local state
     * (`requireDurableRefresh = true` for cache-first, or hybrid's
     * `LOCAL`-selected-as-fallback branch with `reconcileAfterFallback =
     * true`), and a [StrategyDurableQueueAdmitter] was configured and
     * admission succeeded. Mutually exclusive with [refreshOutput] in
     * practice — the evaluator never produces a plan requiring both a
     * synchronous and a durable refresh at once — but both are independently
     * nullable rather than a sealed choice, to avoid forcing every caller
     * through a `when` for the common case where neither is present.
     */
    public data class ServedFromCache(
        override val evaluation: StrategyEvaluationResult,
        override val completedAt: DataLoomInstant,
        public val cacheState: StrategyCacheState,
        public val refreshOutput: StrategyTransportOutput? = null,
        public val durableQueueEntryId: QueueEntryId? = null,
    ) : StrategySynchronizationExecutionResult {
        init {
            require(
                cacheState == StrategyCacheState.FRESH ||
                    cacheState == StrategyCacheState.STALE,
            ) {
                "ServedFromCache requires FRESH or STALE cache state."
            }
        }
    }

    public class Failed(
        override val evaluation: StrategyEvaluationResult,
        override val completedAt: DataLoomInstant,
        public val error: DataLoomError,
        public val transportAttempted: Boolean,
        completedOperations: List<StrategyOperation> = emptyList(),
        public val partialOutput: StrategyTransportOutput? = null,
        public val remoteOutcome: StrategyRemoteOutcome? = null,
        public val primaryError: DataLoomError? = null,
        public val fallbackAttempted: Boolean = false,
    ) : StrategySynchronizationExecutionResult {
        private val completedOperationsSnapshot: List<StrategyOperation> =
            completedOperations.toList()

        public val completedOperations: List<StrategyOperation>
            get() = completedOperationsSnapshot.toList()

        override fun equals(other: Any?): Boolean =
            other is Failed &&
                evaluation == other.evaluation &&
                completedAt == other.completedAt &&
                error == other.error &&
                transportAttempted == other.transportAttempted &&
                completedOperationsSnapshot == other.completedOperationsSnapshot &&
                partialOutput == other.partialOutput &&
                remoteOutcome == other.remoteOutcome &&
                primaryError == other.primaryError &&
                fallbackAttempted == other.fallbackAttempted

        override fun hashCode(): Int {
            var result = evaluation.hashCode()
            result = (31 * result) + completedAt.hashCode()
            result = (31 * result) + error.hashCode()
            result = (31 * result) + transportAttempted.hashCode()
            result = (31 * result) + completedOperationsSnapshot.hashCode()
            result = (31 * result) + (partialOutput?.hashCode() ?: 0)
            result = (31 * result) + (remoteOutcome?.hashCode() ?: 0)
            result = (31 * result) + (primaryError?.hashCode() ?: 0)
            result = (31 * result) + fallbackAttempted.hashCode()
            return result
        }

        override fun toString(): String =
            "StrategySynchronizationExecutionResult.Failed(" +
                "decisionId=${evaluation.decisionId}, " +
                "planId=${evaluation.plan.id}, " +
                "errorCode=${error.code}, " +
                "transportAttempted=$transportAttempted, " +
                "completedOperations=$completedOperationsSnapshot, " +
                "partialOutput=$partialOutput, " +
                "remoteOutcome=$remoteOutcome, " +
                "primaryErrorCode=${primaryError?.code}, " +
                "fallbackAttempted=$fallbackAttempted)"
    }

    /** An allowlisted remote outcome activated application-owned local state. */
    public class FallbackActivated(
        override val evaluation: StrategyEvaluationResult,
        override val completedAt: DataLoomInstant,
        public val remoteOutcome: StrategyRemoteOutcome,
        public val remoteAttempted: Boolean,
        public val cacheState: StrategyCacheState,
        public val primaryError: DataLoomError? = null,
        completedOperations: List<StrategyOperation> = emptyList(),
        public val partialOutput: StrategyTransportOutput? = null,
    ) : StrategySynchronizationExecutionResult {
        private val completedOperationsSnapshot: List<StrategyOperation> =
            completedOperations.toList()

        init {
            require(
                cacheState == StrategyCacheState.FRESH ||
                    cacheState == StrategyCacheState.STALE,
            ) {
                "Activated fallback requires FRESH or STALE cache state."
            }
            require(remoteAttempted == (primaryError != null)) {
                "Remote-attempt evidence and primaryError must agree."
            }
        }

        public val completedOperations: List<StrategyOperation>
            get() = completedOperationsSnapshot.toList()

        override fun equals(other: Any?): Boolean =
            other is FallbackActivated &&
                evaluation == other.evaluation &&
                completedAt == other.completedAt &&
                remoteOutcome == other.remoteOutcome &&
                remoteAttempted == other.remoteAttempted &&
                cacheState == other.cacheState &&
                primaryError == other.primaryError &&
                completedOperationsSnapshot == other.completedOperationsSnapshot &&
                partialOutput == other.partialOutput

        override fun hashCode(): Int {
            var result = evaluation.hashCode()
            result = (31 * result) + completedAt.hashCode()
            result = (31 * result) + remoteOutcome.hashCode()
            result = (31 * result) + remoteAttempted.hashCode()
            result = (31 * result) + cacheState.hashCode()
            result = (31 * result) + (primaryError?.hashCode() ?: 0)
            result = (31 * result) + completedOperationsSnapshot.hashCode()
            result = (31 * result) + (partialOutput?.hashCode() ?: 0)
            return result
        }

        override fun toString(): String =
            "StrategySynchronizationExecutionResult.FallbackActivated(" +
                "decisionId=${evaluation.decisionId}, " +
                "planId=${evaluation.plan.id}, " +
                "remoteOutcome=$remoteOutcome, " +
                "remoteAttempted=$remoteAttempted, " +
                "cacheState=$cacheState, " +
                "primaryErrorCode=${primaryError?.code}, " +
                "completedOperations=$completedOperationsSnapshot, " +
                "partialOutput=$partialOutput)"
    }

    /** Fallback was allowed but no acceptable local state was available. */
    public data class FallbackUnavailable(
        override val evaluation: StrategyEvaluationResult,
        override val completedAt: DataLoomInstant,
        public val remoteOutcome: StrategyRemoteOutcome,
        public val remoteAttempted: Boolean,
        public val localResult: StrategyLocalFallbackResult.Unavailable,
        public val primaryError: DataLoomError? = null,
        public val partialOutput: StrategyTransportOutput? = null,
    ) : StrategySynchronizationExecutionResult

    /** Cancellation remains terminal and is never converted into fallback. */
    public data class Cancelled(
        override val evaluation: StrategyEvaluationResult,
        override val completedAt: DataLoomInstant,
        public val output: StrategyTransportOutput,
    ) : StrategySynchronizationExecutionResult

    /**
     * Strategy admitted durable work instead of running transport directly.
     *
     * [queueEntryId] is non-null only when a [StrategyDurableQueueAdmitter]
     * was configured and real durable admission succeeded for this deferral.
     * When `null`, no queue provider was ever called — the deferral is a
     * pure in-memory signal, same as every `Deferred` result before durable
     * admission wiring existed; the caller is responsible for its own retry
     * strategy, exactly as documented for an unconfigured
     * [io.dataloom.runtime.submission.QueuedSynchronizationWorkEncoder].
     */
    public data class Deferred(
        override val evaluation: StrategyEvaluationResult,
        override val completedAt: DataLoomInstant,
        public val queueEntryId: QueueEntryId? = null,
    ) : StrategySynchronizationExecutionResult

    /**
     * A plan was durably admitted and that admission is the entire outcome
     * of this call — no synchronous transport ran.
     *
     * Distinct from [Deferred]: `Deferred` means the evaluator itself
     * decided not to attempt anything right now (typically connectivity
     * unavailable/unknown). `DurablyEnqueued` means the plan's disposition
     * was `EXECUTE`/`SERVE_AND_REFRESH` — the evaluator expected transport
     * to run — but the strategy in question (currently only
     * [OfflineFirstStrategyExecutor], and [HybridStrategyExecutor] for its
     * transport-free PUSH branch) treats durable admission as a complete
     * substitute for the synchronous attempt rather than running both,
     * to avoid a duplicate remote call once the durably admitted
     * continuation is later processed by a queue worker.
     */
    public data class DurablyEnqueued(
        override val evaluation: StrategyEvaluationResult,
        override val completedAt: DataLoomInstant,
        public val queueEntryId: QueueEntryId,
    ) : StrategySynchronizationExecutionResult

    /**
     * The plan's entire outcome was accepting local intent — no transport
     * ran, and nothing was durably admitted.
     *
     * Distinct from [DurablyEnqueued]: that variant means a queue provider
     * was actually called. This variant means the plan itself never
     * required transport or durable admission in the first place — a
     * `HybridSource.LOCAL` selection for a PUSH-direction request produces
     * operations `[READ_LOCAL]` only, meaning the local write was already
     * accepted by the time this plan was evaluated (the same `ACCEPT_LOCAL`
     * meaning [CacheFirstStrategyExecutor]/[OfflineFirstStrategyExecutor]
     * already treat as a no-op elsewhere), and `LOCAL` was explicitly chosen
     * over `REMOTE`, so no transport runs either. Accepting local state is
     * genuinely the entire outcome, not a placeholder for missing behavior.
     * Currently only reachable through [HybridStrategyExecutor]'s
     * PUSH-direction, `HybridSource.LOCAL`-selected branch without
     * `ENQUEUE_DURABLE_WORK`.
     */
    public data class AcceptedLocally(
        override val evaluation: StrategyEvaluationResult,
        override val completedAt: DataLoomInstant,
    ) : StrategySynchronizationExecutionResult

    /** Execution was rejected before a provider operation. */
    public class Rejected(
        override val evaluation: StrategyEvaluationResult,
        override val completedAt: DataLoomInstant,
        public val reason: StrategyExecutionRejectionReason,
        missingCapabilities: Set<StrategyProviderCapability> = emptySet(),
        bindingFailures: List<ProviderBindingFailure> = emptyList(),
    ) : StrategySynchronizationExecutionResult {
        private val missingSnapshot: Set<StrategyProviderCapability> =
            missingCapabilities.toSet()
        private val failuresSnapshot: List<ProviderBindingFailure> =
            bindingFailures.toList()

        public val missingCapabilities: Set<StrategyProviderCapability>
            get() = missingSnapshot.toSet()

        public val bindingFailures: List<ProviderBindingFailure>
            get() = failuresSnapshot.toList()

        override fun equals(other: Any?): Boolean =
            other is Rejected &&
                evaluation == other.evaluation &&
                completedAt == other.completedAt &&
                reason == other.reason &&
                missingSnapshot == other.missingSnapshot &&
                failuresSnapshot == other.failuresSnapshot

        override fun hashCode(): Int {
            var result = evaluation.hashCode()
            result = (31 * result) + completedAt.hashCode()
            result = (31 * result) + reason.hashCode()
            result = (31 * result) + missingSnapshot.hashCode()
            result = (31 * result) + failuresSnapshot.hashCode()
            return result
        }

        override fun toString(): String =
            "StrategySynchronizationExecutionResult.Rejected(" +
                "decisionId=${evaluation.decisionId}, " +
                "planId=${evaluation.plan.id}, " +
                "reason=$reason, " +
                "missingCapabilities=$missingSnapshot, " +
                "bindingFailureCount=${failuresSnapshot.size})"
    }
}
