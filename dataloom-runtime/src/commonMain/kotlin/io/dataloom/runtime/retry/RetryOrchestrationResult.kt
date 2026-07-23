package io.dataloom.runtime.retry

import io.dataloom.api.error.DataLoomError
import io.dataloom.api.retry.RetryDecision
import io.dataloom.api.scheduling.ScheduleReceipt
import io.dataloom.api.scheduling.SchedulingDelay

/**
 * Immutable structured result produced by a single
 * [SynchronizationRetryOrchestrator.evaluateAndSchedule] invocation.
 *
 * ## Purpose
 *
 * [RetryOrchestrationResult] preserves sufficient information for a future
 * event layer to observe and act on the outcome of retry orchestration without
 * re-evaluating policy or re-inspecting the original error.
 *
 * ## Invariants per status
 *
 * ### [RetryOrchestrationStatus.NOT_REQUIRED]
 *
 * - [decisions] is empty.
 * - [selectedDelay] is `null`.
 * - [scheduleReceipt] is `null`.
 * - [schedulerError] is `null`.
 *
 * ### [RetryOrchestrationStatus.STOPPED]
 *
 * - [decisions] is non-empty.
 * - No decision in [decisions] requests retry.
 * - [selectedDelay] is `null`.
 * - [scheduleReceipt] is `null`.
 * - [schedulerError] is `null`.
 *
 * ### [RetryOrchestrationStatus.SCHEDULED]
 *
 * - At least one decision in [decisions] requests retry.
 * - [selectedDelay] is non-`null`.
 * - [scheduleReceipt] is non-`null`.
 * - [schedulerError] is `null`.
 *
 * ### [RetryOrchestrationStatus.SCHEDULER_NOT_CONFIGURED]
 *
 * - At least one decision in [decisions] requests retry.
 * - [selectedDelay] is non-`null`.
 * - [scheduleReceipt] is `null`.
 * - [schedulerError] is `null`.
 *
 * ### [RetryOrchestrationStatus.SCHEDULER_FAILED]
 *
 * - At least one decision in [decisions] requests retry.
 * - [selectedDelay] is non-`null`.
 * - [scheduleReceipt] is `null`.
 * - [schedulerError] is non-`null`.
 *
 * ## Collection contract
 *
 * The [decisions] collection is defensively copied from the supplied list at
 * construction. Caller mutation of the original list does not affect the
 * result. The exposed collection is read-only.
 *
 * ## Sensitive-data restrictions
 *
 * No raw [Throwable] is exposed. No stack trace is exposed. No provider
 * internal state is exposed. [schedulerError] is a canonical
 * [DataLoomError] whose [DataLoomError.message] must already be sanitized by
 * the provider.
 *
 * ## KMP compatibility
 *
 * Uses Kotlin standard-library and DataLoom API types only. Safe for use in
 * Kotlin Multiplatform common code.
 *
 * @param status the canonical terminal status of this orchestration cycle.
 * @param decisions ordered [RetryDecision] values produced by policy
 *   evaluation. Empty when [status] is [RetryOrchestrationStatus.NOT_REQUIRED].
 *   Defensively copied.
 * @param selectedDelay the maximum [SchedulingDelay] selected from all retry
 *   decisions. Non-`null` when scheduling was attempted or a scheduler was
 *   absent.
 * @param scheduleReceipt the [ScheduleReceipt] returned by
 *   [io.dataloom.api.scheduling.SchedulerProvider] on success. Non-`null`
 *   only when [status] is [RetryOrchestrationStatus.SCHEDULED].
 * @param schedulerError the canonical [DataLoomError] returned by the
 *   scheduler on failure. Non-`null` only when [status] is
 *   [RetryOrchestrationStatus.SCHEDULER_FAILED].
 */
