@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.dataloom.runtime.retry

import io.dataloom.api.error.ErrorCategory
import io.dataloom.api.error.ErrorCode
import io.dataloom.api.error.ErrorSeverity
import io.dataloom.api.error.Recoverability
import io.dataloom.api.identifier.QueueEntryId
import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.api.retry.RetryAdministrationAction
import io.dataloom.api.retry.RetryAdministrationAuthorizationId
import io.dataloom.api.retry.RetryAdministrationCommandId
import io.dataloom.api.retry.RetryAdministrationCommandState
import io.dataloom.api.retry.RetryAdministrationCommandStatus
import io.dataloom.api.retry.RetryAdministrationCompareAndSetRequest
import io.dataloom.api.retry.RetryAdministrationCompareAndSetResult
import io.dataloom.api.retry.RetryAdministrationLoadResult
import io.dataloom.api.retry.RetryAdministrationPrincipalId
import io.dataloom.api.retry.RetryAdministrationReason
import io.dataloom.api.retry.RetryAdministrationRequest
import io.dataloom.api.retry.RetryFailureSnapshot
import io.dataloom.api.time.DataLoomInstant
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID

class AppleFileRetryAdministrationStateStoreTest {

    @Test
    fun `missing command is created and survives a new store instance`() = runTest {
        val directory = uniqueDirectory()
        val request = request(commandId = "command-restart")
        val state = authorizedState(request)
        val first = AppleFileRetryAdministrationStateStore(directory)

        assertIs<RetryAdministrationLoadResult.Missing>(
            first.loadSuccess(request.commandId),
        )
        val created = assertIs<RetryAdministrationCompareAndSetResult.Updated>(
            first.compareSuccess(
                RetryAdministrationCompareAndSetRequest(
                    commandId = request.commandId,
                    expectedVersion = null,
                    nextState = state,
                ),
            ),
        )
        assertEquals(0L, created.record.version)
        assertEquals(state, created.record.state)

        val reopened = AppleFileRetryAdministrationStateStore(directory)
        val found = assertIs<RetryAdministrationLoadResult.Found>(
            reopened.loadSuccess(request.commandId),
        )
        assertEquals(created.record, found.record)
    }

    @Test
    fun `compare and set preserves exact conflicts and increments versions`() = runTest {
        val store = AppleFileRetryAdministrationStateStore(uniqueDirectory())
        val request = request(commandId = "command-cas")
        val initial = authorizedState(request)
        val created = assertIs<RetryAdministrationCompareAndSetResult.Updated>(
            store.compareSuccess(
                RetryAdministrationCompareAndSetRequest(
                    request.commandId,
                    null,
                    initial,
                ),
            ),
        )

        val stale = assertIs<RetryAdministrationCompareAndSetResult.Conflict>(
            store.compareSuccess(
                RetryAdministrationCompareAndSetRequest(
                    request.commandId,
                    null,
                    initial,
                ),
            ),
        )
        assertEquals(created.record, stale.current)

        val succeeded = initial.copy(
            status = RetryAdministrationCommandStatus.SUCCEEDED,
            updatedAt = DataLoomInstant(3_000L),
        )
        val updated = assertIs<RetryAdministrationCompareAndSetResult.Updated>(
            store.compareSuccess(
                RetryAdministrationCompareAndSetRequest(
                    request.commandId,
                    created.record.version,
                    succeeded,
                ),
            ),
        )
        assertEquals(1L, updated.record.version)
        assertEquals(succeeded, updated.record.state)
    }

    @Test
    fun `matching version cannot replace immutable command input`() = runTest {
        val store = AppleFileRetryAdministrationStateStore(uniqueDirectory())
        val request = request(commandId = "command-immutable", reason = "first reason")
        val created = assertIs<RetryAdministrationCompareAndSetResult.Updated>(
            store.compareSuccess(
                RetryAdministrationCompareAndSetRequest(
                    request.commandId,
                    null,
                    authorizedState(request),
                ),
            ),
        )
        val changedRequest = request(
            commandId = request.commandId.value,
            reason = "forged replacement",
        )

        val conflict = assertIs<RetryAdministrationCompareAndSetResult.Conflict>(
            store.compareSuccess(
                RetryAdministrationCompareAndSetRequest(
                    request.commandId,
                    created.record.version,
                    authorizedState(changedRequest),
                ),
            ),
        )
        assertEquals(created.record, conflict.current)
        val persisted = assertIs<RetryAdministrationLoadResult.Found>(
            store.loadSuccess(request.commandId),
        )
        assertEquals(request, persisted.record.state.request)
        assertEquals(0L, persisted.record.version)
    }

