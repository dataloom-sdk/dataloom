@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.dataloom.runtime.retry

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
import io.dataloom.api.queue.QueueEntry
import io.dataloom.api.queue.QueueEntryState
import io.dataloom.api.retry.AuthorizedRetryAdministrationCommand
import io.dataloom.api.retry.RetryAdministrationAction
import io.dataloom.api.retry.RetryAdministrationAuthorizationId
import io.dataloom.api.retry.RetryAdministrationCommandId
import io.dataloom.api.retry.RetryAdministrationExecutionResult
import io.dataloom.api.retry.RetryAdministrationPrincipalId
import io.dataloom.api.retry.RetryAdministrationReason
import io.dataloom.api.retry.RetryAdministrationRequest
import io.dataloom.api.retry.RetryAttempt
import io.dataloom.api.retry.RetryBudgetState
import io.dataloom.api.retry.RetryFailureSnapshot
import io.dataloom.api.retry.WorkflowTimeoutState
import io.dataloom.api.scheduling.SchedulingDelay
import io.dataloom.api.time.DataLoomClock
import io.dataloom.api.time.DataLoomInstant
import io.dataloom.runtime.queue.AppleFileQueueProvider
import io.dataloom.runtime.queue.AppleQueueSnapshot
import io.dataloom.runtime.queue.AppleQueueStateFileCodec
import io.dataloom.runtime.queue.appleQueueEnsurePrivateDirectory
import io.dataloom.runtime.queue.appleQueueReadUtf8FileOrNull
import io.dataloom.runtime.queue.appleQueueWriteUtf8FileAtomically
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID

class AppleFileRetryAdministrationExecutorTest {

    @Test
    fun `legacy snapshot upgrades and identical command replays without second mutation`() = runTest {
        val directory = uniqueDirectory()
        val original = terminalEntry()
        writeLegacySnapshot(directory, original)
        val command = commandFor(original)
        val executor = AppleFileRetryAdministrationExecutor(directory, FixedClock(3_000L))

        assertIs<RetryAdministrationExecutionResult.Applied>(executor.execute(command))
        assertIs<RetryAdministrationExecutionResult.Applied>(executor.execute(command))

        val content = readSnapshotText(directory)
        assertTrue(content.startsWith("DATALOOM_QUEUE_STATE\t2\n"))
        val snapshot = AppleQueueStateFileCodec.decodeSnapshot(content)
        val persisted = snapshot.entries.getValue(original.id.value)
        assertEquals(QueueEntryState.RETRY_WAITING, persisted.state)
        assertEquals(original.retryAttempt, persisted.retryAttempt)
        assertEquals(original.retryBudgetState, persisted.retryBudgetState)
        assertEquals(original.workflowTimeoutState, persisted.workflowTimeoutState)
        assertNull(persisted.lastError)
        assertEquals(1, snapshot.retryAdministrationReceipts.size)
        assertEquals(command, snapshot.retryAdministrationReceipts.getValue("command-1").command)
        assertEquals(DataLoomInstant(3_000L), snapshot.retryAdministrationReceipts.getValue("command-1").appliedAt)

        val provider = AppleFileQueueProvider(directory)
        val first = assertIs<QueueAcquireResult.Entries>(
            provider.acquire(acquireRequest("lease-1", 3_000L)).successValue(),
        )
        assertEquals(original.id, first.entries.single().id)
        assertIs<QueueAcquireResult.NoEntries>(
            provider.acquire(acquireRequest("lease-2", 3_000L)).successValue(),
        )

        val afterAcquire = AppleQueueStateFileCodec.decodeSnapshot(readSnapshotText(directory))
        assertEquals(1, afterAcquire.retryAdministrationReceipts.size)
    }

    @Test
    fun `receipt rejects command id reuse with different immutable input`() = runTest {
        val directory = uniqueDirectory()
        val original = terminalEntry()
        writeLegacySnapshot(directory, original)
        val executor = AppleFileRetryAdministrationExecutor(directory, FixedClock(3_000L))
        val command = commandFor(original)
        assertIs<RetryAdministrationExecutionResult.Applied>(executor.execute(command))

        val conflicting = command.copy(
            request = command.request.copy(
                reason = RetryAdministrationReason("Different immutable reason"),
            ),
        )
        val rejected = assertIs<RetryAdministrationExecutionResult.Rejected>(
            executor.execute(conflicting),
        )

        assertEquals("RETRY_ADMIN_COMMAND_CONFLICT", rejected.reasonCode)
        val snapshot = AppleQueueStateFileCodec.decodeSnapshot(readSnapshotText(directory))
        assertEquals(command, snapshot.retryAdministrationReceipts.getValue("command-1").command)
    }

