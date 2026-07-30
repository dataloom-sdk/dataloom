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
 * provider/server hints, optional elapsed/cumulative budget enforcement, and an
 * opt-in scheduler-provider timeout boundary.
 *
 * Hints are clamped before policy visibility and enforced as a minimum after
 * policy evaluation. Budgets evaluate the resulting final delay. Budget state
 * advances only after [SchedulerProvider.schedule] succeeds.
 *
 * Existing constructors preserve the historical direct scheduler invocation.
 * [withSchedulerProviderTimeout] creates an orchestrator whose single scheduler
 * call is protected by [CoroutineRetryTimeoutExecutor] through
 * [TimeoutEnforcingSchedulerProvider]. Construction performs no clock read,
 * provider call, coroutine launch, or scheduling operation.
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

        val scheduler = schedulerProvider
        if (scheduler == null) {
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

        return when (val result = scheduler.schedule(scheduleRequest)) {
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

    public companion object {
        /**
         * Creates an orchestrator whose scheduler invocation is bounded by
         * [schedulerProviderTimeout].
         *
         * The timeout applies only to the single
         * [io.dataloom.api.scheduling.SchedulerProvider.schedule] call made after
         * retry protection, policy evaluation, hint enforcement, delay selection,
         * and optional budget evaluation. A timeout returns
         * [RetryOrchestrationStatus.SCHEDULER_FAILED] with the bounded canonical
         * `SCHEDULER_PROVIDER_TIMEOUT` error. It never advances retry-budget state
         * and never emits `RetryScheduled`.
         *
         * [budgetConfiguration] and [hintConfiguration] preserve the same
         * semantics as the corresponding public constructors. The supplied
         * [clock] is used for retry budgets when configured and to assemble the
         * provider-timeout boundary. Construction does not read it.
         *
         * Coroutine cancellation is cooperative. Blocking scheduler
         * implementations without cancellation checkpoints require a
         * platform-specific hard-interruption adapter.
         */
        public fun withSchedulerProviderTimeout(
            retryPolicy: RetryPolicy,
            schedulerProvider: SchedulerProvider?,
            configuration: RetrySchedulingConfiguration,
            clock: DataLoomClock,
            schedulerProviderTimeout: SchedulingDelay,
            budgetConfiguration: RetryBudgetConfiguration? = null,
            hintConfiguration: RetryHintConfiguration? = null,
            eventEmitter: SynchronizationRuntimeEventEmitter? = null,
        ): SynchronizationRetryOrchestrator {
            val timeoutScheduler = schedulerProvider?.let { provider ->
                TimeoutEnforcingSchedulerProvider(
                    delegate = provider,
                    timeoutCoordinator = RetryTimeoutCoordinator(
                        configuration = RetryTimeoutConfiguration(
                            providerTimeout = schedulerProviderTimeout,
                        ),
                        clock = clock,
                        executor = CoroutineRetryTimeoutExecutor(),
                    ),
                )
            }

            return when {
                budgetConfiguration != null && hintConfiguration != null ->
                    SynchronizationRetryOrchestrator(
                        retryPolicy = retryPolicy,
                        schedulerProvider = timeoutScheduler,
                        configuration = configuration,
                        clock = clock,
                        budgetConfiguration = budgetConfiguration,
                        hintConfiguration = hintConfiguration,
                        eventEmitter = eventEmitter,
                    )

                budgetConfiguration != null -> SynchronizationRetryOrchestrator(
                    retryPolicy = retryPolicy,
                    schedulerProvider = timeoutScheduler,
                    configuration = configuration,
                    clock = clock,
                    budgetConfiguration = budgetConfiguration,
                    eventEmitter = eventEmitter,
                )

                hintConfiguration != null -> SynchronizationRetryOrchestrator(
                    retryPolicy = retryPolicy,
                    schedulerProvider = timeoutScheduler,
                    configuration = configuration,
                    hintConfiguration = hintConfiguration,
                    eventEmitter = eventEmitter,
                )

                else -> SynchronizationRetryOrchestrator(
                    retryPolicy = retryPolicy,
                    schedulerProvider = timeoutScheduler,
                    configuration = configuration,
                    eventEmitter = eventEmitter,
                )
            }
        }
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
