@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.dataloom.processcontentionproof

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

/**
 * Real production Apple cross-process contention proof for `#94`'s
 * retry-budget durable structure -- extending
 * [AppleCircuitBreakerProbeContentionProof]'s/[AppleUnresolvedConflictLogContentionProof]'s
 * own proven two-process racing mechanism (see
 * [AppleCircuitBreakerProbeContentionProof]'s own KDoc for the full "how two
 * genuinely separate Simulator app processes race on one file" and
 * "file-based polling substitutes for `CyclicBarrier`" explanations, not
 * repeated here) to a domain **with no Android contention-specific
 * precedent to mirror**.
 *
 * ## Why this domain has no Android test to port
 *
 * Unlike `#95`'s two conflict-log domains (each with a direct
 * `AndroidUnresolvedConflictLogContentionInstrumentedTest`/
 * `AndroidResolvedConflictDecisionLogContentionInstrumentedTest` precedent to
 * mirror), `dataloom-queue-room`'s `androidTest` directory has no
 * lease-acquisition or retry-budget contention test of any kind -- only
 * `AndroidProcessTerminationRetryBudgetInstrumentedTest`, a single-process
 * kill/relaunch proof, and `AndroidCircuitBreakerProbeContentionInstrumentedTest`,
 * a different durable structure entirely (`circuit_breaker_states`, not
 * `queue_entries`). This was confirmed by searching
 * `dataloom-queue-room/src/androidTest` directly, not assumed. This proof's
 * race shape below was therefore designed directly from
 * [AppleFileQueueProvider]'s own real `acquire` implementation, not ported
 * from an existing test on either platform.
 *
 * ## The real contendable operation: `acquire`'s eligibility-then-lease critical section
 *
 * [AppleFileQueueProvider.acquire] is not an optimistic
 * compare-and-set-with-retry the way
 * [io.dataloom.runtime.retry.AppleFileCircuitBreakerStateStore]'s and
 * [io.dataloom.runtime.state.AppleFileDurableStateStore]'s own
 * `compareAndSet` methods are (read a version, attempt to write, retry on a
 * losing `Conflict`). Instead, the *entire* read-eligible-entries/assign-lease/
 * write-snapshot sequence runs inside one real cross-process `flock`
 * (`appleQueueWithExclusiveLock`, held for the full critical section, not
 * released and reacquired). This was verified directly by reading
 * [AppleFileQueueProvider.acquire]'s source, not assumed from the
 * circuit-breaker/conflict-log domains' own shape.
 *
 * That pessimistic-lock shape still produces a genuinely bounded,
 * unambiguous one-winner/one-loser split when two real OS processes race
 * [AppleFileQueueProvider.acquire] for the *same single eligible entry*
 * (`maxEntries = 1`, one matching `QueueEntry` in the snapshot): whichever
 * process's `flock(LOCK_EX)` call the kernel grants first runs its entire
 * critical section -- reads the entry as eligible
 * (`RETRY_WAITING`/`PENDING` with `availableAt <= acquiredAt`), atomically
 * transitions it to `LEASED` with its own lease, and writes that back to the
 * real on-disk snapshot -- before the second process's `flock` call can even
 * return. The second process, once the kernel finally grants *its* `flock`
 * call, reads the now-updated snapshot and finds the entry no longer
 * eligible (state is `LEASED`, not `PENDING`/`RETRY_WAITING`), so its own
 * `eligible` sequence is empty and it returns
 * [QueueAcquireResult.NoEntries]. Exactly one process observes
 * [QueueAcquireResult.Entries]; the other, deterministically and
 * unambiguously, observes [QueueAcquireResult.NoEntries] -- never both,
 * never neither. Real mutual exclusion is enforced by the provider's own
 * unmodified `flock`-based lock, exactly the way the circuit-breaker and
 * conflict-log domains' own compare-and-set is enforced by their own
 * unmodified `flock`-based locks, never a test-only mutex.
 *
 * ## Tying the race to retry-budget state specifically, not just lease acquisition
 *
 * A bare "two processes race to acquire the same fresh `PENDING` entry" proof
 * would demonstrate `acquire`'s own lease contention, but nothing specific to
 * `#94`'s retry-budget durable structure (`QueueEntry.retryAttempt`/
 * `QueueEntry.retryBudgetState`). This proof instead seeds the single
 * raced-for entry with real, already-persisted retry-budget state *before*
 * either process races for it -- via the identical real production
 * `enqueue -> acquire -> reschedule` sequence
 * [io.dataloom.processterminationproof.AppleRetryBudgetProcessTerminationProof]
 * already drives for `#94`'s single-process kill/relaunch proof -- so the
 * entry both processes race for is genuinely `RETRY_WAITING` carrying a real
 * [RetryAttempt] and [RetryBudgetState] persisted through
 * [AppleFileQueueProvider.reschedule], not a fresh work item with no retry
 * history. The winning process's own `acquire` call then reads back those
 * exact retry-budget fields as part of its atomically-acquired
 * [QueueEntry] -- [waitForGoSignalThenAttemptAcquire] reports them directly
 * so the CI job can assert they survived the race byte-for-byte, proving the
 * durable retry-budget structure itself, not just the lease, is what the
 * real cross-process contention exercised.
 *
 * ## Asymmetric seeder/racer shape, like the circuit-breaker domain
 *
 * Seeding retry-budget state requires real setup work (`enqueue`, an
 * `acquire`, then a `reschedule`) that only one process can meaningfully
 * perform once, exactly the shape
 * [AppleCircuitBreakerProbeContentionProof]'s own asymmetric opener/racer
 * split already established (there, "opening the circuit"; here, "seeding
 * the `RETRY_WAITING` entry"). Unlike the two `#95` conflict-log domains
 * (symmetric: both processes attempt the identical mutating operation), this
 * domain reuses the circuit-breaker domain's own asymmetric shape: one
 * process ([seedRetryWaitingEntryAndSignalReady]) performs the one-time
 * setup and signals ready; the other simply signals ready directly (no
 * Kotlin call of its own, mirroring
 * `AppleCircuitBreakerProbeContentionProof`'s own non-opening racer, done
 * straight from Swift). *Both* processes then race
 * [waitForGoSignalThenAttemptAcquire] identically.
 */