    @Test
    fun `protected failure requires explicit reclassification`() = runTest {
        val directory = uniqueDirectory()
        val error = TestError(
            code = ErrorCode("AUTHORIZATION_FAILURE"),
            category = ErrorCategory.AUTHORIZATION,
            severity = ErrorSeverity.ERROR,
            recoverability = Recoverability.RECOVERABLE,
            message = "Authorization failed.",
        )
        val original = terminalEntry(id = "protected", error = error)
        writeLegacySnapshot(directory, original)
        val executor = AppleFileRetryAdministrationExecutor(directory, FixedClock(3_000L))

        val ordinary = commandFor(original, commandId = "protected-requeue")
        val rejected = assertIs<RetryAdministrationExecutionResult.Rejected>(
            executor.execute(ordinary),
        )
        assertEquals("RETRY_RECLASSIFICATION_REQUIRED", rejected.reasonCode)

        val reclassified = commandFor(
            original,
            commandId = "protected-reclassify",
            action = RetryAdministrationAction.RECLASSIFY_AND_REQUEUE,
        )
        assertIs<RetryAdministrationExecutionResult.Applied>(executor.execute(reclassified))

        val snapshot = AppleQueueStateFileCodec.decodeSnapshot(readSnapshotText(directory))
        assertEquals(QueueEntryState.RETRY_WAITING, snapshot.entries.getValue("protected").state)
        assertEquals(1, snapshot.retryAdministrationReceipts.size)
    }

    @Test
    fun `failure mismatch rejects without upgrading or mutating legacy state`() = runTest {
        val directory = uniqueDirectory()
        val original = terminalEntry(id = "mismatch")
        writeLegacySnapshot(directory, original)
        val command = commandFor(original, commandId = "mismatch-command").copy(
            request = commandFor(original, commandId = "mismatch-command").request.copy(
                originalFailure = RetryFailureSnapshot(
                    code = ErrorCode("DIFFERENT_FAILURE"),
                    category = ErrorCategory.NETWORK,
                    severity = ErrorSeverity.ERROR,
                    recoverability = Recoverability.RECOVERABLE,
                ),
            ),
        )

        val rejected = assertIs<RetryAdministrationExecutionResult.Rejected>(
            AppleFileRetryAdministrationExecutor(directory, FixedClock(3_000L)).execute(command),
        )

        assertEquals("RETRY_ADMIN_TARGET_FAILURE_MISMATCH", rejected.reasonCode)
        val content = readSnapshotText(directory)
        assertTrue(content.startsWith("DATALOOM_QUEUE_STATE\t1\n"))
        val snapshot = AppleQueueStateFileCodec.decodeSnapshot(content)
        assertEquals(QueueEntryState.FAILED, snapshot.entries.getValue("mismatch").state)
        assertTrue(snapshot.retryAdministrationReceipts.isEmpty())
    }

    @Test
    fun `expired immutable workflow deadline rejects without receipt`() = runTest {
        val directory = uniqueDirectory()
        val original = terminalEntry(
            id = "expired",
            workflowTimeout = WorkflowTimeoutState(
                startedAt = DataLoomInstant(1_000L),
                deadline = DataLoomInstant(2_500L),
            ),
        )
        writeLegacySnapshot(directory, original)

        val rejected = assertIs<RetryAdministrationExecutionResult.Rejected>(
            AppleFileRetryAdministrationExecutor(directory, FixedClock(3_000L)).execute(
                commandFor(original, commandId = "expired-command"),
            ),
        )

        assertEquals("RETRY_ADMIN_TARGET_WORKFLOW_DEADLINE_EXPIRED", rejected.reasonCode)
        val snapshot = AppleQueueStateFileCodec.decodeSnapshot(readSnapshotText(directory))
        assertTrue(snapshot.retryAdministrationReceipts.isEmpty())
        assertEquals(QueueEntryState.FAILED, snapshot.entries.getValue("expired").state)
    }

    @Test
    fun `corrupt v2 receipt fails closed without leaking file content`() = runTest {
        val directory = uniqueDirectory()
        appleQueueEnsurePrivateDirectory(directory)
        val path = dataPath(directory)
        appleQueueWriteUtf8FileAtomically(
            temporaryPath = "$path.test-tmp",
            destinationPath = path,
            content = "DATALOOM_QUEUE_STATE\t2\nR\tcredential-value\n",
        )

        val failed = assertIs<RetryAdministrationExecutionResult.Failed>(
            AppleFileRetryAdministrationExecutor(directory, FixedClock(3_000L)).execute(
                commandFor(terminalEntry()),
            ),
        )

        assertEquals("RETRY_ADMIN_APPLE_QUEUE_STATE_CORRUPT", failed.error.code.value)
        assertTrue("credential-value" !in failed.error.message)
        assertNull(failed.error.cause)
    }

    @Test
    fun `cancelled caller does not enter Apple queue execution`() = runTest {
        val executor = AppleFileRetryAdministrationExecutor(uniqueDirectory(), FixedClock(3_000L))
        val deferred = async(start = CoroutineStart.LAZY) {
            executor.execute(commandFor(terminalEntry()))
        }
        deferred.cancel(CancellationException("caller cancelled"))

        val failure = assertFailsWith<CancellationException> { deferred.await() }
        assertEquals("caller cancelled", failure.message)
    }

