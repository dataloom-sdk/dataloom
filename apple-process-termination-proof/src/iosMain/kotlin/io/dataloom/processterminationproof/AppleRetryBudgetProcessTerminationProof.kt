@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.dataloom.processterminationproof

import io.dataloom.api.context.ExecutionContext
import io.dataloom.api.error.DataLoomError
import io.dataloom.api.error.ErrorCategory
import io.dataloom.api.error.ErrorCode
import io.dataloom.api.error.ErrorSeverity
import io.dataloom.api.error.Recoverability
import io.dataloom.api.identifier.CorrelationId
import io.dataloom.api.identifier.ExecutionId
import io.dataloom.api.identifier.QueueConsumerId
import io.dataloom.api.identifier.QueueEntryId
import io.dataloom.api.identifier.QueueLeaseId
import io.dataloom.api.identifier.SynchronizationSessionId
import io.dataloom.api.identifier.WorkflowId
import io.dataloom.api.model.SynchronizationDirection
import io.dataloom.api.model.SynchronizationMode
import io.dataloom.api.model.SynchronizationRequest
import io.dataloom.api.model.WorkflowPriority
import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.api.queue.QueueAcquireRequest
import io.dataloom.api.queue.QueueAcquireResult
import io.dataloom.api.queue.QueueDeferralReason
import io.dataloom.api.queue.QueueDeferralRequest
import io.dataloom.api.queue.QueueEnqueueRequest
import io.dataloom.api.queue.QueueEntry
import io.dataloom.api.queue.QueueEntryState
import io.dataloom.api.queue.QueueRescheduleRequest
import io.dataloom.api.retry.RetryAttempt
import io.dataloom.api.retry.RetryBudgetState
import io.dataloom.api.scheduling.SchedulingDelay
import io.dataloom.api.time.DataLoomInstant
import io.dataloom.runtime.queue.AppleFileQueueProvider
import kotlinx.coroutines.runBlocking
import platform.posix.F_OK
import platform.posix.access

/**
 * Real production write/read path exercised by the Apple Simulator
 * process-termination CI proof app, for `#94`'s "prove real Apple process
 * termination/relaunch" gap -- extended here from circuit-breaker state
 * (see [AppleCircuitBreakerProcessTerminationProof]) to durable retry-budget
 * state, mirroring how the Android retry-budget proof
 * (`AndroidProcessTerminationRetryBudgetInstrumentedTest`) mechanically
 * extended the Android circuit-breaker proof.
 *
 * This is a Kotlin `object`, exported to Objective-C/Swift as a class with a
 * `shared` singleton accessor -- from Swift:
 * `AppleRetryBudgetProcessTerminationProof.shared.writeRetryBudgetAndPersist(directoryPath:)`.
 *
 * ## What this drives
 *
 * Retry-budget state is a genuinely separate durable structure from
 * circuit-breaker state on both platforms: on Android it lives in the
 * `retry_attempt_number`/`retry_window_started_at_ms`/
 * `retry_last_evaluated_at_ms`/`retry_cumulative_delay_ms` columns of the
 * `queue_entries` table, written and read exclusively through
 * `RoomQueueProvider`'s `enqueue`/`acquire`/`reschedule`/`defer` operations --
 * never through the independent `circuit_breaker_states` table
 * `RoomCircuitBreakerStateStore` owns. The real Apple analog is exactly the
 * same shape: `io.dataloom.api.queue.QueueEntry.retryAttempt` and
 * `QueueEntry.retryBudgetState`, persisted by the real production
 * [AppleFileQueueProvider]
 * (`dataloom-runtime/src/iosMain/kotlin/io/dataloom/runtime/queue/AppleFileQueueProvider.kt`)
 * -- never `AppleFileCircuitBreakerStateStore`. This was verified directly by
 * reading `QueueEntry`'s own declared properties and `AppleFileQueueProvider`'s
 * `reschedule` implementation, not assumed from Android's column names.
 *
 * [writeRetryBudgetAndPersist] drives the exact same real production
 * `enqueue -> acquire -> reschedule -> acquire -> defer` sequence Android's
 * `RetryBudgetProcessTerminationContentProvider` drives through
 * `RoomQueueProvider`, against the caller-supplied directory:
 *
 * 1. `enqueue` a fresh `PENDING` entry with no retry state.
 * 2. `acquire` it under lease `lease-open-1`.
 * 3. `reschedule` it with a genuine [RetryAttempt] and [RetryBudgetState],
 *    returning it to `RETRY_WAITING` and clearing the lease.
 * 4. `acquire` it again under lease `lease-confirm-1` -- an independent
 *    production read of exactly what was persisted, not merely an echo of
 *    what was requested.
 * 5. `defer` it to release the confirmation lease while preserving the
 *    persisted retry history exactly, leaving the entry at rest as
 *    `RETRY_WAITING`, unleased, with its retry-budget state intact -- the
 *    stable on-disk shape the CI proof's outside file diff compares
 *    byte-for-byte before the kill and after the relaunch.
 *
 * [hasPersistedRetryBudgetState] is a non-mutating existence check the proof
 * app uses to decide whether [writeRetryBudgetAndPersist] has already run
 * against a given directory (see its own KDoc for why this cannot reuse
 * [AppleCircuitBreakerProcessTerminationProof]'s own `readPersistedState`
 * pattern).
 *
 * ## Scope reduction versus the Android precedent
 *
 * Android's `RetryBudgetProcessTerminationContentProvider` drives its
 * `enqueue -> acquire -> reschedule -> acquire -> defer` sequence through the
 * full `RoomQueueProvider` production coordinator, exactly as this object
 * drives [AppleFileQueueProvider] -- there is no further scope reduction to
 * make here, unlike [AppleCircuitBreakerProcessTerminationProof]'s own
 * reduction versus `CircuitBreakerExecutionGate`/`CircuitBreakerCoordinator`.
 * `QueueProvider` has no coordinator/execution-gate layer of its own that
 * this proof would otherwise need to bypass.
 *
 * ## Gate re-drive after relaunch
 *
 * [redriveAcquireGateAfterRelaunch] closes the gap named in
 * `docs/status/market-readiness.md`'s `#94` row ("Apple's circuit-breaker
 * and retry-budget kill/relaunch proofs still only check raw persisted
 * state and never re-drive the real gate"): it mirrors
 * `RetryBudgetProcessTerminationContentProvider.readRetryBudget`'s own
 * post-relaunch gate-check exactly -- one real [AppleFileQueueProvider.acquire]
 * call one millisecond *before* the persisted `availableAt` (asserting the
 * real gate still reports [QueueAcquireResult.NoEntries]), followed by a
 * real acquire *at* `availableAt` whose returned entry's fields -- including
 * `availableAt` itself -- are returned for the caller to assert equal to
 * what [writeRetryBudgetAndPersist] originally persisted.
 */
