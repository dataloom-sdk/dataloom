package io.dataloom.runtime.retry

import io.dataloom.api.error.DataLoomError
import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.api.retry.RetryBudgetState
import io.dataloom.api.retry.RetryDecision
import io.dataloom.api.retry.RetryPolicy
import io.dataloom.api.scheduling.ScheduleRequest
import io.dataloom.api.scheduling.SchedulerProvider
import io.dataloom.api.scheduling.SchedulingDelay
import io.dataloom.api.synchronization.SynchronizationResult
import io.dataloom.api.time.DataLoomClock
import io.dataloom.runtime.execution.lifecycle.SynchronizationRuntimeEventEmitter

/**
 * Scheduler-backed retry orchestrator with central protection, optional bounded
 * provider/server hints, and optional elapsed/cumulative budget enforcement.
 *
 * Hints are clamped before policy visibility and enforced as a minimum after
 * policy evaluation. Budgets evaluate the resulting final delay. Budget state
 * advances only after [SchedulerProvider.schedule] succeeds.
 */
public class SynchronizationRetryOrchestrator(
    private val retryPolicy: RetryPolicy,
    private val schedulerProvider: SchedulerProvider?,
    private val configuration: RetrySchedulingConfiguration,
    private val eventEmitter: SynchronizationRuntimeEventEmitter? = null,
) {
    private var budgetClock: DataLoomClock? = null
    private var budgetEvaluator: RetryBudgetEvaluator? = null
    private var hintEvaluator: RetryHintEvaluator? = null

    /** Creates an orchestrator with bounded provider/server hints. */
    public constructor(
        retryPolicy: RetryPolicy,
        schedulerProvider: SchedulerProvider?,
        configuration: RetrySchedulingConfiguration,
        hintConfiguration: RetryHintConfiguration,
        eventEmitter: SynchronizationRuntimeEventEmitter? = null,
    ) : this(
        retryPolicy = retryPolicy,
        schedulerProvider = schedulerProvider,
        configuration = configuration,
        eventEmitter = eventEmitter,
    ) {
        hintEvaluator = RetryHintEvaluator(hintConfiguration)
    }

    /** Creates an orchestrator with central [budgetConfiguration]. */
    public constructor(
        retryPolicy: RetryPolicy,
        schedulerProvider: SchedulerProvider?,
        configuration: RetrySchedulingConfiguration,
        clock: DataLoomClock,
        budgetConfiguration: RetryBudgetConfiguration,
        eventEmitter: SynchronizationRuntimeEventEmitter? = null,
    ) : this(
        retryPolicy = retryPolicy,
        schedulerProvider = schedulerProvider,
        configuration = configuration,
        eventEmitter = eventEmitter,
    ) {
        budgetClock = clock
        budgetEvaluator = RetryBudgetEvaluator(budgetConfiguration)
    }

    /** Creates an orchestrator with central budgets and bounded retry hints. */
    public constructor(
        retryPolicy: RetryPolicy,
        schedulerProvider: SchedulerProvider?,
        configuration: RetrySchedulingConfiguration,
        clock: DataLoomClock,
        budgetConfiguration: RetryBudgetConfiguration,
        hintConfiguration: RetryHintConfiguration,
        eventEmitter: SynchronizationRuntimeEventEmitter? = null,
    ) : this(
        retryPolicy = retryPolicy,
        schedulerProvider = schedulerProvider,
        configuration = configuration,
        eventEmitter = eventEmitter,
    ) {
        budgetClock = clock
        budgetEvaluator = RetryBudgetEvaluator(budgetConfiguration)
        hintEvaluator = RetryHintEvaluator(hintConfiguration)
    }

    /** Evaluates policy and schedules at most one future attempt. */
    public suspend fun evaluateAndSchedule(
        request: SynchronizationRetryRequest,
    ): RetryOrchestrationResult {
        val errors = extractRetryErrors(request.synchronizationResult)
            ?: return RetryOrchestrationResult(
                status = RetryOrchestrationStatus.NOT_REQUIRED,
                decisions = emptyList(),
                selectedDelay = null,
                scheduleReceipt = null,
                schedulerError = null,
            )

        val evaluated = evaluateRetryDecisions(
            retryPolicy = retryPolicy,
            synchronizationRequest = request.synchronizationRequest,
            retryOperation = request.retryOperation,
            retryAttempt = request.retryAttempt,
            errors = errors,
            hintEvaluator = hintEvaluator,
        )

        if (evaluated.blockingError != null) {
            return stopped(evaluated.decisions)
        }

        val maxDelay = selectMaxRetryDelay(evaluated.decisions)
            ?: return stopped(evaluated.decisions)

        val acceptedBudgetState = evaluateBudget(
            request = request,
            proposedDelay = maxDelay,
            decisionCount = evaluated.decisions.size,
        )
        if (acceptedBudgetState is BudgetResult.Stopped) {
            return stopped(
                List(evaluated.decisions.size) {
                    RetryDecision.Stop(reason = acceptedBudgetState.reason)
                },
            )
        }
        val nextBudgetState = (acceptedBudgetState as BudgetResult.Accepted).nextState

        if (schedulerProvider == null) {
            return RetryOrchestrationResult(
                status = RetryOrchestrationStatus.SCHEDULER_NOT_CONFIGURED,
                decisions = evaluated.decisions,
                selectedDelay = maxDelay,
                scheduleReceipt = null,
                schedulerError = null,
            )
        }

        val scheduleRequest = ScheduleRequest(
            id = request.scheduleId,
            synchronizationRequest = request.synchronizationRequest,
            delay = maxDelay,
            constraints = configuration.constraints,
            existingPolicy = configuration.existingSchedulePolicy,
        )

        return when (val result = schedulerProvider.schedule(scheduleRequest)) {
            is ProviderOperationResult.Success -> {
                if (eventEmitter != null) {
                    val primaryError = selectPrimaryRetryError(
                        errors = errors,
                        decisions = evaluated.decisions,
                        maxDelay = maxDelay,
                    )
                    if (primaryError != null) {
                        eventEmitter.emitRetryScheduled(
                            request = request.synchronizationRequest,
                            attempt = request.retryAttempt,
                            delay = maxDelay,
                            error = primaryError,
                        )
                    }
                }

                RetryOrchestrationResult(
                    status = RetryOrchestrationStatus.SCHEDULED,
                    decisions = evaluated.decisions,
                    selectedDelay = maxDelay,
                    scheduleReceipt = result.value,
                    schedulerError = null,
                    retryBudgetState = nextBudgetState,
                )
            }

            is ProviderOperationResult.Failure -> RetryOrchestrationResult(
                status = RetryOrchestrationStatus.SCHEDULER_FAILED,
                decisions = evaluated.decisions,
                selectedDelay = maxDelay,
                scheduleReceipt = null,
                schedulerError = result.error,
            )
        }
    }

    private fun evaluateBudget(
        request: SynchronizationRetryRequest,
        proposedDelay: SchedulingDelay,
        decisionCount: Int,
    ): BudgetResult {
        check(decisionCount > 0) { "Budget evaluation requires policy decisions." }
        val evaluator = budgetEvaluator ?: return BudgetResult.Accepted(null)
        val evaluatedAt = checkNotNull(budgetClock) {
            "A DataLoomClock is required when retry budgets are enabled."
        }.now()
        return when (val result = evaluator.evaluate(
            state = request.retryBudgetState,
            evaluatedAt = evaluatedAt,
            proposedDelay = proposedDelay,
        )) {
            is RetryBudgetEvaluation.Accepted -> BudgetResult.Accepted(result.nextState)
            is RetryBudgetEvaluation.Stopped -> BudgetResult.Stopped(result.reason)
        }
    }

    private fun stopped(decisions: List<RetryDecision>): RetryOrchestrationResult =
        RetryOrchestrationResult(
            status = RetryOrchestrationStatus.STOPPED,
            decisions = decisions,
            selectedDelay = null,
            scheduleReceipt = null,
            schedulerError = null,
        )

    private sealed interface BudgetResult {
        data class Accepted(val nextState: RetryBudgetState?) : BudgetResult
        data class Stopped(val reason: io.dataloom.api.retry.RetryStopReason) : BudgetResult
    }
}

private fun selectPrimaryRetryError(
    errors: List<DataLoomError>,
    decisions: List<RetryDecision>,
    maxDelay: SchedulingDelay,
): DataLoomError? =
    errors.zip(decisions)
        .firstOrNull { (_, decision) ->
            decision is RetryDecision.Retry &&
                decision.delay.milliseconds == maxDelay.milliseconds
        }
        ?.first
        ?: errors.zip(decisions)
            .firstOrNull { (_, decision) -> decision is RetryDecision.Retry }
            ?.first
