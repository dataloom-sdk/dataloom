package io.dataloom.queue.room

import io.dataloom.api.error.DataLoomError
import io.dataloom.api.error.ErrorCategory
import io.dataloom.api.error.ErrorCode
import io.dataloom.api.error.ErrorSeverity
import io.dataloom.api.error.Recoverability
import io.dataloom.api.error.safeDiagnosticString
import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.api.state.DurableStateCodec
import io.dataloom.api.state.DurableStateCompareAndSetRequest
import io.dataloom.api.state.DurableStateCompareAndSetResult
import io.dataloom.api.state.DurableStateLoadResult
import io.dataloom.api.state.DurableStateRecord
import io.dataloom.api.state.DurableStateScopeKeyEncoder
import io.dataloom.api.state.DurableStateStore
import io.dataloom.queue.room.internal.DataLoomRoomDatabase
import io.dataloom.queue.room.internal.DurableStateCompareAndSetEntityResult
import io.dataloom.queue.room.internal.DurableStateEntity

/**
 * Production Android Room implementation of [DurableStateStore], generic
 * over any [TScope]/[TState] a domain supplies encoding for via
 * [scopeKeyEncoder] and [codec].
 *
 * Multiple domains can safely share one [DataLoomRoomDatabase]: [namespace]
 * plus the encoded scope key form each row's composite primary key, so two
 * domains whose [TScope] encodings happen to collide never collide with
 * each other's rows. Give each domain adopting [DurableStateStore] its own
 * stable, unique [namespace] (for example `"configuration-history"`).
 */
public class RoomDurableStateStore<TScope : Any, TState : Any>(
    database: DataLoomRoomDatabase,
    private val namespace: String,
    private val scopeKeyEncoder: DurableStateScopeKeyEncoder<TScope>,
    private val codec: DurableStateCodec<TState>,
) : DurableStateStore<TScope, TState> {
    init {
        require(namespace.isNotBlank()) { "RoomDurableStateStore namespace must not be blank." }
    }

    private val dao = database.durableStateDao()

    override suspend fun load(
        scope: TScope,
    ): ProviderOperationResult<DurableStateLoadResult<TState>> = protect {
        val entity = dao.load(namespace, scopeKeyEncoder.encode(scope))
        if (entity == null) {
            DurableStateLoadResult.Missing
        } else {
            DurableStateLoadResult.Found(entity.toDurableStateRecord())
        }
    }

    override suspend fun compareAndSet(
        request: DurableStateCompareAndSetRequest<TScope, TState>,
    ): ProviderOperationResult<DurableStateCompareAndSetResult<TState>> {
        if (request.expectedVersion == Long.MAX_VALUE) {
            return ProviderOperationResult.Failure(RoomDurableStateStoreError.versionExhausted())
        }
        return protect {
            val payload = encodePayload(request.nextState)
            val nextVersion = request.expectedVersion?.plus(1L) ?: 0L
            val next = DurableStateEntity(
                namespace = namespace,
                scopeKey = scopeKeyEncoder.encode(request.scope),
                statePayload = payload,
                schemaVersion = request.nextSchemaVersion,
                recordVersion = nextVersion,
            )
            when (val result = dao.compareAndSet(request.expectedVersion, next)) {
                is DurableStateCompareAndSetEntityResult.Updated ->
                    DurableStateCompareAndSetResult.Updated(result.entity.toDurableStateRecord())
                is DurableStateCompareAndSetEntityResult.Conflict ->
                    DurableStateCompareAndSetResult.Conflict(result.current?.toDurableStateRecord())
            }
        }
    }

    private fun encodePayload(state: TState): String {
        val payload = try {
            codec.encode(state)
        } catch (invalid: Exception) {
            throw DurableStateEncodeFailureException(invalid)
        }
        if (payload.length > MAX_PAYLOAD_LENGTH) {
            throw DurableStatePayloadTooLargeException()
        }
        return payload
    }

    private fun DurableStateEntity.toDurableStateRecord(): DurableStateRecord<TState> = try {
        DurableStateRecord(state = codec.decode(statePayload), version = recordVersion, schemaVersion = schemaVersion)
    } catch (invalid: Exception) {
        throw MalformedDurableStateException(invalid)
    }

    private suspend fun <T> protect(block: suspend () -> T): ProviderOperationResult<T> = try {
        ProviderOperationResult.Success(block())
    } catch (cancelled: kotlin.coroutines.cancellation.CancellationException) {
        throw cancelled
    } catch (_: DurableStatePayloadTooLargeException) {
        ProviderOperationResult.Failure(RoomDurableStateStoreError.payloadTooLarge())
    } catch (_: DurableStateEncodeFailureException) {
        ProviderOperationResult.Failure(RoomDurableStateStoreError.encodeFailure())
    } catch (_: MalformedDurableStateException) {
        ProviderOperationResult.Failure(RoomDurableStateStoreError.integrityFailure())
    } catch (_: Exception) {
        ProviderOperationResult.Failure(RoomDurableStateStoreError.databaseFailure())
    }

    private companion object {
        const val MAX_PAYLOAD_LENGTH: Int = 4 * 1024 * 1024
    }
}

private class MalformedDurableStateException(cause: Throwable) : Exception(cause)
private class DurableStateEncodeFailureException(cause: Throwable) : Exception(cause)
private class DurableStatePayloadTooLargeException : Exception()

private object RoomDurableStateStoreError {
    fun databaseFailure(): DataLoomError = error(
        code = "DURABLE_STATE_ROOM_DATABASE_FAILURE",
        category = ErrorCategory.STORAGE,
        recoverability = Recoverability.RECOVERABLE,
        message = "A durable-state database operation failed.",
    )

    fun integrityFailure(): DataLoomError = error(
        code = "DURABLE_STATE_ROOM_STATE_CORRUPT",
        category = ErrorCategory.STATE,
        recoverability = Recoverability.NON_RECOVERABLE,
        message = "Persisted durable state failed integrity validation.",
    )

    fun encodeFailure(): DataLoomError = error(
        code = "DURABLE_STATE_ROOM_ENCODE_FAILURE",
        category = ErrorCategory.STATE,
        recoverability = Recoverability.NON_RECOVERABLE,
        message = "The next durable-state value could not be encoded.",
    )

    fun versionExhausted(): DataLoomError = error(
        code = "DURABLE_STATE_VERSION_EXHAUSTED",
        category = ErrorCategory.STATE,
        recoverability = Recoverability.NON_RECOVERABLE,
        message = "The durable-state record version is exhausted.",
    )

    fun payloadTooLarge(): DataLoomError = error(
        code = "DURABLE_STATE_ROOM_PAYLOAD_TOO_LARGE",
        category = ErrorCategory.STATE,
        recoverability = Recoverability.NON_RECOVERABLE,
        message = "Encoded durable-state payload exceeds the bounded limit.",
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
    ) : DataLoomError {
        override fun toString(): String = safeDiagnosticString()
    }
}
