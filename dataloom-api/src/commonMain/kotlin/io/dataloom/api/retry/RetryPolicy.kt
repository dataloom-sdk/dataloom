package io.dataloom.api.retry

import io.dataloom.api.identifier.RetryPolicyId

/**
 * Platform-independent contract for evaluating whether a failed
 * synchronization operation should be retried and, if so, after how long.
 *
 * A [RetryPolicy] receives a [RetryEvaluationRequest] containing all
 * already-available information about the failure and returns a [RetryDecision]
 * that the DataLoom runtime acts on.
 *
 * ```text
 * Provider operation fails
 *       ↓
 * DataLoomError
 *       ↓
 * RetryPolicy.evaluate(...)
 *       ↓
 * RetryDecision
 *       ├── Retry after delay
 *       └── Stop retrying
 * ```
 *
 * ## Why evaluation is synchronous
 *
 * Retry-policy evaluation is deliberately synchronous. A policy should
 * calculate a decision using already-available information. It must not:
 *
 * - Make network requests
 * - Query a database
 * - Refresh credentials
 * - Call providers
 * - Sleep or wait for connectivity
 * - Schedule background work
 *
 * The runtime performs those operations after receiving the decision. This
 * keeps retry evaluation deterministic, fast, testable, multiplatform, and
 * independent of runtime infrastructure.
 *
 * ## Recoverability semantics
 *
 * - [io.dataloom.api.error.Recoverability.NON_RECOVERABLE]: the normal
 *   decision is [RetryDecision.Stop] with
 *   [RetryStopReason.NON_RECOVERABLE].
 * - [io.dataloom.api.error.Recoverability.RECOVERABLE]: the policy may
 *   return either [RetryDecision.Retry] or [RetryDecision.Stop].
 * - [io.dataloom.api.error.Recoverability.UNKNOWN]: the configured policy
 *   determines whether retrying is safe.
 *
 * Severity alone must not determine retry behaviour. `CRITICAL` does not
 * automatically mean retry. `WARNING` does not automatically mean continue.
 *
 * ## Coroutine cancellation
 *
 * [evaluate] must not catch or translate `CancellationException`.
 * Coroutine cancellation must propagate normally and must not be converted
 * into a [RetryDecision].
 *
 * ## Dependency injection
 *
 * Policy implementations may receive configuration through constructors or
 * constructor-injected dependencies. DataLoom does not depend on Hilt, Koin,
 * Dagger, or any other dependency-injection framework.
 *
 * ## Thread safety
 *
 * Implementations must document their thread-safety guarantees.
 *
 * ## Evaluation restrictions
 *
 * [evaluate] must not:
 * - Block the current thread
 * - Sleep
 * - Access network services
 * - Access application storage
 * - Schedule work
 * - Mutate queues
 * - Execute provider operations
 * - Automatically log sensitive context
 * - Expose provider-specific exception types
 * - Catch or translate coroutine cancellation
 */
public interface RetryPolicy {

    /**
     * Stable identifier for this retry policy.
     *
     * Used by the runtime to identify the policy in diagnostics and
     * configuration. The identifier must not be blank.
     */
    public val id: RetryPolicyId

    /**
     * Evaluates the supplied [request] and returns a [RetryDecision].
     *
     * Evaluation is synchronous and deterministic: for the same [request] and
     * policy configuration, [evaluate] must always return the same decision.
     *
     * ## Restrictions
     *
     * This function must not block the current thread, sleep, access network
     * services, access application storage, schedule work, mutate queues,
     * execute provider operations, automatically log sensitive context, or
     * catch coroutine cancellation.
     *
     * @param request the immutable evaluation request carrying the failed
     *   synchronization request, the logical operation, the canonical error,
     *   the current retry attempt, the previous delay, the optional provider
     *   descriptor, and optional metadata.
     * @return a [RetryDecision] indicating whether to retry after a delay or
     *   stop retrying.
     */
    public fun evaluate(
        request: RetryEvaluationRequest,
    ): RetryDecision
}
