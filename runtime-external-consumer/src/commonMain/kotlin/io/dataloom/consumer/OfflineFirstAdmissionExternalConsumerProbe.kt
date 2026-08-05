package io.dataloom.consumer

import io.dataloom.api.identifier.QueueEntryId
import io.dataloom.api.strategy.StrategyOperationInput
import io.dataloom.runtime.strategy.StrategyOfflineFirstAdmissionDisposition
import io.dataloom.runtime.strategy.StrategySynchronizationExecutionResult

/** Compile-only use of atomic offline-first admission from an external module. */
internal fun compileOfflineFirstAdmissionConsumer(
    queueEntryId: QueueEntryId,
    result: StrategySynchronizationExecutionResult,
): StrategyOperationInput.OfflineFirstAdmission {
    val input = StrategyOperationInput.OfflineFirstAdmission(
        queueEntryId = queueEntryId,
        idempotencyKey = "external-intent",
    )
    input.idempotencyKey
    if (result is StrategySynchronizationExecutionResult.Deferred) {
        val disposition: StrategyOfflineFirstAdmissionDisposition? =
            result.admissionDisposition
        disposition?.name
    }
    return input
}