    @Test
    fun `two store instances serialize first creation exactly`() = runTest {
        val directory = uniqueDirectory()
        val request = request(commandId = "command-contention")
        val command = RetryAdministrationCompareAndSetRequest(
            commandId = request.commandId,
            expectedVersion = null,
            nextState = authorizedState(request),
        )
        val first = AppleFileRetryAdministrationStateStore(directory)
        val second = AppleFileRetryAdministrationStateStore(directory)

        val results = listOf(
            async(Dispatchers.Default) { first.compareSuccess(command) },
            async(Dispatchers.Default) { second.compareSuccess(command) },
        ).awaitAll()

        assertEquals(
            1,
            results.count { it is RetryAdministrationCompareAndSetResult.Updated },
        )
        assertEquals(
            1,
            results.count { it is RetryAdministrationCompareAndSetResult.Conflict },
        )
        val persisted = assertIs<RetryAdministrationLoadResult.Found>(
            first.loadSuccess(request.commandId),
        ).record
        assertEquals(0L, persisted.version)
        val conflict = assertIs<RetryAdministrationCompareAndSetResult.Conflict>(
            results.single { it is RetryAdministrationCompareAndSetResult.Conflict },
        )
        assertEquals(persisted, conflict.current)
    }

    @Test
    fun `every durable command status shape round trips`() = runTest {
        val store = AppleFileRetryAdministrationStateStore(uniqueDirectory())
        val states = listOf(
            authorizedState(request("command-authorized")),
            authorizedState(request("command-succeeded")).copy(
                status = RetryAdministrationCommandStatus.SUCCEEDED,
            ),
            deniedState(request("command-denied")),
            authorizedState(request("command-policy")).copy(
                status = RetryAdministrationCommandStatus.POLICY_REJECTED,
                rejectionReasonCode = "RECLASSIFICATION_REQUIRED",
            ),
            authorizedState(request("command-rejected")).copy(
                status = RetryAdministrationCommandStatus.EXECUTION_REJECTED,
                rejectionReasonCode = "QUEUE_STATE_CHANGED",
            ),
            authorizedState(request("command-failed")).copy(
                status = RetryAdministrationCommandStatus.EXECUTION_FAILED,
                executionFailure = RetryFailureSnapshot(
                    code = ErrorCode("QUEUE_WRITE_FAILED"),
                    category = ErrorCategory.QUEUE,
                    severity = ErrorSeverity.ERROR,
                    recoverability = Recoverability.UNKNOWN,
                ),
            ),
        )

        states.forEach { state ->
            val result = store.compareSuccess(
                RetryAdministrationCompareAndSetRequest(
                    commandId = state.request.commandId,
                    expectedVersion = null,
                    nextState = state,
                ),
            )
            val record = assertIs<RetryAdministrationCompareAndSetResult.Updated>(result).record
            assertEquals(0L, record.version)
        }

        states.forEach { expected ->
            val found = assertIs<RetryAdministrationLoadResult.Found>(
                store.loadSuccess(expected.request.commandId),
            )
            assertEquals(expected, found.record.state)
            assertEquals(0L, found.record.version)
        }
    }

    @Test
    fun `corrupt snapshot fails closed without leaking file content`() = runTest {
        val directory = uniqueDirectory()
        val store = AppleFileRetryAdministrationStateStore(directory)
        val commandId = RetryAdministrationCommandId("command-corrupt")
        assertIs<RetryAdministrationLoadResult.Missing>(store.loadSuccess(commandId))
        val dataPath =
            "$directory/${AppleFileRetryAdministrationStateStore.DEFAULT_FILE_NAME}"
        appleRetryAdminWriteUtf8FileAtomically(
            temporaryPath = "$dataPath.test-tmp",
            destinationPath = dataPath,
            content = "not-a-dataloom-retry-admin-snapshot\nsecret-audit-value",
        )

        val failure = assertIs<ProviderOperationResult.Failure>(store.load(commandId))
        assertEquals("RETRY_ADMIN_APPLE_STATE_CORRUPT", failure.error.code.value)
        assertEquals(ErrorCategory.STATE, failure.error.category)
        assertEquals(Recoverability.NON_RECOVERABLE, failure.error.recoverability)
        assertTrue("secret-audit-value" !in failure.error.message)
        assertEquals(null, failure.error.cause)
    }