public object AppleRetryBudgetProcessTerminationProof {

    /**
     * Drives the real production `enqueue -> acquire -> reschedule ->
     * acquire -> defer` sequence through a new [AppleFileQueueProvider]
     * rooted at [directoryPath] and returns the retry-budget state read back
     * by the confirmation acquisition (step 4 above), before the entry is
     * deferred back to rest. Throws (via Kotlin `error`) if any step
     * unexpectedly fails -- this proof harness intentionally does not
     * swallow or retry failures, matching this repository's "fail loudly
     * rather than assume success" testing discipline.
     *
     * Must be called at most once against a given [directoryPath]: a second
     * call would enqueue a duplicate entry id and fail. Callers must guard
     * repeat invocations with [hasPersistedRetryBudgetState].
     */
    public fun writeRetryBudgetAndPersist(
        directoryPath: String,
    ): RetryBudgetProcessTerminationProofState = runBlocking {
        val provider = AppleFileQueueProvider(directoryPath)
        val entryId = QueueEntryId(ENTRY_ID)

        provider.enqueue(QueueEnqueueRequest(freshEntry(entryId)))
            .requireSuccess("enqueue")

        acquireSingle(provider, LEASE_OPEN_ID, FIRST_ACQUIRE_AT_MS, FIRST_LEASE_EXPIRES_AT_MS)

        provider.reschedule(
            QueueRescheduleRequest(
                entryId = entryId,
                leaseId = QueueLeaseId(LEASE_OPEN_ID),
                retryAttempt = RetryAttempt(RETRY_ATTEMPT_NUMBER),
                availableAt = DataLoomInstant(RESCHEDULE_AVAILABLE_AT_MS),
                error = InjectedRetryFailure,
                retryBudgetState = RetryBudgetState(
                    windowStartedAt = DataLoomInstant(RETRY_WINDOW_STARTED_AT_MS),
                    lastEvaluatedAt = DataLoomInstant(RETRY_LAST_EVALUATED_AT_MS),
                    cumulativeDelay = SchedulingDelay(RETRY_CUMULATIVE_DELAY_MS),
                ),
            ),
        ).requireSuccess("reschedule")

        val confirmed = acquireSingle(
            provider,
            LEASE_CONFIRM_ID,
            RESCHEDULE_AVAILABLE_AT_MS,
            RESCHEDULE_AVAILABLE_AT_MS + LEASE_DURATION_MS,
        )

        provider.defer(
            QueueDeferralRequest(
                entryId = entryId,
                leaseId = QueueLeaseId(LEASE_CONFIRM_ID),
                availableAt = DataLoomInstant(RESCHEDULE_AVAILABLE_AT_MS),
                reason = QueueDeferralReason.CONNECTIVITY_REQUIREMENT_NOT_MET,
            ),
        ).requireSuccess("defer")

        confirmed.toProofState()
    }

