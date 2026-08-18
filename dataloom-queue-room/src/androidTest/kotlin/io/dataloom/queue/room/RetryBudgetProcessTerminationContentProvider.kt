package io.dataloom.queue.room

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import androidx.room.Room
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
import io.dataloom.queue.room.internal.DataLoomRoomDatabase
import kotlinx.coroutines.runBlocking

/**
 * Test-only [ContentProvider] hosted in its own `:retrybudgetproof` process
 * (see `src/androidTest/AndroidManifest.xml`), used exclusively by
 * [AndroidProcessTerminationRetryBudgetInstrumentedTest] to prove that
 * persisted durable retry-budget state (attempt count, retry window,
 * cumulative delay) survives a genuine Android OS process kill and relaunch.
 *
 * This is a separate durable structure from circuit-breaker state, proved
 * by [CircuitBreakerProcessTerminationContentProvider]: retry-budget fields
 * (`retry_attempt_number`, `retry_window_started_at_ms`,
 * `retry_last_evaluated_at_ms`, `retry_cumulative_delay_ms`) live on the
 * `queue_entries` table written by [RoomQueueProvider], while circuit-breaker
 * state lives in the independent `circuit_breaker_states` table written by
 * [RoomCircuitBreakerStateStore]. See `DataLoomRoomMigrations.MIGRATION_1_2`
 * (adds the retry-budget columns) versus `MIGRATION_2_3` (adds the separate
 * circuit-breaker table).
 *
 * Both entry points open a fresh [DataLoomRoomDatabase] connection to the
 * on-disk database named by the call argument and drive the real
 * [RoomQueueProvider] production coordinator -- never touching internal DAO
 * or entity state directly. Each call also reports
 * [android.os.Process.myPid] so the caller can prove two calls were served
 * by two different OS processes, not a warm reused one.
 */
public class RetryBudgetProcessTerminationContentProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val databaseName = requireNotNull(arg) {
            "RetryBudgetProcessTerminationContentProvider requires a database name argument."
        }
        val appContext = requireNotNull(context) {
            "RetryBudgetProcessTerminationContentProvider has no attached Context."
        }
        return when (method) {
            RetryBudgetProcessTerminationContract.METHOD_WRITE_RETRY_BUDGET -> {
                runBlocking { writeRetryBudget(appContext, databaseName) }
            }
            RetryBudgetProcessTerminationContract.METHOD_READ_RETRY_BUDGET -> {
                runBlocking { readRetryBudget(appContext, databaseName) }
            }
            else -> error("Unknown RetryBudgetProcessTerminationContentProvider method: $method")
        }
    }

    /**
     * Enqueues a real entry, then drives the real production
     * `acquire -> reschedule -> acquire -> defer` sequence: the first
     * `acquire` leases the fresh entry, `reschedule` persists a genuine
     * retry attempt number and retry-budget window/cumulative delay and
     * returns the entry to `RETRY_WAITING`, the second `acquire` performs an
     * independent SQL read of exactly what was persisted (not merely an
     * echo of what was requested), and `defer` releases that confirmation
     * lease while preserving the retry history exactly, leaving the entry
     * available for [readRetryBudget] (in a different process) to acquire.
     */
    private suspend fun writeRetryBudget(context: Context, databaseName: String): Bundle {
        val database = openDatabase(context, databaseName)
        try {
            val queueProvider = RoomQueueProvider(database)
            val entryId = QueueEntryId(ENTRY_ID)

            val enqueued = queueProvider.enqueue(QueueEnqueueRequest(freshEntry(entryId)))
            check(enqueued is ProviderOperationResult.Success<Unit>) {
                "Failed to enqueue retry-budget-proof entry: $enqueued"
            }

            acquireSingle(queueProvider, "lease-open-1", FIRST_ACQUIRE_AT_MS, FIRST_LEASE_EXPIRES_AT_MS)

            val rescheduled = queueProvider.reschedule(
                QueueRescheduleRequest(
                    entryId = entryId,
                    leaseId = QueueLeaseId("lease-open-1"),
                    retryAttempt = RetryAttempt(RETRY_ATTEMPT_NUMBER),
                    availableAt = DataLoomInstant(RESCHEDULE_AVAILABLE_AT_MS),
                    error = InjectedRetryFailure(),
                    retryBudgetState = RetryBudgetState(
                        windowStartedAt = DataLoomInstant(RETRY_WINDOW_STARTED_AT_MS),
                        lastEvaluatedAt = DataLoomInstant(RETRY_LAST_EVALUATED_AT_MS),
                        cumulativeDelay = SchedulingDelay(RETRY_CUMULATIVE_DELAY_MS),
                    ),
                ),
            )
            check(rescheduled is ProviderOperationResult.Success<Unit>) {
                "Failed to reschedule retry-budget-proof entry: $rescheduled"
            }

            val confirmed = acquireSingle(
                queueProvider,
                "lease-confirm-1",
                RESCHEDULE_AVAILABLE_AT_MS,
                RESCHEDULE_AVAILABLE_AT_MS + LEASE_DURATION_MS,
            )

            val deferred = queueProvider.defer(
                QueueDeferralRequest(
                    entryId = entryId,
                    leaseId = QueueLeaseId("lease-confirm-1"),
                    availableAt = DataLoomInstant(RESCHEDULE_AVAILABLE_AT_MS),
                    reason = QueueDeferralReason.CONNECTIVITY_REQUIREMENT_NOT_MET,
                ),
            )
            check(deferred is ProviderOperationResult.Success<Unit>) {
                "Failed to release the confirmation lease on the retry-budget-proof entry: $deferred"
            }

            return retryBudgetBundle(confirmed)
        } finally {
            database.close()
        }
    }

    /**
     * Opens a brand-new connection to the same on-disk database and
     * re-acquires the same entry, an independent production read that must
     * observe exactly what [writeRetryBudget] persisted before this process
     * was killed.
     */
    private suspend fun readRetryBudget(context: Context, databaseName: String): Bundle {
        val database = openDatabase(context, databaseName)
        try {
            val queueProvider = RoomQueueProvider(database)
            val entry = acquireSingle(
                queueProvider,
                "lease-after-relaunch",
                RESCHEDULE_AVAILABLE_AT_MS,
                RESCHEDULE_AVAILABLE_AT_MS + LEASE_DURATION_MS,
            )
            return retryBudgetBundle(entry)
        } finally {
            database.close()
        }
    }

    private suspend fun acquireSingle(
        queueProvider: RoomQueueProvider,
        leaseId: String,
        acquiredAtMs: Long,
        leaseExpiresAtMs: Long,
    ): QueueEntry {
        val result = queueProvider.acquire(
            QueueAcquireRequest(
                consumerId = QueueConsumerId("retry-budget-proof-consumer"),
                leaseId = QueueLeaseId(leaseId),
                acquiredAt = DataLoomInstant(acquiredAtMs),
                leaseExpiresAt = DataLoomInstant(leaseExpiresAtMs),
                maxEntries = 1,
            ),
        )
        val success = result as? ProviderOperationResult.Success<QueueAcquireResult>
            ?: error("Failed to acquire retry-budget-proof entry: $result")
        val entries = success.value as? QueueAcquireResult.Entries
            ?: error("Expected an eligible retry-budget-proof entry but found none: ${success.value}")
        return entries.entries.single()
    }

    private fun retryBudgetBundle(entry: QueueEntry): Bundle {
        val retryAttempt = requireNotNull(entry.retryAttempt) {
            "Acquired retry-budget-proof entry has no persisted retryAttempt."
        }
        val budget = requireNotNull(entry.retryBudgetState) {
            "Acquired retry-budget-proof entry has no persisted retryBudgetState."
        }
        return Bundle().apply {
            putInt(RetryBudgetProcessTerminationContract.KEY_PID, android.os.Process.myPid())
            putInt(RetryBudgetProcessTerminationContract.KEY_RETRY_ATTEMPT_NUMBER, retryAttempt.number)
            putLong(
                RetryBudgetProcessTerminationContract.KEY_RETRY_WINDOW_STARTED_AT_MILLIS,
                budget.windowStartedAt.epochMilliseconds,
            )
            putLong(
                RetryBudgetProcessTerminationContract.KEY_RETRY_LAST_EVALUATED_AT_MILLIS,
                budget.lastEvaluatedAt.epochMilliseconds,
            )
            putLong(
                RetryBudgetProcessTerminationContract.KEY_RETRY_CUMULATIVE_DELAY_MILLIS,
                budget.cumulativeDelay.milliseconds,
            )
        }
    }

    private fun freshEntry(entryId: QueueEntryId): QueueEntry = QueueEntry(
        id = entryId,
        synchronizationRequest = SynchronizationRequest(
            workflowId = WorkflowId("workflow-retry-budget-proof"),
            sessionId = SynchronizationSessionId("session-retry-budget-proof"),
            direction = SynchronizationDirection.PUSH,
            mode = SynchronizationMode.DELTA,
            priority = WorkflowPriority.NORMAL,
            context = ExecutionContext(
                executionId = ExecutionId("execution-retry-budget-proof"),
                correlationId = CorrelationId("correlation-retry-budget-proof"),
            ),
        ),
        state = QueueEntryState.PENDING,
        enqueuedAt = DataLoomInstant(ENQUEUED_AT_MS),
        availableAt = DataLoomInstant(ENQUEUED_AT_MS),
    )

    private fun openDatabase(context: Context, name: String): DataLoomRoomDatabase = Room.databaseBuilder(
        context,
        DataLoomRoomDatabase::class.java,
        name,
    ).addMigrations(*DataLoomRoomMigrations.ALL)
        .build()

    // ContentProvider query/insert/update/delete/getType are unused by this
    // test-only provider; call() is the sole entry point.
    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    private data class InjectedRetryFailure(
        override val code: ErrorCode = ErrorCode("RETRY_BUDGET_PROOF_INJECTED_TRANSPORT_FAILURE"),
        override val category: ErrorCategory = ErrorCategory.NETWORK,
        override val severity: ErrorSeverity = ErrorSeverity.WARNING,
        override val recoverability: Recoverability = Recoverability.RECOVERABLE,
        override val message: String = "Sanitized injected failure for process-kill retry-budget proof.",
        override val cause: Throwable? = null,
    ) : DataLoomError

    private companion object {
        const val ENTRY_ID = "retry-budget-proof-entry"
        const val ENQUEUED_AT_MS: Long = 500L
        const val FIRST_ACQUIRE_AT_MS: Long = 1_000L
        const val FIRST_LEASE_EXPIRES_AT_MS: Long = 1_500L
        const val RETRY_ATTEMPT_NUMBER: Int = 3
        const val RETRY_WINDOW_STARTED_AT_MS: Long = 1_100L
        const val RETRY_LAST_EVALUATED_AT_MS: Long = 1_200L
        const val RETRY_CUMULATIVE_DELAY_MS: Long = 750L
        const val RESCHEDULE_AVAILABLE_AT_MS: Long = 1_300L
        const val LEASE_DURATION_MS: Long = 500L
    }
}