    @Test
    fun `version exhaustion fails before file access`() = runTest {
        val request = request(commandId = "command-version-exhausted")
        val store = AppleFileRetryAdministrationStateStore(
            "/dev/null/dataloom-retry-administration",
        )

        val failure = assertIs<ProviderOperationResult.Failure>(
            store.compareAndSet(
                RetryAdministrationCompareAndSetRequest(
                    commandId = request.commandId,
                    expectedVersion = Long.MAX_VALUE,
                    nextState = authorizedState(request),
                ),
            ),
        )
        assertEquals("RETRY_ADMIN_STATE_VERSION_EXHAUSTED", failure.error.code.value)
        assertEquals(ErrorCategory.STATE, failure.error.category)
        assertEquals(Recoverability.NON_RECOVERABLE, failure.error.recoverability)
    }

    @Test
    fun `cancelled caller does not enter the store`() = runTest {
        val store = AppleFileRetryAdministrationStateStore(uniqueDirectory())
        val deferred = async(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            store.load(RetryAdministrationCommandId("command-cancelled"))
        }
        deferred.cancel(CancellationException("caller cancelled"))

        val failure = assertFailsWith<CancellationException> { deferred.await() }
        assertEquals("caller cancelled", failure.message)
    }

    @Test
    fun `constructor rejects unsafe paths without side effects`() {
        assertFailsWith<IllegalArgumentException> {
            AppleFileRetryAdministrationStateStore("relative/path")
        }
        assertFailsWith<IllegalArgumentException> {
            AppleFileRetryAdministrationStateStore("/tmp/safe", "../unsafe")
        }
        assertFailsWith<IllegalArgumentException> {
            AppleFileRetryAdministrationStateStore("/tmp/../unsafe")
        }
    }

    private suspend fun AppleFileRetryAdministrationStateStore.loadSuccess(
        commandId: RetryAdministrationCommandId,
    ): RetryAdministrationLoadResult =
        assertIs<ProviderOperationResult.Success<RetryAdministrationLoadResult>>(
            load(commandId),
        ).value

    private suspend fun AppleFileRetryAdministrationStateStore.compareSuccess(
        request: RetryAdministrationCompareAndSetRequest,
    ): RetryAdministrationCompareAndSetResult =
        assertIs<ProviderOperationResult.Success<RetryAdministrationCompareAndSetResult>>(
            compareAndSet(request),
        ).value

    private fun request(
        commandId: String,
        reason: String = "operator approved retry",
    ): RetryAdministrationRequest = RetryAdministrationRequest(
        commandId = RetryAdministrationCommandId(commandId),
        queueEntryId = QueueEntryId("queue-$commandId"),
        principalId = RetryAdministrationPrincipalId("principal-å"),
        requestedAt = DataLoomInstant(1_000L),
        action = RetryAdministrationAction.REQUEUE,
        reason = RetryAdministrationReason(reason),
        originalFailure = RetryFailureSnapshot(
            code = ErrorCode("NETWORK_TIMEOUT"),
            category = ErrorCategory.NETWORK,
            severity = ErrorSeverity.ERROR,
            recoverability = Recoverability.RECOVERABLE,
        ),
    )

    private fun authorizedState(
        request: RetryAdministrationRequest,
    ): RetryAdministrationCommandState = RetryAdministrationCommandState(
        request = request,
        status = RetryAdministrationCommandStatus.AUTHORIZED,
        authorizationId = RetryAdministrationAuthorizationId("authorization-雪"),
        effectiveRecoverability = Recoverability.RECOVERABLE,
        updatedAt = DataLoomInstant(2_000L),
    )

    private fun deniedState(
        request: RetryAdministrationRequest,
    ): RetryAdministrationCommandState = RetryAdministrationCommandState(
        request = request,
        status = RetryAdministrationCommandStatus.AUTHORIZATION_DENIED,
        authorizationId = null,
        effectiveRecoverability = null,
        updatedAt = DataLoomInstant(2_000L),
        rejectionReasonCode = "PRINCIPAL_NOT_ALLOWED",
    )

    private fun uniqueDirectory(): String = buildString {
        append(NSTemporaryDirectory().trimEnd('/'))
        append("/dataloom-apple-retry-admin-")
        append(NSUUID().UUIDString)
    }
}