    /**
     * Non-mutating existence check for whether [writeRetryBudgetAndPersist]
     * has already persisted state under [directoryPath].
     *
     * Unlike [AppleCircuitBreakerProcessTerminationProof.readPersistedState]
     * -- which is safe to call as a pure idempotency guard because
     * `AppleFileCircuitBreakerStateStore.load` never mutates state --
     * [AppleFileQueueProvider] has no read-only "peek" operation: its only
     * public read path is `acquire`, which atomically assigns a new lease as
     * a side effect. Calling `acquire` from an idempotency guard would itself
     * mutate the persisted snapshot (attaching a lease) every time the proof
     * app launches, which would make the CI proof's own outside byte-for-byte
     * file diff observe a spurious change across the kill/relaunch even when
     * the underlying retry-budget fields never changed.
     *
     * Instead, this checks for the well-known on-disk snapshot file's
     * existence directly -- the same [AppleFileQueueProvider.DEFAULT_FILE_NAME]
     * the real provider itself writes to, referenced here (not duplicated) so
     * this check can never drift from the provider's own file name. This
     * performs no provider operation and leaves any existing snapshot
     * completely unmodified.
     */
    public fun hasPersistedRetryBudgetState(directoryPath: String): Boolean {
        val path = "$directoryPath/${AppleFileQueueProvider.DEFAULT_FILE_NAME}"
        return access(path, F_OK) == 0
    }

    /**
     * Re-drives the real [AppleFileQueueProvider.acquire] gate against the
     * entry [writeRetryBudgetAndPersist] already persisted at
     * [directoryPath] -- not just a raw on-disk file diff -- mirroring
     * `RetryBudgetProcessTerminationContentProvider.readRetryBudget`'s own
     * two steps exactly:
     *
     * 1. One real `acquire` call one millisecond *before* the persisted
     *    `availableAt`, asserting the real gate still reports
     *    [QueueAcquireResult.NoEntries] -- proof that the relaunched
     *    process's persisted retry-budget/`availableAt` state is genuinely
     *    honored by production acquisition logic post-relaunch, not merely
     *    present on disk and never re-checked.
     * 2. A real `acquire` *at* `availableAt`, which must find the entry
     *    eligible; its fields (including `availableAt` itself) are returned
     *    for the caller to assert equal to what was persisted before the
     *    kill.
     *
     * Must be called only after [writeRetryBudgetAndPersist] has already
     * persisted retry-budget state for [directoryPath] -- in the Simulator
     * proof app this is the app's *second* launch (post-relaunch);
     * [writeRetryBudgetAndPersist] itself only ever runs on the first (see
     * [hasPersistedRetryBudgetState]'s own idempotency-guard role). Throws
     * (via Kotlin `error`/`check`) if either step does not hold, matching
     * this repository's "fail loudly rather than assume success" testing
     * discipline.
     *
     * Unlike [writeRetryBudgetAndPersist] this never calls `defer`: Android's
     * own `readRetryBudget` content-provider method leaves the entry leased
     * under its `lease-after-relaunch` id too, and this mirrors that
     * exactly rather than adding an extra round-trip Android's own proof
     * does not make.
     */
    public fun redriveAcquireGateAfterRelaunch(
        directoryPath: String,
    ): RetryBudgetProcessTerminationProofState = runBlocking {
        val provider = AppleFileQueueProvider(directoryPath)

        val gateCheck = provider.acquire(
            QueueAcquireRequest(
                consumerId = QueueConsumerId("$CONSUMER_ID-gate-check"),
                leaseId = QueueLeaseId("lease-gate-check"),
                acquiredAt = DataLoomInstant(RESCHEDULE_AVAILABLE_AT_MS - 1L),
                leaseExpiresAt = DataLoomInstant(RESCHEDULE_AVAILABLE_AT_MS - 1L + LEASE_DURATION_MS),
                maxEntries = 1,
            ),
        )
        val gateCheckResult = (gateCheck as? ProviderOperationResult.Success<QueueAcquireResult>)
            ?: error("Retry-budget gate-check acquire failed unexpectedly: $gateCheck")
        check(gateCheckResult.value is QueueAcquireResult.NoEntries) {
            "Expected the relaunched process's real acquire gate to still report the " +
                "retry-budget process-termination-proof entry as ineligible one millisecond " +
                "before its persisted availableAt, but found: ${gateCheckResult.value}"
        }

        val entry = acquireSingle(
            provider,
            "lease-after-relaunch",
            RESCHEDULE_AVAILABLE_AT_MS,
            RESCHEDULE_AVAILABLE_AT_MS + LEASE_DURATION_MS,
        )
        entry.toProofState()
    }

