package io.dataloom.runtime.retry

import kotlinx.coroutines.withTimeoutOrNull

/**
 * Kotlin Multiplatform [RetryTimeoutExecutor] backed by structured coroutine
 * cancellation.
 *
 * The executor starts no independent scope and selects no dispatcher. It runs
 * [operation] in the caller's coroutine context and uses `withTimeoutOrNull` to
 * cancel that child operation when [RetryTimeoutExecutionRequest.timeout]
 * expires.
 *
 * Caller cancellation and timeout exceptions created by nested operations are
 * not translated. Only expiration of this executor's own timeout becomes
 * [RetryTimeoutExecutionResult.TimedOut]. Operations that block without a
 * suspension or other cancellation checkpoint cannot be preempted by a
 * coroutine timeout and must be adapted by a platform-specific executor when
 * hard interruption is required.
 */
public class CoroutineRetryTimeoutExecutor : RetryTimeoutExecutor {

    override suspend fun <T> execute(
        request: RetryTimeoutExecutionRequest,
        operation: suspend () -> T,
    ): RetryTimeoutExecutionResult<T> {
        val completed = withTimeoutOrNull(request.timeout.milliseconds) {
            CompletedValue(operation())
        }
        return if (completed == null) {
            RetryTimeoutExecutionResult.TimedOut(
                kind = request.kind,
                timeout = request.timeout,
            )
        } else {
            RetryTimeoutExecutionResult.Completed(completed.value)
        }
    }
}

private class CompletedValue<out T>(val value: T)
