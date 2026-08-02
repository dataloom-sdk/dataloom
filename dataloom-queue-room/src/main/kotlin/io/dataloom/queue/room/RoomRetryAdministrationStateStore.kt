package io.dataloom.queue.room

import io.dataloom.api.error.DataLoomError
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
import io.dataloom.api.retry.RetryAdministrationStateRecord
import io.dataloom.api.retry.RetryAdministrationStateStore
import io.dataloom.api.retry.RetryFailureSnapshot
import io.dataloom.api.time.DataLoomInstant
import io.dataloom.queue.room.internal.DataLoomRoomDatabase
import io.dataloom.queue.room.internal.RetryAdministrationCompareAndSetEntityResult
import io.dataloom.queue.room.internal.RetryAdministrationStateEntity

/** Production Android Room implementation of [RetryAdministrationStateStore]. */
public class RoomRetryAdministrationStateStore(
    database: DataLoomRoomDatabase,
) : RetryAdministrationStateStore {
    private val dao = database.retryAdministrationStateDao()

    override suspend fun load(
        commandId: RetryAdministrationCommandId,
    ): ProviderOperationResult<RetryAdministrationLoadResult> = protect {
        val entity = dao.load(commandId.value)
        if (entity == null) {
            RetryAdministrationLoadResult.Missing
        } else {
            RetryAdministrationLoadResult.Found(entity.toRecord())
        }
    }

    override suspend fun compareAndSet(
        request: RetryAdministrationCompareAndSetRequest,
    ): ProviderOperationResult<RetryAdministrationCompareAndSetResult> {
        if (request.expectedVersion == Long.MAX_VALUE) {
            return ProviderOperationResult.Failure(RoomRetryAdministrationStoreError.versionExhausted())
        }
        return protect {
            val nextVersion = request.expectedVersion?.plus(1L) ?: 0L
            val next = request.nextState.toEntity(nextVersion)
            when (val result = dao.compareAndSet(request.expectedVersion, next)) {
                is RetryAdministrationCompareAndSetEntityResult.Updated ->
                    RetryAdministrationCompareAndSetResult.Updated(result.entity.toRecord())
                is RetryAdministrationCompareAndSetEntityResult.Conflict ->
                    RetryAdministrationCompareAndSetResult.Conflict(result.current?.toRecord())
            }
        }
    }

    private suspend fun <T> protect(block: suspend () -> T): ProviderOperationResult<T> = try {
        ProviderOperationResult.Success(block())
    } catch (cancelled: kotlin.coroutines.cancellation.CancellationException) {
        throw cancelled
    } catch (_: MalformedRetryAdministrationStateException) {
        ProviderOperationResult.Failure(RoomRetryAdministrationStoreError.integrityFailure())
    } catch (_: Exception) {
        ProviderOperationResult.Failure(RoomRetryAdministrationStoreError.databaseFailure())
    }
}

private fun RetryAdministrationCommandState.toEntity(
    version: Long,
): RetryAdministrationStateEntity = RetryAdministrationStateEntity(
    commandId = request.commandId.value,
    queueEntryId = request.queueEntryId.value,
    principalId = request.principalId.value,
    requestedAtMs = request.requestedAt.epochMilliseconds,
    action = request.action.name,
    reason = request.reason.value,
    originalErrorCode = request.originalFailure.code.value,
    originalErrorCategory = request.originalFailure.category.name,
    originalErrorSeverity = request.originalFailure.severity.name,
    originalErrorRecoverability = request.originalFailure.recoverability.name,
    status = status.name,
    authorizationId = authorizationId?.value,
    effectiveRecoverability = effectiveRecoverability?.name,
    updatedAtMs = updatedAt.epochMilliseconds,
    rejectionReasonCode = rejectionReasonCode,
    executionErrorCode = executionFailure?.code?.value,
    executionErrorCategory = executionFailure?.category?.name,
    executionErrorSeverity = executionFailure?.severity?.name,
    executionErrorRecoverability = executionFailure?.recoverability?.name,
    recordVersion = version,
)

