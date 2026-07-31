package io.dataloom.runtime.worker

import io.dataloom.api.circuit.CircuitBreakerScope
import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.api.scheduling.ScheduleRequest
import io.dataloom.api.scheduling.SchedulerProvider
import io.dataloom.api.scheduling.SchedulingDelay
import io.dataloom.api.time.DataLoomClock
import io.dataloom.api.time.DataLoomInstant
import io.dataloom.runtime.queue.CircuitBreakerDurableQueueExecutionProcessor
import io.dataloom.runtime.queue.CircuitBreakerQueueProcessingEngine
import io.dataloom.runtime.queue.CircuitBreakerQueueProcessingResult
import io.dataloom.runtime.queue.QueueCircuitPreExecutionDecision
import io.dataloom.runtime.queue.QueueCircuitProviderFailureDisposition
import io.dataloom.runtime.retry.CircuitBreakerExecutionResult
import io.dataloom.runtime.retry.CircuitBreakerQueueOperationAdapter
import io.dataloom.runtime.retry.CircuitBreakerRecordResult
import io.dataloom.runtime.retry.CircuitProtectedOperationResult
import io.dataloom.runtime.retry.QueueCircuitOperation

/**
 * Coordinates one circuit-aware queue-worker cycle.
 *
 * The coordinator optionally protects expired-lease recovery, delegates one
 * bounded acquisition/transition cycle to [queueProcessor], and schedules one
 * follow-up wake-up only after a normal processing result.
 *
 * Recovery, processing, and scheduling remain separate evidence boundaries.
 * A provider success followed by an unconfirmed circuit write is never
 * collapsed into a provider failure or replayed.
 */