public class RetryOrchestrationResult(
    /** The canonical terminal status of this orchestration cycle. */
    public val status: RetryOrchestrationStatus,

    decisions: List<RetryDecision>,

    /**
     * The maximum [SchedulingDelay] selected across all retry decisions, or
     * `null` when no retry was requested or evaluation was not required.
     */
    public val selectedDelay: SchedulingDelay?,

    /**
     * The [ScheduleReceipt] returned by
     * [io.dataloom.api.scheduling.SchedulerProvider] on success.
     *
     * Non-`null` only when [status] is [RetryOrchestrationStatus.SCHEDULED].
     */
    public val scheduleReceipt: ScheduleReceipt?,

    /**
     * The canonical [DataLoomError] returned by the scheduler on failure.
     *
     * Non-`null` only when [status] is
     * [RetryOrchestrationStatus.SCHEDULER_FAILED].
     */
    public val schedulerError: DataLoomError?,
) {
    private val _decisions: List<RetryDecision> = decisions.toList()

    /**
     * Ordered [RetryDecision] values produced by policy evaluation.
     *
     * Empty when [status] is [RetryOrchestrationStatus.NOT_REQUIRED]. The
     * collection is a defensive copy; caller mutation of the original list
     * does not affect this result.
     */
    public val decisions: List<RetryDecision>
        get() = _decisions

    init {
        val hasRetryDecision = _decisions.any { it is RetryDecision.Retry }
        when (status) {
            RetryOrchestrationStatus.NOT_REQUIRED -> {
                require(_decisions.isEmpty()) {
                    "NOT_REQUIRED result must have an empty decisions list."
                }
                require(selectedDelay == null) {
                    "NOT_REQUIRED result must have a null selectedDelay."
                }
                require(scheduleReceipt == null) {
                    "NOT_REQUIRED result must have a null scheduleReceipt."
                }
                require(schedulerError == null) {
                    "NOT_REQUIRED result must have a null schedulerError."
                }
            }
            RetryOrchestrationStatus.STOPPED -> {
                require(_decisions.isNotEmpty()) {
                    "STOPPED result must have a non-empty decisions list."
                }
                require(!hasRetryDecision) {
                    "STOPPED result must not contain a Retry decision."
                }
                require(selectedDelay == null) {
                    "STOPPED result must have a null selectedDelay."
                }
                require(scheduleReceipt == null) {
                    "STOPPED result must have a null scheduleReceipt."
                }
                require(schedulerError == null) {
                    "STOPPED result must have a null schedulerError."
                }
            }
            RetryOrchestrationStatus.SCHEDULED -> {
                require(hasRetryDecision) {
                    "SCHEDULED result must contain at least one Retry decision."
                }
                require(selectedDelay != null) {
                    "SCHEDULED result must have a non-null selectedDelay."
                }
                require(scheduleReceipt != null) {
                    "SCHEDULED result must have a non-null scheduleReceipt."
                }
                require(schedulerError == null) {
                    "SCHEDULED result must have a null schedulerError."
                }
            }
            RetryOrchestrationStatus.SCHEDULER_NOT_CONFIGURED -> {
                require(hasRetryDecision) {
                    "SCHEDULER_NOT_CONFIGURED result must contain at least one Retry decision."
                }
                require(selectedDelay != null) {
                    "SCHEDULER_NOT_CONFIGURED result must have a non-null selectedDelay."
                }
                require(scheduleReceipt == null) {
                    "SCHEDULER_NOT_CONFIGURED result must have a null scheduleReceipt."
                }
                require(schedulerError == null) {
                    "SCHEDULER_NOT_CONFIGURED result must have a null schedulerError."
                }
            }
            RetryOrchestrationStatus.SCHEDULER_FAILED -> {
                require(hasRetryDecision) {
                    "SCHEDULER_FAILED result must contain at least one Retry decision."
                }
                require(selectedDelay != null) {
                    "SCHEDULER_FAILED result must have a non-null selectedDelay."
                }
                require(scheduleReceipt == null) {
                    "SCHEDULER_FAILED result must have a null scheduleReceipt."
                }
                require(schedulerError != null) {
                    "SCHEDULER_FAILED result must have a non-null schedulerError."
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
            "schedulerErrorCode=${schedulerError?.code?.value}" +
            ")"
}
