package io.dataloom.runtime.retry

import io.dataloom.api.error.DataLoomError
import io.dataloom.api.error.ErrorCategory
import io.dataloom.api.error.ErrorCode
import io.dataloom.api.error.ErrorSeverity
import io.dataloom.api.error.Recoverability
import io.dataloom.api.provider.ProviderDescriptor
import io.dataloom.api.provider.ProviderHealth
import io.dataloom.api.provider.ProviderInitializationContext
import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.api.queue.ExpiredLeaseRecoveryRequest
import io.dataloom.api.queue.ExpiredLeaseRecoveryResult
import io.dataloom.api.queue.QueueAcquireRequest
import io.dataloom.api.queue.QueueAcquireResult
import io.dataloom.api.queue.QueueCancellationRequest
import io.dataloom.api.queue.QueueCompletionRequest
import io.dataloom.api.queue.QueueDeferralRequest
import io.dataloom.api.queue.QueueEnqueueRequest
import io.dataloom.api.queue.QueueFailureRequest
import io.dataloom.api.queue.QueueProvider
import io.dataloom.api.queue.QueueRescheduleRequest

/**
 * [QueueProvider] decorator that enforces one configured provider-timeout
 * boundary for every lifecycle and queue operation.
 *
 * The decorator preserves the delegate descriptor and exact completed
 * [ProviderOperationResult] values. Only expiration of [timeoutCoordinator]
 * becomes a bounded canonical timeout failure. Caller cancellation and
 * unexpected programming exceptions propagate unchanged.
 *
 * ## Durable ambiguity
 *
 * Queue mutations may commit durably before cancellation is observed. A timeout
 * therefore reports [Recoverability.UNKNOWN] for every operation except the
 * read-only [health] check. The decorator never retries, replays, or assumes
 * rollback. Callers must reconcile durable state through normal lease, lookup,
 * and recovery semantics before attempting another mutation.
 *
 * The common coroutine timeout is cooperative. Blocking implementations without
 * cancellation checkpoints require a platform-specific hard-interruption
 * adapter.
 */
public class TimeoutEnforcingQueueProvider(
    private val delegate: QueueProvider,
    private val timeoutCoordinator: RetryTimeoutCoordinator,
) : QueueProvider {

    override val descriptor: ProviderDescriptor
        get() = delegate.descriptor

    override suspend fun initialize(
        context: ProviderInitializationContext,
    ): ProviderOperationResult<Unit> = execute(QueueOperation.INITIALIZE) {
        delegate.initialize(context)
    }

    override suspend fun health(): ProviderOperationResult<ProviderHealth> =
        execute(QueueOperation.HEALTH) {
            delegate.health()
        }

    override suspend fun close(): ProviderOperationResult<Unit> =
        execute(QueueOperation.CLOSE) {
            delegate.close()
        }

    override suspend fun enqueue(
        request: QueueEnqueueRequest,
    ): ProviderOperationResult<Unit> = execute(QueueOperation.ENQUEUE) {
        delegate.enqueue(request)
    }

    override suspend fun acquire(
        request: QueueAcquireRequest,
    ): ProviderOperationResult<QueueAcquireResult> = execute(QueueOperation.ACQUIRE) {
        delegate.acquire(request)
    }

    override suspend fun complete(
        request: QueueCompletionRequest,
    ): ProviderOperationResult<Unit> = execute(QueueOperation.COMPLETE) {
        delegate.complete(request)
    }

    override suspend fun reschedule(
        request: QueueRescheduleRequest,
    ): ProviderOperationResult<Unit> = execute(QueueOperation.RESCHEDULE) {
        delegate.reschedule(request)
    }

    override suspend fun defer(
        request: QueueDeferralRequest,
    ): ProviderOperationResult<Unit> = execute(QueueOperation.DEFER) {
        delegate.defer(request)
    }

    override suspend fun fail(
        request: QueueFailureRequest,
    ): ProviderOperationResult<Unit> = execute(QueueOperation.FAIL) {
        delegate.fail(request)
    }

    override suspend fun cancel(
        request: QueueCancellationRequest,
    ): ProviderOperationResult<Unit> = execute(QueueOperation.CANCEL) {
        delegate.cancel(request)
    }

    override suspend fun recoverExpiredLeases(
        request: ExpiredLeaseRecoveryRequest,
    ): ProviderOperationResult<ExpiredLeaseRecoveryResult> = execute(QueueOperation.RECOVER_EXPIRED_LEASES) {
        delegate.recoverExpiredLeases(request)
    }

    private suspend fun <T> execute(
        operation: QueueOperation,
        block: suspend () -> ProviderOperationResult<T>,
    ): ProviderOperationResult<T> = when (
        val result = timeoutCoordinator.execute(
            kind = RetryTimeoutKind.PROVIDER,
            operation = block,
        )
    ) {
        is RetryTimeoutExecutionResult.Completed -> result.value
        is RetryTimeoutExecutionResult.TimedOut -> ProviderOperationResult.Failure(
            QueueTimeoutErrors.providerTimedOut(operation),
        )
        is RetryTimeoutExecutionResult.WorkflowDeadlineExceeded -> ProviderOperationResult.Failure(
            QueueTimeoutErrors.workflowDeadlineExceeded(operation),
        )
        is RetryTimeoutExecutionResult.ClockRegression -> ProviderOperationResult.Failure(
            QueueTimeoutErrors.clockRegression(operation),
        )
    }
}

private enum class QueueOperation(
    val label: String,
    val readOnly: Boolean = false,
) {
    INITIALIZE("initialize"),
    HEALTH("health", readOnly = true),
    CLOSE("close"),
    ENQUEUE("enqueue"),
    ACQUIRE("acquire"),
    COMPLETE("complete"),
    RESCHEDULE("reschedule"),
    DEFER("defer"),
    FAIL("fail"),
    CANCEL("cancel"),
    RECOVER_EXPIRED_LEASES("recover-expired-leases"),
}

private object QueueTimeoutErrors {
    fun providerTimedOut(operation: QueueOperation): DataLoomError = Error(
        code = ErrorCode("QUEUE_PROVIDER_TIMEOUT"),
        category = ErrorCategory.QUEUE,
        severity = ErrorSeverity.ERROR,
        recoverability = if (operation.readOnly) {
            Recoverability.RECOVERABLE
        } else {
            Recoverability.UNKNOWN
        },
        message = if (operation.readOnly) {
            "The queue provider ${operation.label} operation exceeded its configured timeout."
        } else {
            "The queue provider ${operation.label} operation exceeded its configured timeout; " +
                "durable completion is not confirmed."
        },
    )

    fun workflowDeadlineExceeded(operation: QueueOperation): DataLoomError = Error(
        code = ErrorCode("QUEUE_WORKFLOW_DEADLINE_EXCEEDED"),
        category = ErrorCategory.QUEUE,
        severity = ErrorSeverity.ERROR,
        recoverability = Recoverability.NON_RECOVERABLE,
        message = "The workflow deadline expired before queue provider ${operation.label} completed.",
    )

    fun clockRegression(operation: QueueOperation): DataLoomError = Error(
        code = ErrorCode("QUEUE_TIMEOUT_CLOCK_REGRESSION"),
        category = ErrorCategory.STATE,
        severity = ErrorSeverity.ERROR,
        recoverability = Recoverability.NON_RECOVERABLE,
        message = "Clock regression prevented queue provider ${operation.label} timeout enforcement.",
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
