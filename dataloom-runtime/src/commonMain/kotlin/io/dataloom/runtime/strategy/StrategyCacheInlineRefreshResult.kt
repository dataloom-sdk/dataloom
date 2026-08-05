package io.dataloom.runtime.strategy

import io.dataloom.api.error.DataLoomError
import io.dataloom.api.strategy.StrategyOperation
import io.dataloom.api.strategy.StrategyRemoteOutcome
import io.dataloom.api.strategy.StrategyTransportOutput
import io.dataloom.api.synchronization.SynchronizationResult
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
 */
public sealed interface StrategyCacheInlineRefreshResult {
    public val disposition: StrategyCacheInlineRefreshDisposition
    public val completedAt: DataLoomInstant

    /** The inline refresh reached a complete successful or no-change result. */
    public data class Completed(
        override val completedAt: DataLoomInstant,
        public val output: StrategyTransportOutput.ProviderBacked,
    ) : StrategyCacheInlineRefreshResult {
        init {
            require(
                output.result is SynchronizationResult.Succeeded ||
                    output.result is SynchronizationResult.Skipped,
            ) {
                "Completed inline cache refresh requires a succeeded or skipped output."
            }
        }

        override val disposition: StrategyCacheInlineRefreshDisposition =
            StrategyCacheInlineRefreshDisposition.COMPLETED

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
        override val completedAt: DataLoomInstant,
        public val output: StrategyTransportOutput.ProviderBacked,
    ) : StrategyCacheInlineRefreshResult {
        init {
            require(output.result is SynchronizationResult.PartiallySucceeded) {
                "Partially succeeded inline cache refresh requires canonical partial output."
            }
        }

        override val disposition: StrategyCacheInlineRefreshDisposition =
            StrategyCacheInlineRefreshDisposition.PARTIALLY_SUCCEEDED

        override fun toString(): String {
            val partial = output.result as SynchronizationResult.PartiallySucceeded
            return "StrategyCacheInlineRefreshResult.PartiallySucceeded(" +
                "errorCount=${partial.errors.size}, disposition=$disposition)"
        }
    }

    /**
     * The cache remained available, but its inline remote refresh failed.
     *
     * [completedOperations] preserves already completed remote effects so the
     * caller does not blindly replay them. The list is defensively copied.
     */
    public class Failed(
        override val completedAt: DataLoomInstant,
        public val error: DataLoomError,
        public val transportAttempted: Boolean,
        completedOperations: List<StrategyOperation> = emptyList(),
        public val partialOutput: StrategyTransportOutput.ProviderBacked,
        public val remoteOutcome: StrategyRemoteOutcome? = null,
    ) : StrategyCacheInlineRefreshResult {
        private val completedOperationsSnapshot: List<StrategyOperation> =
            completedOperations.toList()

        init {
            val failed = partialOutput.result as? SynchronizationResult.Failed
            require(failed != null && failed.error == error) {
                "Failed inline cache refresh requires matching canonical failed output."
            }
        }

        override val disposition: StrategyCacheInlineRefreshDisposition =
            StrategyCacheInlineRefreshDisposition.FAILED

        public val completedOperations: List<StrategyOperation>
            get() = completedOperationsSnapshot.toList()

        override fun equals(other: Any?): Boolean =
            other is Failed &&
                completedAt == other.completedAt &&
                error == other.error &&
                transportAttempted == other.transportAttempted &&
                completedOperationsSnapshot == other.completedOperationsSnapshot &&
                partialOutput == other.partialOutput &&
                remoteOutcome == other.remoteOutcome

        override fun hashCode(): Int {
            var result = completedAt.hashCode()
            result = (31 * result) + error.hashCode()
            result = (31 * result) + transportAttempted.hashCode()
            result = (31 * result) + completedOperationsSnapshot.hashCode()
            result = (31 * result) + partialOutput.hashCode()
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
        override val completedAt: DataLoomInstant,
        public val output: StrategyTransportOutput.ProviderBacked,
    ) : StrategyCacheInlineRefreshResult {
        init {
            require(output.result is SynchronizationResult.Cancelled) {
                "Cancelled inline cache refresh requires canonical cancelled output."
            }
        }

        override val disposition: StrategyCacheInlineRefreshDisposition =
            StrategyCacheInlineRefreshDisposition.CANCELLED

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