public class CircuitBreakerQueueWorkerCoordinator internal constructor(
    private val queueOperationAdapter: CircuitBreakerQueueOperationAdapter,
    private val recoveryScope: CircuitBreakerScope,
    private val queueProcessor: CircuitBreakerQueueProcessingEngine,
    schedulerProvider: SchedulerProvider?,
    private val clock: DataLoomClock,
    private val configuration: QueueWorkerConfiguration,
) {

    private val schedulerProvider: SchedulerProvider? = assembleQueueWorkerSchedulerProvider(
        provider = schedulerProvider,
        timeout = configuration.schedulerProviderTimeout,
        clock = clock,
    )

    init {
        require(
            recoveryScope.providerId == null ||
                recoveryScope.providerId == queueOperationAdapter.descriptor.id,
        ) {
            "Queue-worker recovery circuit scope provider must match the queue provider."
        }
        require(
            recoveryScope.operation == null ||
                recoveryScope.operation == QueueCircuitOperation.RECOVER_EXPIRED_LEASES.retryOperation,
        ) {
            "Queue-worker recovery circuit scope operation must be queue.recover-expired-leases."
        }
    }

    /** Convenience constructor for the production circuit-aware queue processor. */
    public constructor(
        queueOperationAdapter: CircuitBreakerQueueOperationAdapter,
        recoveryScope: CircuitBreakerScope,
        queueProcessor: CircuitBreakerDurableQueueExecutionProcessor,
        schedulerProvider: SchedulerProvider?,
        clock: DataLoomClock,
        configuration: QueueWorkerConfiguration,
    ) : this(
        queueOperationAdapter = queueOperationAdapter,
        recoveryScope = recoveryScope,
        queueProcessor = CircuitBreakerQueueProcessingEngine { request ->
            queueProcessor.process(request)
        },
        schedulerProvider = schedulerProvider,
        clock = clock,
        configuration = configuration,
    )

    /** Executes at most one recovery, processing, and scheduling operation. */
    public suspend fun run(
        request: QueueWorkerRunRequest,
    ): CircuitBreakerQueueWorkerRunResult {
        if (configuration.recoverExpiredLeasesBeforeProcessing) {
            requireNotNull(request.recoveryRequest) {
                "QueueWorkerRunRequest.recoveryRequest must not be null when " +
                    "QueueWorkerConfiguration.recoverExpiredLeasesBeforeProcessing is true."
            }
        }

        val recoveryResult = if (configuration.recoverExpiredLeasesBeforeProcessing) {
            executeRecovery(request.recoveryRequest!!)
        } else {
            CircuitBreakerQueueWorkerRecoveryResult.NotRequested
        }

        if (!recoveryResult.allowsProcessing()) {
            return CircuitBreakerQueueWorkerRunResult.RecoveryStopped(recoveryResult)
        }

        val processingResult = queueProcessor.process(request.processingRequest)
        return when (processingResult) {
            is CircuitBreakerQueueProcessingResult.NoWork -> {
                CircuitBreakerQueueWorkerRunResult.ProcessingCompleted(
                    recoveryResult = recoveryResult,
                    processingResult = processingResult,
                    schedulingResult = executeScheduling(QueueWorkerWakeUpPlan.NoWakeUp),
                )
            }
            is CircuitBreakerQueueProcessingResult.Processed -> {
                CircuitBreakerQueueWorkerRunResult.ProcessingCompleted(
                    recoveryResult = recoveryResult,
                    processingResult = processingResult,
                    schedulingResult = executeScheduling(buildWakeUpPlan(processingResult)),
                )
            }
            is CircuitBreakerQueueProcessingResult.PreExecutionStopped,
            is CircuitBreakerQueueProcessingResult.ProviderFailure,
            is CircuitBreakerQueueProcessingResult.CircuitRecordingUnconfirmed,
            is CircuitBreakerQueueProcessingResult.QueueContractViolation,
            -> CircuitBreakerQueueWorkerRunResult.ProcessingStopped(
                recoveryResult = recoveryResult,
                processingResult = processingResult,
            )
        }
    }

    private suspend fun executeRecovery(
        request: io.dataloom.api.queue.ExpiredLeaseRecoveryRequest,
    ): CircuitBreakerQueueWorkerRecoveryResult {
        return when (
            val execution = queueOperationAdapter.recoverExpiredLeases(
                scope = recoveryScope,
                request = request,
            )
        ) {
            is CircuitBreakerExecutionResult.Rejected -> {
                CircuitBreakerQueueWorkerRecoveryResult.PreExecutionStopped(
                    QueueCircuitPreExecutionDecision.Rejected(
                        reason = execution.reason,
                        retryAt = execution.retryAt,
                    ),
                )
            }
            is CircuitBreakerExecutionResult.PermissionPersistenceFailure -> {
                CircuitBreakerQueueWorkerRecoveryResult.PreExecutionStopped(
                    QueueCircuitPreExecutionDecision.PermissionPersistenceFailure(execution.error),
                )
            }
            CircuitBreakerExecutionResult.PermissionContentionLimitReached -> {
                CircuitBreakerQueueWorkerRecoveryResult.PreExecutionStopped(
                    QueueCircuitPreExecutionDecision.PermissionContentionLimitReached,
                )
            }
            is CircuitBreakerExecutionResult.Executed -> {
                when (val providerResult = execution.operationResult) {
                    is CircuitProtectedOperationResult.Failure -> {
                        CircuitBreakerQueueWorkerRecoveryResult.ProviderFailure(
                            error = providerResult.error,
                            disposition = QueueCircuitProviderFailureDisposition.CIRCUIT_FAILURE,
                            recordResult = execution.recordResult,
                        )
                    }
                    is CircuitProtectedOperationResult.NonCircuitFailure -> {
                        CircuitBreakerQueueWorkerRecoveryResult.ProviderFailure(
                            error = providerResult.error,
                            disposition = QueueCircuitProviderFailureDisposition.NON_CIRCUIT_FAILURE,
                            recordResult = execution.recordResult,
                        )
                    }
                    is CircuitProtectedOperationResult.Success -> {
                        if (execution.recordResult.isAccepted()) {
                            CircuitBreakerQueueWorkerRecoveryResult.Completed(
                                result = providerResult.value,
                                recordResult = execution.recordResult,
                            )
                        } else {
                            CircuitBreakerQueueWorkerRecoveryResult.CircuitRecordingUnconfirmed(
                                result = providerResult.value,
                                recordResult = execution.recordResult,
                            )
                        }
                    }
                }
            }
        }
    }

    private fun buildWakeUpPlan(
        processed: CircuitBreakerQueueProcessingResult.Processed,
    ): QueueWorkerWakeUpPlan {
        val limitReached = processed.acquisitionLimitReached
        val futureAvailability = earliestFutureAvailability(processed)

        return when {
            !limitReached && futureAvailability == null -> QueueWorkerWakeUpPlan.NoWakeUp
            limitReached && futureAvailability == null -> schedulePlan(
                reason = QueueWorkerWakeUpReason.ACQUISITION_LIMIT_REACHED,
                delay = configuration.continuationDelay,
            )
            !limitReached && futureAvailability != null -> schedulePlan(
                reason = futureAvailability.reason,
                delay = calculateAvailabilityDelay(futureAvailability.availableAt),
            )
            else -> {
                val futureDelay = calculateAvailabilityDelay(futureAvailability!!.availableAt)
                schedulePlan(
                    reason = QueueWorkerWakeUpReason.BOTH,
                    delay = if (
                        configuration.continuationDelay.milliseconds <= futureDelay.milliseconds
                    ) {
                        configuration.continuationDelay
                    } else {
                        futureDelay
                    },
                )
            }
        }
    }

    private fun schedulePlan(
        reason: QueueWorkerWakeUpReason,
        delay: SchedulingDelay,
    ): QueueWorkerWakeUpPlan.Schedule = QueueWorkerWakeUpPlan.Schedule(
        reason = reason,
        delay = delay,
        scheduleId = configuration.scheduleId,
        constraints = configuration.constraints,
        existingSchedulePolicy = configuration.existingSchedulePolicy,
    )

    private fun earliestFutureAvailability(
        processed: CircuitBreakerQueueProcessingResult.Processed,
    ): FutureAvailability? {
        val retryAt = processed.earliestRescheduledAt
        val deferredAt = processed.earliestDeferredAt
        return when {
            retryAt == null && deferredAt == null -> null
            retryAt != null && deferredAt == null -> FutureAvailability(
                retryAt,
                QueueWorkerWakeUpReason.RESCHEDULED_ENTRY_AVAILABLE,
            )
            retryAt == null && deferredAt != null -> FutureAvailability(
                deferredAt,
                QueueWorkerWakeUpReason.DEFERRED_ENTRY_AVAILABLE,
            )
            retryAt!!.epochMilliseconds <= deferredAt!!.epochMilliseconds -> FutureAvailability(
                retryAt,
                QueueWorkerWakeUpReason.RETRY_AND_DEFERRAL_AVAILABLE,
            )
            else -> FutureAvailability(
                deferredAt,
                QueueWorkerWakeUpReason.RETRY_AND_DEFERRAL_AVAILABLE,
            )
        }
    }

    private fun calculateAvailabilityDelay(
        availableAt: DataLoomInstant,
    ): SchedulingDelay {
        val delay = availableAt.epochMilliseconds - clock.now().epochMilliseconds
        return if (delay <= 0L) SchedulingDelay.ZERO else SchedulingDelay(delay)
    }

    private suspend fun executeScheduling(
        plan: QueueWorkerWakeUpPlan,
    ): QueueWorkerSchedulingResult {
        if (plan is QueueWorkerWakeUpPlan.NoWakeUp) {
            return QueueWorkerSchedulingResult.NotRequired
        }
        val schedulePlan = plan as QueueWorkerWakeUpPlan.Schedule
        val scheduler = schedulerProvider
            ?: return QueueWorkerSchedulingResult.SchedulerNotConfigured(schedulePlan)
        val request = ScheduleRequest(
            id = schedulePlan.scheduleId,
            synchronizationRequest = null,
            delay = schedulePlan.delay,
            constraints = schedulePlan.constraints,
            existingPolicy = schedulePlan.existingSchedulePolicy,
        )
        return when (val result = scheduler.schedule(request)) {
            is ProviderOperationResult.Success -> QueueWorkerSchedulingResult.Scheduled(
                receipt = result.value,
                plan = schedulePlan,
            )
            is ProviderOperationResult.Failure -> QueueWorkerSchedulingResult.SchedulerFailed(
                error = result.error,
                plan = schedulePlan,
            )
        }
    }

    private fun CircuitBreakerRecordResult.isAccepted(): Boolean =
        this is CircuitBreakerRecordResult.Recorded ||
            this is CircuitBreakerRecordResult.Ignored

    private data class FutureAvailability(
        val availableAt: DataLoomInstant,
        val reason: QueueWorkerWakeUpReason,
    )
}
