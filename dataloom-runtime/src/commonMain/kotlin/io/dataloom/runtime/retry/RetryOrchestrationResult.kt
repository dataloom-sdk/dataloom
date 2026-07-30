package io.dataloom.runtime.retry

import io.dataloom.api.error.DataLoomError
import io.dataloom.api.retry.RetryBudgetState
import io.dataloom.api.retry.RetryDecision
import io.dataloom.api.scheduling.ScheduleReceipt
import io.dataloom.api.scheduling.SchedulingDelay

/**
 * Immutable result of one scheduler-backed retry orchestration cycle.
 *
 * The original five-argument constructor is preserved. The six-argument
 * constructor carries [retryBudgetState] only when the scheduler accepted the
 * retry. Missing or failed scheduling never advances durable budget state.
 */
public class RetryOrchestrationResult(
    public val status: RetryOrchestrationStatus,
    decisions: List<RetryDecision>,
    public val selectedDelay: SchedulingDelay?,
    public val scheduleReceipt: ScheduleReceipt?,
    public val schedulerError: DataLoomError?,
) {
    private val _decisions: List<RetryDecision> = decisions.toList()
    private var storedRetryBudgetState: RetryBudgetState? = null

    public val decisions: List<RetryDecision>
        get() = _decisions

    /** Exact next state to persist after scheduler acceptance, or null. */
    public val retryBudgetState: RetryBudgetState?
        get() = storedRetryBudgetState

    /** Creates a result with accepted retry-budget state. */
    public constructor(
        status: RetryOrchestrationStatus,
        decisions: List<RetryDecision>,
        selectedDelay: SchedulingDelay?,
        scheduleReceipt: ScheduleReceipt?,
        schedulerError: DataLoomError?,
        retryBudgetState: RetryBudgetState?,
    ) : this(
        status = status,
        decisions = decisions,
        selectedDelay = selectedDelay,
        scheduleReceipt = scheduleReceipt,
        schedulerError = schedulerError,
    ) {
        require(retryBudgetState == null || status == RetryOrchestrationStatus.SCHEDULED) {
            "retryBudgetState may be returned only for SCHEDULED results."
        }
        storedRetryBudgetState = retryBudgetState
    }

    init {
        val hasRetryDecision = _decisions.any { it is RetryDecision.Retry }
        when (status) {
            RetryOrchestrationStatus.NOT_REQUIRED -> {
                require(_decisions.isEmpty()) {
                    "NOT_REQUIRED result must have an empty decisions list."
                }
                require(selectedDelay == null && scheduleReceipt == null && schedulerError == null) {
                    "NOT_REQUIRED result must not contain scheduling evidence."
                }
            }
            RetryOrchestrationStatus.STOPPED -> {
                require(_decisions.isNotEmpty() && !hasRetryDecision) {
                    "STOPPED result requires non-empty Stop decisions."
                }
                require(selectedDelay == null && scheduleReceipt == null && schedulerError == null) {
                    "STOPPED result must not contain scheduling evidence."
                }
            }
            RetryOrchestrationStatus.SCHEDULED -> {
                require(hasRetryDecision && selectedDelay != null && scheduleReceipt != null) {
                    "SCHEDULED result requires retry decisions, delay, and receipt."
                }
                require(schedulerError == null) {
                    "SCHEDULED result must not contain schedulerError."
                }
            }
            RetryOrchestrationStatus.SCHEDULER_NOT_CONFIGURED -> {
                require(hasRetryDecision && selectedDelay != null) {
                    "SCHEDULER_NOT_CONFIGURED requires retry decisions and delay."
                }
                require(scheduleReceipt == null && schedulerError == null) {
                    "SCHEDULER_NOT_CONFIGURED must not contain scheduler evidence."
                }
            }
            RetryOrchestrationStatus.SCHEDULER_FAILED -> {
                require(hasRetryDecision && selectedDelay != null && schedulerError != null) {
                    "SCHEDULER_FAILED requires retry decisions, delay, and schedulerError."
                }
                require(scheduleReceipt == null) {
                    "SCHEDULER_FAILED must not contain a receipt."
                }
            }
        }
    }

    override fun toString(): String =
        "RetryOrchestrationResult(" +
            "status=$status, " +
            "decisionCount=${_decisions.size}, " +
            "selectedDelay=$selectedDelay, " +
            "scheduleReceiptId=${scheduleReceipt?.id?.value}, " +
            "schedulerErrorCode=${schedulerError?.code?.value}, " +
            "budgeted=${storedRetryBudgetState != null}" +
            ")"
}
