package io.dataloom.queue.room

import io.dataloom.api.error.ErrorCategory
import io.dataloom.api.error.ErrorCode
import io.dataloom.api.error.ErrorSeverity
import io.dataloom.api.error.Recoverability
import io.dataloom.api.identifier.QueueEntryId
import io.dataloom.api.retry.AuthorizedRetryAdministrationCommand
import io.dataloom.api.retry.RetryAdministrationAction
import io.dataloom.api.retry.RetryAdministrationAuthorizationId
import io.dataloom.api.retry.RetryAdministrationCommandId
import io.dataloom.api.retry.RetryAdministrationExecutionResult
import io.dataloom.api.retry.RetryAdministrationPrincipalId
import io.dataloom.api.retry.RetryAdministrationReason
import io.dataloom.api.retry.RetryAdministrationRequest
import io.dataloom.api.retry.RetryFailureSnapshot
import io.dataloom.api.time.DataLoomClock
import io.dataloom.api.time.DataLoomInstant
import io.dataloom.queue.room.internal.DataLoomRoomDatabase
import io.dataloom.queue.room.internal.RetryAdministrationExecutionDao
import io.dataloom.queue.room.internal.RetryAdministrationExecutionEntityResult
import io.dataloom.queue.room.internal.RetryAdministrationExecutionIntegrityException
import io.dataloom.queue.room.internal.RetryAdministrationExecutionVersionExhaustedException
import java.util.concurrent.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class RoomRetryAdministrationExecutorTest {
    private lateinit var database: DataLoomRoomDatabase
    private lateinit var dao: RetryAdministrationExecutionDao
    private lateinit var clock: DataLoomClock
    private lateinit var executor: RoomRetryAdministrationExecutor

    @Before
    fun setUp() {
        database = mock()
        dao = mock()
        clock = mock()
        whenever(database.retryAdministrationExecutionDao()).thenReturn(dao)
        whenever(clock.now()).thenReturn(DataLoomInstant(3_000L))
        executor = RoomRetryAdministrationExecutor(database, clock)
    }

    @Test
    fun `applied transaction maps to applied execution result`() {
        runBlocking {
            whenever(dao.execute(eq(command()), eq(3_000L))).thenReturn(
                RetryAdministrationExecutionEntityResult.Applied,
            )

            assertIs<RetryAdministrationExecutionResult.Applied>(executor.execute(command()))
        }
    }

    @Test
    fun `semantic rejection preserves stable reason code`() {
        runBlocking {
            whenever(dao.execute(any(), eq(3_000L))).thenReturn(
                RetryAdministrationExecutionEntityResult.Rejected("RETRY_ADMIN_TARGET_MISSING"),
            )

            val result = assertIs<RetryAdministrationExecutionResult.Rejected>(
                executor.execute(command()),
            )

            assertEquals("RETRY_ADMIN_TARGET_MISSING", result.reasonCode)
        }
    }

    @Test
    fun `clock regression is a canonical non recoverable state failure`() {
        runBlocking {
            whenever(dao.execute(any(), eq(3_000L))).thenReturn(
                RetryAdministrationExecutionEntityResult.ClockRegression,
            )

            val result = assertIs<RetryAdministrationExecutionResult.Failed>(
                executor.execute(command()),
            )

            assertEquals("RETRY_ADMIN_EXECUTION_CLOCK_REGRESSION", result.error.code.value)
            assertEquals(ErrorCategory.STATE, result.error.category)
            assertEquals(Recoverability.NON_RECOVERABLE, result.error.recoverability)
        }
    }

    @Test
    fun `integrity failure is canonical and redacted`() {
        runBlocking {
            whenever(dao.execute(any(), eq(3_000L))).thenAnswer {
                throw RetryAdministrationExecutionIntegrityException(
                    IllegalStateException("secret"),
                )
            }

            val result = assertIs<RetryAdministrationExecutionResult.Failed>(
                executor.execute(command()),
            )

            assertEquals("RETRY_ADMIN_ROOM_EXECUTOR_STATE_CORRUPT", result.error.code.value)
            assertEquals(
                "Durable administrative retry or queue state failed integrity validation.",
                result.error.message,
            )
        }
    }

    @Test
    fun `version exhaustion is non recoverable`() {
        runBlocking {
            whenever(dao.execute(any(), eq(3_000L))).thenAnswer {
                throw RetryAdministrationExecutionVersionExhaustedException()
            }

            val result = assertIs<RetryAdministrationExecutionResult.Failed>(
                executor.execute(command()),
            )

            assertEquals("RETRY_ADMIN_STATE_VERSION_EXHAUSTED", result.error.code.value)
            assertEquals(Recoverability.NON_RECOVERABLE, result.error.recoverability)
        }
    }

    @Test
    fun `database failure is canonical and recoverable`() {
        runBlocking {
            whenever(dao.execute(any(), eq(3_000L))).thenThrow(
                mock<android.database.sqlite.SQLiteException>(),
            )

            val result = assertIs<RetryAdministrationExecutionResult.Failed>(
                executor.execute(command()),
            )

            assertEquals("RETRY_ADMIN_ROOM_EXECUTOR_DATABASE_FAILURE", result.error.code.value)
            assertEquals(ErrorCategory.STORAGE, result.error.category)
            assertEquals(Recoverability.RECOVERABLE, result.error.recoverability)
        }
    }

    @Test
    fun `database cancellation propagates unchanged`() {
        val expected = CancellationException("cancelled")
        runBlocking {
            whenever(dao.execute(any(), eq(3_000L))).thenThrow(expected)
        }

        val actual = assertFailsWith<CancellationException> {
            runBlocking { executor.execute(command()) }
        }

        assertEquals("cancelled", actual.message)
    }

    private fun command(): AuthorizedRetryAdministrationCommand =
        AuthorizedRetryAdministrationCommand(
            request = RetryAdministrationRequest(
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
            ),
            authorizationId = RetryAdministrationAuthorizationId("authorization-1"),
            effectiveRecoverability = Recoverability.RECOVERABLE,
        )
}
