package io.dataloom.queue.room

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
import io.dataloom.queue.room.internal.DataLoomRoomDatabase
import io.dataloom.queue.room.internal.RetryAdministrationCompareAndSetEntityResult
import io.dataloom.queue.room.internal.RetryAdministrationStateDao
import io.dataloom.queue.room.internal.RetryAdministrationStateEntity
import java.util.concurrent.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class RoomRetryAdministrationStateStoreTest {
    private lateinit var database: DataLoomRoomDatabase
    private lateinit var dao: RetryAdministrationStateDao
    private lateinit var store: RoomRetryAdministrationStateStore

    @Before
    fun setUp() {
        database = mock()
        dao = mock()
        whenever(database.retryAdministrationStateDao()).thenReturn(dao)
        store = RoomRetryAdministrationStateStore(database)
    }

    @Test
    fun `missing command is returned explicitly`() {
        runBlocking {
            whenever(dao.load(any())).thenReturn(null)

            val result = assertIs<ProviderOperationResult.Success<RetryAdministrationLoadResult>>(
                store.load(RetryAdministrationCommandId("command-1")),
            )

            assertIs<RetryAdministrationLoadResult.Missing>(result.value)
        }
    }

    @Test
    fun `compare and set conflict preserves current immutable command and version`() {
        runBlocking {
            val current = validEntity(recordVersion = 3L)
            whenever(dao.compareAndSet(eq(2L), any())).thenReturn(
                RetryAdministrationCompareAndSetEntityResult.Conflict(current),
            )

            val result = assertIs<ProviderOperationResult.Success<RetryAdministrationCompareAndSetResult>>(
                store.compareAndSet(
                    RetryAdministrationCompareAndSetRequest(
                        commandId = RetryAdministrationCommandId("command-1"),
                        expectedVersion = 2L,
                        nextState = authorizedState(),
                    ),
                ),
            )
            val conflict = assertIs<RetryAdministrationCompareAndSetResult.Conflict>(result.value)

            assertEquals(3L, conflict.current?.version)
            assertEquals("entry-1", conflict.current?.state?.request?.queueEntryId?.value)
            assertEquals("operator-1", conflict.current?.state?.request?.principalId?.value)
        }
    }

    @Test
    fun `partial execution failure evidence fails closed`() {
        runBlocking {
            whenever(dao.load(any())).thenReturn(
                validEntity(
                    status = RetryAdministrationCommandStatus.EXECUTION_FAILED.name,
                    executionErrorCode = "REMOTE_FAILURE",
                    executionErrorCategory = null,
                ),
            )

            val result = assertIs<ProviderOperationResult.Failure>(
                store.load(RetryAdministrationCommandId("command-1")),
            )

            assertEquals("RETRY_ADMIN_ROOM_STATE_CORRUPT", result.error.code.value)
            assertEquals(Recoverability.NON_RECOVERABLE, result.error.recoverability)
        }
    }

    @Test
    fun `version exhaustion is non recoverable and does not access Room`() {
        runBlocking {
            val result = assertIs<ProviderOperationResult.Failure>(
                store.compareAndSet(
                    RetryAdministrationCompareAndSetRequest(
                        commandId = RetryAdministrationCommandId("command-1"),
                        expectedVersion = Long.MAX_VALUE,
                        nextState = authorizedState(),
                    ),
                ),
            )

            assertEquals("RETRY_ADMIN_STATE_VERSION_EXHAUSTED", result.error.code.value)
            assertEquals(Recoverability.NON_RECOVERABLE, result.error.recoverability)
            verifyNoInteractions(dao)
        }
    }

    @Test
    fun `database failure is sanitized and recoverable`() {
        runBlocking {
            whenever(dao.load(any())).thenThrow(mock<android.database.sqlite.SQLiteException>())

            val result = assertIs<ProviderOperationResult.Failure>(
                store.load(RetryAdministrationCommandId("command-1")),
            )

            assertEquals("RETRY_ADMIN_ROOM_DATABASE_FAILURE", result.error.code.value)
            assertEquals(Recoverability.RECOVERABLE, result.error.recoverability)
            assertEquals("A retry-administration database operation failed.", result.error.message)
        }
    }

    @Test
    fun `database cancellation propagates unchanged`() {
        val expected = CancellationException("cancelled")
        runBlocking {
            whenever(dao.load(any())).thenThrow(expected)
        }

        val actual = assertFailsWith<CancellationException> {
            runBlocking { store.load(RetryAdministrationCommandId("command-1")) }
        }

        assertEquals("cancelled", actual.message)
    }

    private fun authorizedState(): RetryAdministrationCommandState = RetryAdministrationCommandState(
        request = request(),
        status = RetryAdministrationCommandStatus.AUTHORIZED,
        authorizationId = RetryAdministrationAuthorizationId("authorization-1"),
        effectiveRecoverability = Recoverability.RECOVERABLE,
        updatedAt = DataLoomInstant(2_000L),
    )

    private fun request(): RetryAdministrationRequest = RetryAdministrationRequest(
        commandId = RetryAdministrationCommandId("command-1"),
        queueEntryId = QueueEntryId("entry-1"),
        principalId = RetryAdministrationPrincipalId("operator-1"),
        requestedAt = DataLoomInstant(1_000L),
        action = RetryAdministrationAction.REQUEUE,
        reason = RetryAdministrationReason("Operator requested retry"),
        originalFailure = RetryFailureSnapshot(
            code = ErrorCode("NETWORK_FAILURE"),
            category = ErrorCategory.NETWORK,
            severity = ErrorSeverity.ERROR,
            recoverability = Recoverability.RECOVERABLE,
        ),
    )

    private fun validEntity(
        recordVersion: Long = 0L,
        status: String = RetryAdministrationCommandStatus.AUTHORIZED.name,
        executionErrorCode: String? = null,
        executionErrorCategory: String? = null,
    ): RetryAdministrationStateEntity = RetryAdministrationStateEntity(
        commandId = "command-1",
        queueEntryId = "entry-1",
        principalId = "operator-1",
        requestedAtMs = 1_000L,
        action = RetryAdministrationAction.REQUEUE.name,
        reason = "Operator requested retry",
        originalErrorCode = "NETWORK_FAILURE",
        originalErrorCategory = ErrorCategory.NETWORK.name,
        originalErrorSeverity = ErrorSeverity.ERROR.name,
        originalErrorRecoverability = Recoverability.RECOVERABLE.name,
        status = status,
        authorizationId = "authorization-1",
        effectiveRecoverability = Recoverability.RECOVERABLE.name,
        updatedAtMs = 2_000L,
        rejectionReasonCode = null,
        executionErrorCode = executionErrorCode,
        executionErrorCategory = executionErrorCategory,
        executionErrorSeverity = if (executionErrorCode == null) null else ErrorSeverity.ERROR.name,
        executionErrorRecoverability = if (executionErrorCode == null) null else Recoverability.UNKNOWN.name,
        recordVersion = recordVersion,
    )
}