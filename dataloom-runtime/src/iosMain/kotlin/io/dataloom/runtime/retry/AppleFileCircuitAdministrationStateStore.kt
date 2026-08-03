package io.dataloom.runtime.retry

import io.dataloom.api.circuit.CircuitAdministrationCommandId
import io.dataloom.api.circuit.CircuitAdministrationCompareAndSetRequest
import io.dataloom.api.circuit.CircuitAdministrationCompareAndSetResult
import io.dataloom.api.circuit.CircuitAdministrationLoadResult
import io.dataloom.api.circuit.CircuitAdministrationStateRecord
import io.dataloom.api.circuit.CircuitAdministrationStateStore
import io.dataloom.api.error.DataLoomError
import io.dataloom.api.error.ErrorCategory
import io.dataloom.api.error.ErrorCode
import io.dataloom.api.error.ErrorSeverity
import io.dataloom.api.error.Recoverability
import io.dataloom.api.provider.ProviderOperationResult
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Production Apple file-backed [CircuitAdministrationStateStore].
 *
 * Command audit records share the exact snapshot and lock used by
 * [AppleFileCircuitBreakerStateStore]. Construct both stores with the same
 * directory and file name so an executor can durably commit a circuit mutation
 * and its successful command receipt with one atomic file replacement.
 */
public class AppleFileCircuitAdministrationStateStore(
    directoryPath: String,
    fileName: String = DEFAULT_FILE_NAME,
) : CircuitAdministrationStateStore {
    private val boundary = AppleCircuitStateFileBoundary(directoryPath, fileName)

    override suspend fun load(
        commandId: CircuitAdministrationCommandId,
    ): ProviderOperationResult<CircuitAdministrationLoadResult> = protect {
        boundary.withExclusiveLock {
            val record = boundary.readSnapshot().administrationRecords[commandId.value]
            if (record == null) {
                CircuitAdministrationLoadResult.Missing
            } else {
                CircuitAdministrationLoadResult.Found(record)
            }
        }
    }

    override suspend fun compareAndSet(
        request: CircuitAdministrationCompareAndSetRequest,
    ): ProviderOperationResult<CircuitAdministrationCompareAndSetResult> {
        if (request.expectedVersion == Long.MAX_VALUE) {
            return ProviderOperationResult.Failure(
                AppleCircuitAdministrationStoreError.versionExhausted(),
            )
        }
        return protect {
            boundary.withExclusiveLock {
                val snapshot = boundary.readSnapshot()
                val records = snapshot.administrationRecords
                val key = request.commandId.value
                val current = records[key]
                val versionMatches = when (val expected = request.expectedVersion) {
                    null -> current == null
                    else -> current?.version == expected
                }
                val immutableRequestMatches = current == null ||
                    current.state.request == request.nextState.request
                if (!versionMatches || !immutableRequestMatches) {
                    CircuitAdministrationCompareAndSetResult.Conflict(current)
                } else {
                    if (current == null && records.size >= APPLE_CIRCUIT_ADMIN_MAX_RECORD_COUNT) {
                        throw AppleCircuitAdministrationRecordLimitException()
                    }
                    val nextRecord = CircuitAdministrationStateRecord(
                        state = request.nextState,
                        version = request.expectedVersion?.plus(1L) ?: 0L,
                    )
                    records[key] = nextRecord
                    currentCoroutineContext().ensureActive()
                    boundary.writeSnapshot(snapshot)
                    CircuitAdministrationCompareAndSetResult.Updated(nextRecord)
                }
            }
        }
    }

    private suspend fun <T> protect(
        block: suspend () -> T,
    ): ProviderOperationResult<T> = try {
        currentCoroutineContext().ensureActive()
        ProviderOperationResult.Success(block())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: AppleCircuitStateLimitException) {
        ProviderOperationResult.Failure(
            AppleCircuitAdministrationStoreError.stateLimitExceeded(),
        )
    } catch (_: AppleCircuitAdministrationRecordLimitException) {
        ProviderOperationResult.Failure(
            AppleCircuitAdministrationStoreError.stateLimitExceeded(),
        )
    } catch (_: MalformedAppleCircuitStateException) {
        ProviderOperationResult.Failure(
            AppleCircuitAdministrationStoreError.integrityFailure(),
        )
    } catch (_: AppleCircuitFileException) {
        ProviderOperationResult.Failure(
            AppleCircuitAdministrationStoreError.fileFailure(),
        )
    } catch (_: Exception) {
        ProviderOperationResult.Failure(
            AppleCircuitAdministrationStoreError.fileFailure(),
        )
    }

    public companion object {
        public const val DEFAULT_FILE_NAME: String =
            AppleFileCircuitBreakerStateStore.DEFAULT_FILE_NAME
    }
}

private object AppleCircuitAdministrationStoreError {
    fun fileFailure(): DataLoomError = error(
        code = "CIRCUIT_ADMIN_APPLE_FILE_IO_FAILURE",
        category = ErrorCategory.STORAGE,
        recoverability = Recoverability.RECOVERABLE,
        message = "An Apple circuit-administration file operation failed.",
    )

    fun integrityFailure(): DataLoomError = error(
        code = "CIRCUIT_ADMIN_APPLE_STATE_CORRUPT",
        category = ErrorCategory.STATE,
        recoverability = Recoverability.NON_RECOVERABLE,
        message = "Persisted Apple circuit-administration state failed integrity validation.",
    )

    fun stateLimitExceeded(): DataLoomError = error(
        code = "CIRCUIT_ADMIN_APPLE_STATE_LIMIT_EXCEEDED",
        category = ErrorCategory.STATE,
        recoverability = Recoverability.NON_RECOVERABLE,
        message = "Persisted Apple circuit-administration state exceeds its bounded limit.",
    )

    fun versionExhausted(): DataLoomError = error(
        code = "CIRCUIT_ADMIN_STATE_VERSION_EXHAUSTED",
        category = ErrorCategory.STATE,
        recoverability = Recoverability.NON_RECOVERABLE,
        message = "The circuit-administration record version is exhausted.",
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
