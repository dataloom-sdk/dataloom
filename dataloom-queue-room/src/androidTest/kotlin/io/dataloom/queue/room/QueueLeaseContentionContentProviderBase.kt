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
import io.dataloom.api.queue.QueueCompletionRequest
import io.dataloom.api.queue.QueueEnqueueRequest
import io.dataloom.api.queue.QueueEntry
import io.dataloom.api.queue.QueueEntryState
import io.dataloom.api.queue.QueueRescheduleRequest
import io.dataloom.api.retry.RetryAttempt
import io.dataloom.api.retry.RetryBudgetState
import io.dataloom.api.scheduling.SchedulingDelay
import io.dataloom.api.time.DataLoomInstant
import io.dataloom.queue.room.internal.DataLoomRoomDatabase
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.runBlocking

/**
 * Test-only shared base [ContentProvider] logic for the genuine cross-process
 * queue-lease (retry-budget lease) contention proof. Never declared in the
 * manifest itself; see [CircuitBreakerProbeContentionContentProviderBase] for
 * the Android `ComponentName` rule that forces two distinct leaf classes
 * ([QueueLeaseContentionContentProviderA] / [QueueLeaseContentionContentProviderB]).
 *
 * Unlike the circuit-breaker contention provider, each process opens and
 * caches its database connection in [METHOD_OPEN][QueueLeaseContentionContract.METHOD_OPEN]
 * before the race, so the raced call does not begin with a slow, jittery Room
 * cold open that would stagger the two processes. The raced call itself
 * ([QueueLeaseContentionContract.METHOD_RACE_ACQUIRE]) additionally spins on
 * the shared device wall clock until a caller-chosen start instant, so both
 * processes issue the real [RoomQueueProvider.acquire] within about a
 * millisecond of each other regardless of Binder dispatch jitter.
 *
 * Every entry point drives the real [RoomQueueProvider] production path. All
 * queue timestamps are fixed logical values passed in the request (queue
 * eligibility is a pure function of the request's `acquiredAt` and the row's
 * `available_at_ms`), so unlike the circuit-breaker proof this one has no
 * dependency on wall-clock agreement beyond the start-gate spin.
 */