    private suspend fun acquireSingle(
        provider: AppleFileQueueProvider,
        leaseId: String,
        acquiredAtMs: Long,
        leaseExpiresAtMs: Long,
    ): QueueEntry {
        val result = provider.acquire(
            QueueAcquireRequest(
                consumerId = QueueConsumerId(CONSUMER_ID),
                leaseId = QueueLeaseId(leaseId),
                acquiredAt = DataLoomInstant(acquiredAtMs),
                leaseExpiresAt = DataLoomInstant(leaseExpiresAtMs),
                maxEntries = 1,
            ),
        )
        val success = (result as? ProviderOperationResult.Success<QueueAcquireResult>)
            ?: error("Failed to acquire the retry-budget process-termination-proof entry: $result")
        val entries = (success.value as? QueueAcquireResult.Entries)
            ?: error(
                "Expected an eligible retry-budget process-termination-proof entry but found " +
                    "none: ${success.value}",
            )
        return entries.entries.single()
    }

    private fun <T> ProviderOperationResult<T>.requireSuccess(step: String): T = when (this) {
        is ProviderOperationResult.Success -> value
        is ProviderOperationResult.Failure ->
            error("Retry-budget process-termination-proof $step failed: ${error}")
    }

    private fun freshEntry(entryId: QueueEntryId): QueueEntry = QueueEntry(
        id = entryId,
        synchronizationRequest = SynchronizationRequest(
            workflowId = WorkflowId("workflow-process-termination-proof-retry-budget"),
            sessionId = SynchronizationSessionId("session-process-termination-proof-retry-budget"),
            direction = SynchronizationDirection.PUSH,
            mode = SynchronizationMode.DELTA,
            priority = WorkflowPriority.NORMAL,
            context = ExecutionContext(
                executionId = ExecutionId("execution-process-termination-proof-retry-budget"),
                correlationId = CorrelationId("correlation-process-termination-proof-retry-budget"),
            ),
        ),
        state = QueueEntryState.PENDING,
        enqueuedAt = DataLoomInstant(ENQUEUED_AT_MS),
        availableAt = DataLoomInstant(ENQUEUED_AT_MS),
    )

    private fun QueueEntry.toProofState(): RetryBudgetProcessTerminationProofState {
        val attempt = requireNotNull(retryAttempt) {
            "Acquired retry-budget process-termination-proof entry has no persisted retryAttempt."
        }
        val budget = requireNotNull(retryBudgetState) {
            "Acquired retry-budget process-termination-proof entry has no persisted retryBudgetState."
        }
        return RetryBudgetProcessTerminationProofState(
            retryAttemptNumber = attempt.number,
            retryWindowStartedAtEpochMillis = budget.windowStartedAt.epochMilliseconds,
            retryLastEvaluatedAtEpochMillis = budget.lastEvaluatedAt.epochMilliseconds,
            retryCumulativeDelayMillis = budget.cumulativeDelay.milliseconds,
            availableAtEpochMillis = availableAt.epochMilliseconds,
        )
    }

    /**
     * Sanitized injected failure driving the proof's own [reschedule] call,
     * mirroring `RetryBudgetProcessTerminationContentProvider`'s own
     * `InjectedRetryFailure` exactly -- a stable, non-sensitive
     * [DataLoomError] value with no cause chain.
     */
    private object InjectedRetryFailure : DataLoomError {
        override val code: ErrorCode = ErrorCode("RETRY_BUDGET_PROOF_INJECTED_TRANSPORT_FAILURE")
        override val category: ErrorCategory = ErrorCategory.NETWORK
        override val severity: ErrorSeverity = ErrorSeverity.WARNING
        override val recoverability: Recoverability = Recoverability.RECOVERABLE
        override val message: String =
            "Sanitized injected failure for process-kill retry-budget proof."
        override val cause: Throwable? = null
    }

    private const val ENTRY_ID = "process-termination-proof-retry-budget-entry"
    private const val CONSUMER_ID = "process-termination-proof-retry-budget-consumer"
    private const val LEASE_OPEN_ID = "lease-open-1"
    private const val LEASE_CONFIRM_ID = "lease-confirm-1"
    private const val ENQUEUED_AT_MS: Long = 500L
    private const val FIRST_ACQUIRE_AT_MS: Long = 1_000L
    private const val FIRST_LEASE_EXPIRES_AT_MS: Long = 1_500L
    private const val RETRY_ATTEMPT_NUMBER: Int = 3
    private const val RETRY_WINDOW_STARTED_AT_MS: Long = 1_100L
    private const val RETRY_LAST_EVALUATED_AT_MS: Long = 1_200L
    private const val RETRY_CUMULATIVE_DELAY_MS: Long = 750L
    private const val RESCHEDULE_AVAILABLE_AT_MS: Long = 1_300L
    private const val LEASE_DURATION_MS: Long = 500L
}
