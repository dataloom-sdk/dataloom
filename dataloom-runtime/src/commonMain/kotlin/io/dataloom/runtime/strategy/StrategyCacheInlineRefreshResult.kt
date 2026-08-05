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

    /** The inline PULL refresh reached success or a canonical no-change result. */
    public class Completed(
        completedOperations: List<StrategyOperation>,
        public val output: StrategyTransportOutput.ProviderBacked,
    ) : StrategyCacheInlineRefreshResult {
        private val completedOperationsSnapshot: List<StrategyOperation> =
            completedOperations.toList()

        init {
            val result = output.result
            require(
                result is SynchronizationResult.Succeeded ||
                    (
                        result is SynchronizationResult.Skipped &&
                            result.reason == SynchronizationSkipReason.NO_CHANGES
                        ),
            ) {
                "Completed inline cache refresh requires succeeded or no-change output."
            }
            requirePullRefreshOperations(completedOperationsSnapshot)
            require(completedOperationsSnapshot == COMPLETED_PULL_EVIDENCE) {
                "Completed inline cache refresh requires one completed remote pull marker."
            }
        }

        override val disposition: StrategyCacheInlineRefreshDisposition =
            StrategyCacheInlineRefreshDisposition.COMPLETED

        override val completedAt: DataLoomInstant
            get() = output.result.completedAt

        public val transportAttempted: Boolean = true

        public val completedOperations: List<StrategyOperation>
            get() = completedOperationsSnapshot.toList()

        override fun equals(other: Any?): Boolean =
            other is Completed &&
                completedOperationsSnapshot == other.completedOperationsSnapshot &&
                output == other.output

        override fun hashCode(): Int {
            var result = completedOperationsSnapshot.hashCode()
            result = (31 * result) + output.hashCode()
            return result
        }

        override fun toString(): String =
            "StrategyCacheInlineRefreshResult.Completed(" +
                "result=${synchronizationStatus(output.result)}, " +
                "completedOperations=$completedOperationsSnapshot, " +
                "disposition=$disposition)"
    }

    /**
     * The inline PULL refresh committed work but retained unresolved errors.
     * Completed remote effects remain visible so they are not blindly replayed.
     */
    public class PartiallySucceeded(
        completedOperations: List<StrategyOperation>,
        public val output: StrategyTransportOutput.ProviderBacked,
    ) : StrategyCacheInlineRefreshResult {
        private val completedOperationsSnapshot: List<StrategyOperation> =
            completedOperations.toList()
        private val partialOutput: SynchronizationResult.PartiallySucceeded =
            requireNotNull(output.result as? SynchronizationResult.PartiallySucceeded) {
                "Partially succeeded inline cache refresh requires canonical partial output."
            }

        init {
            requirePullRefreshOperations(completedOperationsSnapshot)
            require(completedOperationsSnapshot == COMPLETED_PULL_EVIDENCE) {
                "Partially succeeded inline cache refresh requires one completed remote pull marker."
            }
        }

        override val disposition: StrategyCacheInlineRefreshDisposition =
            StrategyCacheInlineRefreshDisposition.PARTIALLY_SUCCEEDED

        override val completedAt: DataLoomInstant
            get() = partialOutput.completedAt

        public val transportAttempted: Boolean = true

        public val completedOperations: List<StrategyOperation>
            get() = completedOperationsSnapshot.toList()

        override fun equals(other: Any?): Boolean =
            other is PartiallySucceeded &&
                completedOperationsSnapshot == other.completedOperationsSnapshot &&
                output == other.output

        override fun hashCode(): Int {
            var result = completedOperationsSnapshot.hashCode()
            result = (31 * result) + output.hashCode()
            return result
        }

        override fun toString(): String =
            "StrategyCacheInlineRefreshResult.PartiallySucceeded(" +
                "errorCount=${partialOutput.errors.size}, " +
                "completedOperations=$completedOperationsSnapshot, " +
                "disposition=$disposition)"
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
            requireTransportEvidence(
                transportAttempted = transportAttempted,
                completedOperations = completedOperationsSnapshot,
            )
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
    public class Cancelled(
        public val transportAttempted: Boolean,
        completedOperations: List<StrategyOperation> = emptyList(),
        public val output: StrategyTransportOutput.ProviderBacked,
    ) : StrategyCacheInlineRefreshResult {
        private val completedOperationsSnapshot: List<StrategyOperation> =
            completedOperations.toList()

        init {
            require(output.result is SynchronizationResult.Cancelled) {
                "Cancelled inline cache refresh requires canonical cancelled output."
            }
            requireTransportEvidence(
                transportAttempted = transportAttempted,
                completedOperations = completedOperationsSnapshot,
            )
        }

        override val disposition: StrategyCacheInlineRefreshDisposition =
            StrategyCacheInlineRefreshDisposition.CANCELLED

        override val completedAt: DataLoomInstant
            get() = output.result.completedAt

        public val completedOperations: List<StrategyOperation>
            get() = completedOperationsSnapshot.toList()

        override fun equals(other: Any?): Boolean =
            other is Cancelled &&
                transportAttempted == other.transportAttempted &&
                completedOperationsSnapshot == other.completedOperationsSnapshot &&
                output == other.output

        override fun hashCode(): Int {
            var result = transportAttempted.hashCode()
            result = (31 * result) + completedOperationsSnapshot.hashCode()
            result = (31 * result) + output.hashCode()
            return result
        }

        override fun toString(): String =
            "StrategyCacheInlineRefreshResult.Cancelled(" +
                "transportAttempted=$transportAttempted, " +
                "completedOperations=$completedOperationsSnapshot, " +
                "disposition=$disposition)"
    }
}

private val COMPLETED_PULL_EVIDENCE: List<StrategyOperation> =
    listOf(StrategyOperation.PULL_REMOTE)

private fun requireTransportEvidence(
    transportAttempted: Boolean,
    completedOperations: List<StrategyOperation>,
) {
    requirePullRefreshOperations(completedOperations)
    require(
        transportAttempted || completedOperations.isEmpty(),
    ) {
        "Completed remote operations require a transport attempt."
    }
}

private fun requirePullRefreshOperations(
    completedOperations: List<StrategyOperation>,
) {
    require(
        completedOperations.isEmpty() || completedOperations == COMPLETED_PULL_EVIDENCE,
    ) {
        "Inline cache PULL refresh evidence must be empty or one PULL_REMOTE marker."
    }
}

private fun synchronizationStatus(result: SynchronizationResult): String = when (result) {
    is SynchronizationResult.Succeeded -> "SUCCEEDED"
    is SynchronizationResult.PartiallySucceeded -> "PARTIALLY_SUCCEEDED"
    is SynchronizationResult.Failed -> "FAILED"
    is SynchronizationResult.Cancelled -> "CANCELLED"
    is SynchronizationResult.Skipped -> "SKIPPED"
}
