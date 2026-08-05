package io.dataloom.runtime.strategy

import io.dataloom.api.error.DataLoomError
import io.dataloom.api.strategy.StrategyOperation
import io.dataloom.api.strategy.StrategyRemoteOutcome
import io.dataloom.api.strategy.StrategyTransportOutput
import io.dataloom.api.synchronization.SynchronizationResult
import io.dataloom.api.synchronization.SynchronizationSkipReason
import io.dataloom.api.time.DataLoomInstant

/** Terminal disposition of one foreground cache-first refresh attempt. */
public enum class StrategyCacheInlineRefreshDisposition {
    COMPLETED,
    PARTIALLY_SUCCEEDED,
    FAILED,
    CANCELLED,
}

/**
 * Payload-free terminal evidence for a refresh attempted after local cache use.
 *
 * Domain values remain application-owned. Provider-backed synchronization
 * output contains only the canonical request, summary, timestamps, and errors.
 * Completion time and terminal error are derived from that canonical output so
 * duplicate contradictory evidence cannot be supplied by a caller.
 */
public sealed interface StrategyCacheInlineRefreshResult {
    public val disposition: StrategyCacheInlineRefreshDisposition
    public val completedAt: DataLoomInstant

    /** The inline refresh reached complete success or a canonical no-change result. */
    public data class Completed(
        public val output: StrategyTransportOutput.ProviderBacked,
    ) : StrategyCacheInlineRefreshResult {
        init {
            require(
                output.result is SynchronizationResult.Succeeded ||
                    (
                        output.result is SynchronizationResult.Skipped &&
                            output.result.reason == SynchronizationSkipReason.NO_CHANGES
                        ),
            ) {
                "Completed inline cache refresh requires succeeded or no-change output."
            }
        }

        override val disposition: StrategyCacheInlineRefreshDisposition =
            StrategyCacheInlineRefreshDisposition.COMPLETED

        override val completedAt: DataLoomInstant
            get() = output.result.completedAt

        override fun toString(): String =
            "StrategyCacheInlineRefreshResult.Completed(" +
                "result=${synchronizationStatus(output.result)}, " +
                "disposition=$disposition)"
    }

    /**
     * The inline refresh committed some work but retained canonical unresolved
     * errors. Local cache-serving evidence must remain visible separately.
     */
    public data class PartiallySucceeded(
        public val output: StrategyTransportOutput.ProviderBacked,
    ) : StrategyCacheInlineRefreshResult {
        init {
            require(output.result is SynchronizationResult.PartiallySucceeded) {
                "Partially succeeded inline cache refresh requires canonical partial output."
            }
        }

        override val disposition: StrategyCacheInlineRefreshDisposition =
            StrategyCacheInlineRefreshDisposition.PARTIALLY_SUCCEEDED

        override val completedAt: DataLoomInstant
            get() = output.result.completedAt

        override fun toString(): String {
            val partial = output.result as SynchronizationResult.PartiallySucceeded
            return "StrategyCacheInlineRefreshResult.PartiallySucceeded(" +
                "errorCount=${partial.errors.size}, disposition=$disposition)"
        }
    }

    /**
     * The cache remained available, but its inline remote refresh failed.
     *
     * [completedOperations] preserves already completed effects so the caller
     * does not blindly replay them. The list is defensively copied. [error] and
     * [completedAt] are derived from [output] and cannot disagree with it.
     */
    public class Failed(
        public val transportAttempted: Boolean,
        completedOperations: List<StrategyOperation> = emptyList(),
        public val output: StrategyTransportOutput.ProviderBacked,
        public val remoteOutcome: StrategyRemoteOutcome? = null,
    ) : StrategyCacheInlineRefreshResult {
        private val completedOperationsSnapshot: List<StrategyOperation> =
            completedOperations.toList()
        private val failedOutput: SynchronizationResult.Failed =
            requireNotNull(output.result as? SynchronizationResult.Failed) {
                "Failed inline cache refresh requires canonical failed output."
            }

        init {
            require(remoteOutcome == null || transportAttempted) {
                "A classified remote outcome requires a transport attempt."
            }
            require(
                transportAttempted || completedOperationsSnapshot.none {
                    it == StrategyOperation.PUSH_REMOTE ||
                        it == StrategyOperation.PULL_REMOTE
                },
            ) {
                "Completed remote operations require a transport attempt."
            }
        }

        override val disposition: StrategyCacheInlineRefreshDisposition =
            StrategyCacheInlineRefreshDisposition.FAILED

        override val completedAt: DataLoomInstant
            get() = failedOutput.completedAt

        public val error: DataLoomError
            get() = failedOutput.error

        public val completedOperations: List<StrategyOperation>
            get() = completedOperationsSnapshot.toList()

        override fun equals(other: Any?): Boolean =
            other is Failed &&
                transportAttempted == other.transportAttempted &&
                completedOperationsSnapshot == other.completedOperationsSnapshot &&
                output == other.output &&
                remoteOutcome == other.remoteOutcome

        override fun hashCode(): Int {
            var result = transportAttempted.hashCode()
            result = (31 * result) + completedOperationsSnapshot.hashCode()
            result = (31 * result) + output.hashCode()
            result = (31 * result) + (remoteOutcome?.hashCode() ?: 0)
            return result
        }

        override fun toString(): String =
            "StrategyCacheInlineRefreshResult.Failed(" +
                "errorCode=${error.code}, " +
                "transportAttempted=$transportAttempted, " +
                "completedOperations=$completedOperationsSnapshot, " +
                "remoteOutcome=$remoteOutcome, disposition=$disposition)"
    }

    /** The pipeline returned its explicit canonical cancellation result. */
    public data class Cancelled(
        public val output: StrategyTransportOutput.ProviderBacked,
    ) : StrategyCacheInlineRefreshResult {
        init {
            require(output.result is SynchronizationResult.Cancelled) {
                "Cancelled inline cache refresh requires canonical cancelled output."
            }
        }

        override val disposition: StrategyCacheInlineRefreshDisposition =
            StrategyCacheInlineRefreshDisposition.CANCELLED

        override val completedAt: DataLoomInstant
            get() = output.result.completedAt

        override fun toString(): String =
            "StrategyCacheInlineRefreshResult.Cancelled(disposition=$disposition)"
    }
}

private fun synchronizationStatus(result: SynchronizationResult): String = when (result) {
    is SynchronizationResult.Succeeded -> "SUCCEEDED"
    is SynchronizationResult.PartiallySucceeded -> "PARTIALLY_SUCCEEDED"
    is SynchronizationResult.Failed -> "FAILED"
    is SynchronizationResult.Cancelled -> "CANCELLED"
    is SynchronizationResult.Skipped -> "SKIPPED"
}
