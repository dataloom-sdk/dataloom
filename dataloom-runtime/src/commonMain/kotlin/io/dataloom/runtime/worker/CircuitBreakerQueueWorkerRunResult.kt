package io.dataloom.runtime.worker

import io.dataloom.runtime.queue.CircuitBreakerQueueProcessingResult

/** Terminal result of one circuit-aware queue-worker coordination cycle. */
public sealed interface CircuitBreakerQueueWorkerRunResult {

    /** Recovery did not reach an accepted success; processing and scheduling did not run. */
    public data class RecoveryStopped(
        public val recoveryResult: CircuitBreakerQueueWorkerRecoveryResult,
    ) : CircuitBreakerQueueWorkerRunResult {
        init {
            require(!recoveryResult.allowsProcessing()) {
                "RecoveryStopped requires a terminal recovery result."
            }
        }
    }

    /** Processing stopped on a circuit, provider, recording, or contract result. */
    public data class ProcessingStopped(
        public val recoveryResult: CircuitBreakerQueueWorkerRecoveryResult,
        public val processingResult: CircuitBreakerQueueProcessingResult,
    ) : CircuitBreakerQueueWorkerRunResult {
        init {
            require(recoveryResult.allowsProcessing()) {
                "ProcessingStopped requires accepted or disabled recovery."
            }
            require(
                processingResult !is CircuitBreakerQueueProcessingResult.NoWork &&
                    processingResult !is CircuitBreakerQueueProcessingResult.Processed,
            ) {
                "ProcessingStopped requires a terminal processing result."
            }
        }
    }

    /** Normal processing completed and the exact scheduling outcome is available. */
    public data class ProcessingCompleted(
        public val recoveryResult: CircuitBreakerQueueWorkerRecoveryResult,
        public val processingResult: CircuitBreakerQueueProcessingResult,
        public val schedulingResult: QueueWorkerSchedulingResult,
    ) : CircuitBreakerQueueWorkerRunResult {
        init {
            require(recoveryResult.allowsProcessing()) {
                "ProcessingCompleted requires accepted or disabled recovery."
            }
            require(
                processingResult is CircuitBreakerQueueProcessingResult.NoWork ||
                    processingResult is CircuitBreakerQueueProcessingResult.Processed,
            ) {
                "ProcessingCompleted requires a normal processing result."
            }
        }
    }
}