public object AppleRetryBudgetLeaseContentionProof {

    /**
     * Drives the real production `enqueue -> acquire -> reschedule` sequence
     * through a fresh [AppleFileQueueProvider] rooted at [directoryPath],
     * leaving a single entry ([ENTRY_ID]) at rest as `RETRY_WAITING`,
     * unleased, carrying a real persisted [RetryAttempt]/[RetryBudgetState]
     * and an `availableAt` already in the past relative to
     * [RACE_ACQUIRE_AT_EPOCH_MILLIS] (the fixed instant both racing
     * processes later use), so the entry is unconditionally eligible for
     * both processes' race attempt. Touches [readyMarkerPath] once seeding
     * completes so the host CI script can confirm this process finished
     * setup and is about to enter its wait loop.
     *
     * Called only by the "seeder" app process (`ProcessContentionProofAppA`,
     * `DATALOOM_ROLE=A`); the non-seeding racer
     * (`ProcessContentionProofAppB`, `DATALOOM_ROLE=B`) touches its own ready
     * marker directly from Swift instead, mirroring
     * [AppleCircuitBreakerProbeContentionProof.openCircuitAndSignalReady]'s
     * own precedent exactly.
     *
     * Must be called at most once against a given [directoryPath] -- a
     * second call would enqueue a duplicate entry id and fail, matching
     * [io.dataloom.processterminationproof.AppleRetryBudgetProcessTerminationProof.writeRetryBudgetAndPersist]'s
     * own documented single-call contract.
     */
    public fun seedRetryWaitingEntryAndSignalReady(directoryPath: String, readyMarkerPath: String): Unit =
        runBlocking {
            val provider = AppleFileQueueProvider(directoryPath)
            val entryId = QueueEntryId(ENTRY_ID)

            provider.enqueue(QueueEnqueueRequest(freshEntry(entryId)))
                .requireSuccess("enqueue")

            acquireSingle(
                provider = provider,
                leaseId = LEASE_SEED_ID,
                acquiredAtMs = SEED_ACQUIRE_AT_EPOCH_MILLIS,
                leaseExpiresAtMs = SEED_LEASE_EXPIRES_AT_EPOCH_MILLIS,
            )

            provider.reschedule(
                QueueRescheduleRequest(
                    entryId = entryId,
                    leaseId = QueueLeaseId(LEASE_SEED_ID),
                    retryAttempt = RetryAttempt(RETRY_ATTEMPT_NUMBER),
                    availableAt = DataLoomInstant(RACE_AVAILABLE_AT_EPOCH_MILLIS),
                    error = InjectedRetryFailure,
                    retryBudgetState = RetryBudgetState(
                        windowStartedAt = DataLoomInstant(RETRY_WINDOW_STARTED_AT_EPOCH_MILLIS),
                        lastEvaluatedAt = DataLoomInstant(RETRY_LAST_EVALUATED_AT_EPOCH_MILLIS),
                        cumulativeDelay = SchedulingDelay(RETRY_CUMULATIVE_DELAY_MILLIS),
                    ),
                ),
            ).requireSuccess("reschedule")

            touchContentionMarkerFile(readyMarkerPath)
        }