private fun RetryAdministrationStateEntity.toRecord(): RetryAdministrationStateRecord = try {
    val executionColumns = listOf(
        executionErrorCode,
        executionErrorCategory,
        executionErrorSeverity,
        executionErrorRecoverability,
    )
    check(executionColumns.all { it == null } || executionColumns.all { it != null }) {
        "Persisted retry-administration execution failure is only partially populated."
    }
    val executionFailure = if (executionColumns.all { it == null }) {
        null
    } else {
        RetryFailureSnapshot(
            code = ErrorCode(checkNotNull(executionErrorCode)),
            category = ErrorCategory.valueOf(checkNotNull(executionErrorCategory)),
            severity = ErrorSeverity.valueOf(checkNotNull(executionErrorSeverity)),
            recoverability = Recoverability.valueOf(checkNotNull(executionErrorRecoverability)),
        )
    }

    RetryAdministrationStateRecord(
        state = RetryAdministrationCommandState(
            request = RetryAdministrationRequest(
                commandId = RetryAdministrationCommandId(commandId),
                queueEntryId = QueueEntryId(queueEntryId),
                principalId = RetryAdministrationPrincipalId(principalId),
                requestedAt = DataLoomInstant(requestedAtMs),
                action = RetryAdministrationAction.valueOf(action),
                reason = RetryAdministrationReason(reason),
                originalFailure = RetryFailureSnapshot(
                    code = ErrorCode(originalErrorCode),
                    category = ErrorCategory.valueOf(originalErrorCategory),
                    severity = ErrorSeverity.valueOf(originalErrorSeverity),
                    recoverability = Recoverability.valueOf(originalErrorRecoverability),
                ),
            ),
            status = RetryAdministrationCommandStatus.valueOf(status),
            authorizationId = authorizationId?.let(::RetryAdministrationAuthorizationId),
            effectiveRecoverability = effectiveRecoverability?.let(Recoverability::valueOf),
            updatedAt = DataLoomInstant(updatedAtMs),
            rejectionReasonCode = rejectionReasonCode,
            executionFailure = executionFailure,
        ),
        version = recordVersion,
    )
} catch (invalid: IllegalArgumentException) {
    throw MalformedRetryAdministrationStateException(invalid)
} catch (invalid: IllegalStateException) {
    throw MalformedRetryAdministrationStateException(invalid)
}

private class MalformedRetryAdministrationStateException(cause: Throwable) : Exception(cause)

private object RoomRetryAdministrationStoreError {
    fun databaseFailure(): DataLoomError = error(
        code = "RETRY_ADMIN_ROOM_DATABASE_FAILURE",
        category = ErrorCategory.STORAGE,
        recoverability = Recoverability.RECOVERABLE,
        message = "A retry-administration database operation failed.",
    )

    fun integrityFailure(): DataLoomError = error(
        code = "RETRY_ADMIN_ROOM_STATE_CORRUPT",
        category = ErrorCategory.STATE,
        recoverability = Recoverability.NON_RECOVERABLE,
        message = "Persisted retry-administration state failed integrity validation.",
    )

    fun versionExhausted(): DataLoomError = error(
        code = "RETRY_ADMIN_STATE_VERSION_EXHAUSTED",
        category = ErrorCategory.STATE,
        recoverability = Recoverability.NON_RECOVERABLE,
        message = "The retry-administration record version is exhausted.",
    )

    private fun error(
        code: String,
        category: ErrorCategory,
        recoverability: Recoverability,
        message: String,
    ): DataLoomError = Error(
        code = ErrorCode(code),
        category = category,
        severity = ErrorSeverity.ERROR,
        recoverability = recoverability,
        message = message,
    )

    private data class Error(
        override val code: ErrorCode,
        override val category: ErrorCategory,
        override val severity: ErrorSeverity,
        override val recoverability: Recoverability,
        override val message: String,
        override val cause: Throwable? = null,
    ) : DataLoomError
}