public abstract class QueueLeaseContentionContentProviderBase : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val databaseName = requireNotNull(arg) {
            "QueueLeaseContentionContentProviderBase requires a database name argument."
        }
        val appContext = requireNotNull(context) {
            "QueueLeaseContentionContentProviderBase has no attached Context."
        }
        return when (method) {
            QueueLeaseContentionContract.METHOD_OPEN -> {
                databases.getOrPut(databaseName) { openDatabase(appContext, databaseName) }
                pidBundle()
            }
            QueueLeaseContentionContract.METHOD_CLOSE -> {
                databases.remove(databaseName)?.close()
                pidBundle()
            }
            else -> {
                val database = checkNotNull(databases[databaseName]) {
                    "Database '$databaseName' is not open in this process; call METHOD_OPEN first."
                }
                val queue = RoomQueueProvider(database)
                val args = requireNotNull(extras) { "Method '$method' requires extras." }
                runBlocking { dispatch(queue, method, args) }
            }
        }
    }

    private suspend fun dispatch(queue: RoomQueueProvider, method: String, extras: Bundle): Bundle =
        when (method) {
            QueueLeaseContentionContract.METHOD_SEED -> seed(queue, extras.entryId())
            QueueLeaseContentionContract.METHOD_RACE_ACQUIRE -> {
                val startAt = extras.getLong(QueueLeaseContentionContract.EXTRA_START_AT_MS)
                while (System.currentTimeMillis() < startAt) {
                    // Busy-wait: sleeping would reintroduce scheduler jitter at the release edge.
                }
                acquire(queue, extras.leaseId(), RACE_ACQUIRED_AT_MS)
            }
            QueueLeaseContentionContract.METHOD_READBACK_ACQUIRE ->
                acquire(queue, extras.leaseId(), READBACK_ACQUIRED_AT_MS)
            QueueLeaseContentionContract.METHOD_COMPLETE -> complete(queue, extras.entryId(), extras.leaseId())
            QueueLeaseContentionContract.METHOD_RESCHEDULE -> reschedule(queue, extras.entryId(), extras.leaseId())
            else -> error("Unknown QueueLeaseContentionContentProviderBase method: $method")
        }

    /**
     * Enqueues a fresh entry and drives it acquire -> reschedule so it rests
     * as RETRY_WAITING (attempt [QueueLeaseContentionContract.SEEDED_RETRY_ATTEMPT])
     * with a real retry budget, eligible from [SEEDED_AVAILABLE_AT_MS].
     */
    private suspend fun seed(queue: RoomQueueProvider, entryId: String): Bundle {
        val id = QueueEntryId(entryId)
        check(queue.enqueue(QueueEnqueueRequest(freshEntry(id))) is ProviderOperationResult.Success<Unit>) {
            "Failed to enqueue $entryId."
        }
        val seeded = queue.acquire(
            QueueAcquireRequest(
                consumerId = QueueConsumerId("seed-consumer"),
                leaseId = QueueLeaseId("seed-lease-$entryId"),
                acquiredAt = DataLoomInstant(SEED_ACQUIRED_AT_MS),
                leaseExpiresAt = DataLoomInstant(SEED_ACQUIRED_AT_MS + LEASE_DURATION_MS),
                maxEntries = 1,
            ),
        )
        val seededEntries = (seeded as? ProviderOperationResult.Success)?.value as? QueueAcquireResult.Entries
        check(seededEntries?.entries?.singleOrNull()?.id == id) {
            "Seed acquire for $entryId did not lease exactly that entry: $seeded"
        }
        val rescheduled = queue.reschedule(
            QueueRescheduleRequest(
                entryId = id,
                leaseId = QueueLeaseId("seed-lease-$entryId"),
                retryAttempt = RetryAttempt(QueueLeaseContentionContract.SEEDED_RETRY_ATTEMPT),
                availableAt = DataLoomInstant(SEEDED_AVAILABLE_AT_MS),
                error = InjectedFailure(),
                retryBudgetState = RetryBudgetState(
                    windowStartedAt = DataLoomInstant(1_100L),
                    lastEvaluatedAt = DataLoomInstant(1_200L),
                    cumulativeDelay = SchedulingDelay(QueueLeaseContentionContract.SEEDED_CUMULATIVE_DELAY_MS),
                ),
            ),
        )
        check(rescheduled is ProviderOperationResult.Success<Unit>) { "Failed to reschedule $entryId: $rescheduled" }
        return pidBundle()
    }

    private suspend fun acquire(queue: RoomQueueProvider, leaseId: String, acquiredAtMs: Long): Bundle {
        val startedAt = System.currentTimeMillis()
        val result = queue.acquire(
            QueueAcquireRequest(
                consumerId = QueueConsumerId("consumer-${android.os.Process.myPid()}"),
                leaseId = QueueLeaseId(leaseId),
                acquiredAt = DataLoomInstant(acquiredAtMs),
                leaseExpiresAt = DataLoomInstant(acquiredAtMs + LEASE_DURATION_MS),
                maxEntries = 1,
            ),
        )
        val endedAt = System.currentTimeMillis()
        val bundle = pidBundle().apply {
            putLong(QueueLeaseContentionContract.KEY_CALL_START_MS, startedAt)
            putLong(QueueLeaseContentionContract.KEY_CALL_END_MS, endedAt)
        }
        when (result) {
            is ProviderOperationResult.Failure ->
                bundle.putString(QueueLeaseContentionContract.KEY_OUTCOME, "FAILURE:${result.error.code.value}")
            is ProviderOperationResult.Success -> when (val value = result.value) {
                QueueAcquireResult.NoEntries ->
                    bundle.putString(QueueLeaseContentionContract.KEY_OUTCOME, "NO_ENTRIES")
                is QueueAcquireResult.Entries -> {
                    bundle.putString(QueueLeaseContentionContract.KEY_OUTCOME, "WON")
                    bundle.putInt(QueueLeaseContentionContract.KEY_ACQUIRED_ENTRY_COUNT, value.entries.size)
                    val entry = value.entries.first()
                    bundle.putString(QueueLeaseContentionContract.KEY_ACQUIRED_ENTRY_ID, entry.id.value)
                    bundle.putString(QueueLeaseContentionContract.KEY_LEASE_ID, value.lease.id.value)
                    bundle.putInt(QueueLeaseContentionContract.KEY_RETRY_ATTEMPT, entry.retryAttempt?.number ?: -1)
                    bundle.putLong(
                        QueueLeaseContentionContract.KEY_RETRY_CUMULATIVE_DELAY_MS,
                        entry.retryBudgetState?.cumulativeDelay?.milliseconds ?: -1L,
                    )
                }
            }
        }
        return bundle
    }

    private suspend fun complete(queue: RoomQueueProvider, entryId: String, leaseId: String): Bundle {
        val result = queue.complete(
            QueueCompletionRequest(
                entryId = QueueEntryId(entryId),
                leaseId = QueueLeaseId(leaseId),
                completedAt = DataLoomInstant(COMPLETED_AT_MS),
            ),
        )
        return resultBundle(result)
    }

    private suspend fun reschedule(queue: RoomQueueProvider, entryId: String, leaseId: String): Bundle {
        val result = queue.reschedule(
            QueueRescheduleRequest(
                entryId = QueueEntryId(entryId),
                leaseId = QueueLeaseId(leaseId),
                retryAttempt = RetryAttempt(QueueLeaseContentionContract.RESCHEDULED_RETRY_ATTEMPT),
                availableAt = DataLoomInstant(RESCHEDULED_AVAILABLE_AT_MS),
                error = InjectedFailure(),
                retryBudgetState = RetryBudgetState(
                    windowStartedAt = DataLoomInstant(1_100L),
                    lastEvaluatedAt = DataLoomInstant(2_100L),
                    cumulativeDelay = SchedulingDelay(QueueLeaseContentionContract.RESCHEDULED_CUMULATIVE_DELAY_MS),
                ),
            ),
        )
        return resultBundle(result)
    }

    private fun resultBundle(result: ProviderOperationResult<Unit>): Bundle = pidBundle().apply {
        putString(
            QueueLeaseContentionContract.KEY_RESULT,
            when (result) {
                is ProviderOperationResult.Success -> "OK"
                is ProviderOperationResult.Failure -> "FAILURE:${result.error.code.value}"
            },
        )
    }

    private fun pidBundle(): Bundle = Bundle().apply {
        putInt(QueueLeaseContentionContract.KEY_PID, android.os.Process.myPid())
    }

    private fun Bundle.entryId(): String = requireNotNull(getString(QueueLeaseContentionContract.EXTRA_ENTRY_ID))

    private fun Bundle.leaseId(): String = requireNotNull(getString(QueueLeaseContentionContract.EXTRA_LEASE_ID))

    private fun freshEntry(entryId: QueueEntryId): QueueEntry = QueueEntry(
        id = entryId,
        synchronizationRequest = SynchronizationRequest(
            workflowId = WorkflowId("workflow-queue-lease-contention"),
            sessionId = SynchronizationSessionId("session-queue-lease-contention"),
            direction = SynchronizationDirection.PUSH,
            mode = SynchronizationMode.DELTA,
            priority = WorkflowPriority.NORMAL,
            context = ExecutionContext(
                executionId = ExecutionId("execution-queue-lease-contention"),
                correlationId = CorrelationId("correlation-queue-lease-contention"),
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

    private data class InjectedFailure(
        override val code: ErrorCode = ErrorCode("QUEUE_LEASE_CONTENTION_INJECTED_TRANSPORT_FAILURE"),
        override val category: ErrorCategory = ErrorCategory.NETWORK,
        override val severity: ErrorSeverity = ErrorSeverity.WARNING,
        override val recoverability: Recoverability = Recoverability.RECOVERABLE,
        override val message: String = "Sanitized injected failure for queue-lease contention proof.",
        override val cause: Throwable? = null,
    ) : DataLoomError

    private companion object {
        /** Per-process connection cache: each OS process holds its own genuine connection. */
        val databases = ConcurrentHashMap<String, DataLoomRoomDatabase>()

        const val ENQUEUED_AT_MS: Long = 500L
        const val SEED_ACQUIRED_AT_MS: Long = 1_000L
        const val SEEDED_AVAILABLE_AT_MS: Long = 1_300L
        const val RACE_ACQUIRED_AT_MS: Long = 2_000L
        const val RESCHEDULED_AVAILABLE_AT_MS: Long = 2_500L
        const val READBACK_ACQUIRED_AT_MS: Long = 3_000L
        const val COMPLETED_AT_MS: Long = 3_100L
        const val LEASE_DURATION_MS: Long = 500L
    }
}