    /**
     * Busy-polls (bounded by [maxPollAttempts]) for [goSignalPath] to
     * appear, then immediately calls the real
     * [AppleFileQueueProvider.acquire] against a fresh
     * [AppleFileQueueProvider] rooted at [directoryPath] for the fixed
     * [ENTRY_ID] seeded by [seedRetryWaitingEntryAndSignalReady], using
     * [racerLeaseId]/[racerConsumerId] as this call's own distinct
     * lease/consumer identity, and returns the classified outcome. Called by
     * both racing app processes, each with its own distinct
     * [racerLeaseId]/[racerConsumerId] so a winning acquisition's lease
     * unambiguously identifies which process won.
     */
    public fun waitForGoSignalThenAttemptAcquire(
        directoryPath: String,
        goSignalPath: String,
        racerLeaseId: String,
        racerConsumerId: String,
        maxPollAttempts: Int = DEFAULT_RETRY_BUDGET_CONTENTION_MAX_POLL_ATTEMPTS,
    ): RetryBudgetLeaseContentionProofResult = runBlocking {
        if (!awaitContentionGoSignal(goSignalPath, maxPollAttempts)) {
            return@runBlocking timedOutResult()
        }

        val provider = AppleFileQueueProvider(directoryPath)
        val result = provider.acquire(
            QueueAcquireRequest(
                consumerId = QueueConsumerId(racerConsumerId),
                leaseId = QueueLeaseId(racerLeaseId),
                acquiredAt = DataLoomInstant(RACE_ACQUIRE_AT_EPOCH_MILLIS),
                leaseExpiresAt = DataLoomInstant(RACE_LEASE_EXPIRES_AT_EPOCH_MILLIS),
                maxEntries = 1,
            ),
        )

        when (result) {
            is ProviderOperationResult.Failure -> RetryBudgetLeaseContentionProofResult(
                outcome = "PERSISTENCE_FAILURE",
                retryAttemptNumber = -1,
                retryWindowStartedAtEpochMillis = -1L,
                retryLastEvaluatedAtEpochMillis = -1L,
                retryCumulativeDelayMillis = -1L,
            )
            is ProviderOperationResult.Success -> when (val acquireResult = result.value) {
                QueueAcquireResult.NoEntries -> RetryBudgetLeaseContentionProofResult(
                    outcome = "NO_ELIGIBLE_ENTRY",
                    retryAttemptNumber = -1,
                    retryWindowStartedAtEpochMillis = -1L,
                    retryLastEvaluatedAtEpochMillis = -1L,
                    retryCumulativeDelayMillis = -1L,
                )
                is QueueAcquireResult.Entries -> acquireResult.entries.single().toAcquiredResult()
            }
        }
    }

    private suspend fun acquireSingle(
        provider: AppleFileQueueProvider,
        leaseId: String,
        acquiredAtMs: Long,
        leaseExpiresAtMs: Long,
    ): QueueEntry {
        val result = provider.acquire(
            QueueAcquireRequest(
                consumerId = QueueConsumerId(SEED_CONSUMER_ID),
                leaseId = QueueLeaseId(leaseId),
                acquiredAt = DataLoomInstant(acquiredAtMs),
                leaseExpiresAt = DataLoomInstant(leaseExpiresAtMs),
                maxEntries = 1,
            ),
        )
        val success = (result as? ProviderOperationResult.Success<QueueAcquireResult>)
            ?: error("Failed to seed-acquire the retry-budget contention-proof entry: $result")
        val entries = (success.value as? QueueAcquireResult.Entries)
            ?: error(
                "Expected an eligible retry-budget contention-proof entry but found none: ${success.value}",
            )
        return entries.entries.single()
    }

    private fun QueueEntry.toAcquiredResult(): RetryBudgetLeaseContentionProofResult {
        val attempt = requireNotNull(retryAttempt) {
            "Acquired retry-budget contention-proof entry has no persisted retryAttempt."
        }
        val budget = requireNotNull(retryBudgetState) {
            "Acquired retry-budget contention-proof entry has no persisted retryBudgetState."
        }
        return RetryBudgetLeaseContentionProofResult(
            outcome = "ACQUIRED",
            retryAttemptNumber = attempt.number,
            retryWindowStartedAtEpochMillis = budget.windowStartedAt.epochMilliseconds,
            retryLastEvaluatedAtEpochMillis = budget.lastEvaluatedAt.epochMilliseconds,
            retryCumulativeDelayMillis = budget.cumulativeDelay.milliseconds,
        )
    }