    @Test
    fun `constructor rejects unsafe paths without file access`() {
        assertFailsWith<IllegalArgumentException> {
            AppleFileRetryAdministrationExecutor("relative/path", FixedClock(1L))
        }
        assertFailsWith<IllegalArgumentException> {
            AppleFileRetryAdministrationExecutor(
                "/tmp/safe",
                FixedClock(1L),
                "../unsafe",
            )
        }
    }

    private fun terminalEntry(
        id: String = "entry-1",
        error: DataLoomError = TestError(
            code = ErrorCode("NETWORK_FINAL"),
            category = ErrorCategory.NETWORK,
            severity = ErrorSeverity.ERROR,
            recoverability = Recoverability.RECOVERABLE,
            message = "The network request failed.",
        ),
        workflowTimeout: WorkflowTimeoutState = WorkflowTimeoutState(
            startedAt = DataLoomInstant(1_000L),
            deadline = DataLoomInstant(10_000L),
        ),
    ): QueueEntry = QueueEntry(
        id = QueueEntryId(id),
        synchronizationRequest = SynchronizationRequest(
            workflowId = WorkflowId("workflow-$id"),
            sessionId = SynchronizationSessionId("session-$id"),
            direction = SynchronizationDirection.PUSH,
            mode = SynchronizationMode.DELTA,
            priority = WorkflowPriority.NORMAL,
            context = ExecutionContext(
                executionId = ExecutionId("execution-$id"),
                correlationId = CorrelationId("correlation-$id"),
            ),
        ),
        state = QueueEntryState.FAILED,
        enqueuedAt = DataLoomInstant(1_000L),
        availableAt = DataLoomInstant(1_500L),
        retryAttempt = RetryAttempt(2),
        retryBudgetState = RetryBudgetState(
            windowStartedAt = DataLoomInstant(1_100L),
            lastEvaluatedAt = DataLoomInstant(1_200L),
            cumulativeDelay = SchedulingDelay(500L),
        ),
        workflowTimeoutState = workflowTimeout,
        lastError = error,
    )

    private fun commandFor(
        entry: QueueEntry,
        commandId: String = "command-1",
        action: RetryAdministrationAction = RetryAdministrationAction.REQUEUE,
    ): AuthorizedRetryAdministrationCommand {
        val error = checkNotNull(entry.lastError)
        return AuthorizedRetryAdministrationCommand(
            request = RetryAdministrationRequest(
                commandId = RetryAdministrationCommandId(commandId),
                queueEntryId = entry.id,
                principalId = RetryAdministrationPrincipalId("operator-1"),
                requestedAt = DataLoomInstant(2_000L),
                action = action,
                reason = RetryAdministrationReason("Operator requested retry"),
                originalFailure = RetryFailureSnapshot(
                    code = error.code,
                    category = error.category,
                    severity = error.severity,
                    recoverability = error.recoverability,
                ),
            ),
            authorizationId = RetryAdministrationAuthorizationId("authorization-$commandId"),
            effectiveRecoverability = Recoverability.RECOVERABLE,
        )
    }

    private fun writeLegacySnapshot(directory: String, entry: QueueEntry) {
        appleQueueEnsurePrivateDirectory(directory)
        val path = dataPath(directory)
        appleQueueWriteUtf8FileAtomically(
            temporaryPath = "$path.test-tmp",
            destinationPath = path,
            content = AppleQueueStateFileCodec.encode(mapOf(entry.id.value to entry)),
        )
    }

    private fun readSnapshotText(directory: String): String =
        checkNotNull(appleQueueReadUtf8FileOrNull(dataPath(directory)))

    private fun dataPath(directory: String): String =
        "$directory/${AppleFileQueueProvider.DEFAULT_FILE_NAME}"

    private fun acquireRequest(leaseId: String, acquiredAt: Long): QueueAcquireRequest =
        QueueAcquireRequest(
            consumerId = QueueConsumerId("consumer-1"),
            leaseId = QueueLeaseId(leaseId),
            acquiredAt = DataLoomInstant(acquiredAt),
            leaseExpiresAt = DataLoomInstant(acquiredAt + 1_000L),
            maxEntries = 1,
        )

    private fun uniqueDirectory(): String = buildString {
        append(NSTemporaryDirectory().trimEnd('/'))
        append("/dataloom-apple-retry-admin-executor-")
        append(NSUUID().UUIDString)
    }

    private fun <T> ProviderOperationResult<T>.successValue(): T =
        assertIs<ProviderOperationResult.Success<T>>(this).value

    private class FixedClock(private val value: Long) : DataLoomClock {
        override fun now(): DataLoomInstant = DataLoomInstant(value)
    }

    private data class TestError(
        override val code: ErrorCode,
        override val category: ErrorCategory,
        override val severity: ErrorSeverity,
        override val recoverability: Recoverability,
        override val message: String,
        override val cause: Throwable? = null,
    ) : DataLoomError
}