    private fun timedOutResult(): RetryBudgetLeaseContentionProofResult = RetryBudgetLeaseContentionProofResult(
        outcome = "TIMED_OUT_WAITING_FOR_GO_SIGNAL",
        retryAttemptNumber = -1,
        retryWindowStartedAtEpochMillis = -1L,
        retryLastEvaluatedAtEpochMillis = -1L,
        retryCumulativeDelayMillis = -1L,
    )

    private fun <T> ProviderOperationResult<T>.requireSuccess(step: String): T = when (this) {
        is ProviderOperationResult.Success -> value
        is ProviderOperationResult.Failure ->
            error("Retry-budget contention-proof $step failed: ${this.error}")
    }

    private fun freshEntry(entryId: QueueEntryId): QueueEntry = QueueEntry(
        id = entryId,
        synchronizationRequest = SynchronizationRequest(
            workflowId = WorkflowId("workflow-contention-proof-retry-budget"),
            sessionId = SynchronizationSessionId("session-contention-proof-retry-budget"),
            direction = SynchronizationDirection.PUSH,
            mode = SynchronizationMode.DELTA,
            priority = WorkflowPriority.NORMAL,
            context = ExecutionContext(
                executionId = ExecutionId("execution-contention-proof-retry-budget"),
                correlationId = CorrelationId("correlation-contention-proof-retry-budget"),
            ),
        ),
        state = QueueEntryState.PENDING,
        enqueuedAt = DataLoomInstant(ENQUEUED_AT_EPOCH_MILLIS),
        availableAt = DataLoomInstant(ENQUEUED_AT_EPOCH_MILLIS),
    )

    /**
     * Sanitized injected failure driving the proof's own [reschedule] call,
     * mirroring
     * [io.dataloom.processterminationproof.AppleRetryBudgetProcessTerminationProof]'s
     * own `InjectedRetryFailure` exactly -- a stable, non-sensitive
     * [DataLoomError] value with no cause chain.
     */
    private object InjectedRetryFailure : DataLoomError {
        override val code: ErrorCode = ErrorCode("RETRY_BUDGET_CONTENTION_PROOF_INJECTED_TRANSPORT_FAILURE")
        override val category: ErrorCategory = ErrorCategory.NETWORK
        override val severity: ErrorSeverity = ErrorSeverity.WARNING
        override val recoverability: Recoverability = Recoverability.RECOVERABLE
        override val message: String =
            "Sanitized injected failure for cross-process retry-budget contention proof."
        override val cause: Throwable? = null
    }

    private const val ENTRY_ID = "contention-proof-retry-budget-entry"
    private const val SEED_CONSUMER_ID = "contention-proof-retry-budget-seed-consumer"
    private const val LEASE_SEED_ID = "lease-seed-1"

    private const val ENQUEUED_AT_EPOCH_MILLIS: Long = 500L
    private const val SEED_ACQUIRE_AT_EPOCH_MILLIS: Long = 1_000L
    private const val SEED_LEASE_EXPIRES_AT_EPOCH_MILLIS: Long = 1_500L
    private const val RETRY_ATTEMPT_NUMBER: Int = 2
    private const val RETRY_WINDOW_STARTED_AT_EPOCH_MILLIS: Long = 1_100L
    private const val RETRY_LAST_EVALUATED_AT_EPOCH_MILLIS: Long = 1_200L
    private const val RETRY_CUMULATIVE_DELAY_MILLIS: Long = 600L

    // The entry becomes RETRY_WAITING/eligible at this instant (the
    // reschedule's own `availableAt`), strictly before
    // RACE_ACQUIRE_AT_EPOCH_MILLIS below -- both racing processes' own
    // `acquire` calls use the identical fixed RACE_ACQUIRE_AT_EPOCH_MILLIS,
    // so the entry is unconditionally eligible for whichever of the two
    // processes' flock calls the kernel grants first.
    private const val RACE_AVAILABLE_AT_EPOCH_MILLIS: Long = 1_300L
    private const val RACE_ACQUIRE_AT_EPOCH_MILLIS: Long = 5_000L
    private const val RACE_LEASE_EXPIRES_AT_EPOCH_MILLIS: Long = 5_500L

    // 5ms * 12,000 == 60s, matching every other domain's own
    // DEFAULT_MAX_POLL_ATTEMPTS -- generous relative to the CI script's own
    // per-step timeouts, while still failing loudly rather than hanging
    // forever if the host script's "go" file never appears.
    private const val DEFAULT_RETRY_BUDGET_CONTENTION_MAX_POLL_ATTEMPTS = 12_000
}
