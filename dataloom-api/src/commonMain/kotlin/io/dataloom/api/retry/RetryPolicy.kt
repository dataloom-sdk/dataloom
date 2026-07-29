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
 * ## Why evaluation is synchronous
 *
 * Retry-policy evaluation is deliberately synchronous. A policy should
 * calculate a decision using already-available information. It must not make
 * network requests, query storage, refresh credentials, call providers, sleep,
 * wait for connectivity, or schedule background work.
 *
 * ## Recoverability and protected failures
 *
 * DataLoom runtime retry paths enforce a fail-closed boundary before invoking
 * an application-provided policy:
 *
 * - [io.dataloom.api.error.Recoverability.NON_RECOVERABLE] stops with
 *   [RetryStopReason.NON_RECOVERABLE].
 * - [io.dataloom.api.error.Recoverability.UNKNOWN] stops until an explicit,
 *   authorized reclassification path exists.
 * - Authentication, authorization, serialization, validation, configuration,
 *   policy, conflict, and security categories are protected from automatic
 *   retry.
 * - [io.dataloom.api.error.Recoverability.RECOVERABLE] errors outside those
 *   protected categories may be evaluated by the configured policy.
 *
 * A partially successful result containing any protected error blocks the
 * complete retry batch. A retryable sibling error cannot hide a protected
 * failure.
 *
 * Severity alone must not determine retry behaviour.
 *
 * ## Coroutine cancellation
 *
 * [evaluate] must not catch or translate `CancellationException`. Coroutine
 * cancellation must propagate normally and must not become a [RetryDecision].
 *
 * ## Dependency injection
 *
 * Policy implementations may receive configuration through constructors or
 * constructor-injected dependencies. DataLoom does not depend on a particular
 * dependency-injection framework.
 *
 * ## Evaluation restrictions
 *
 * [evaluate] must not block, sleep, access network services or storage,
 * schedule work, mutate queues, execute provider operations, automatically log
 * sensitive context, expose provider-specific exception types, or catch
 * coroutine cancellation.
 */
public interface RetryPolicy {

    /** Stable non-blank identifier used for configuration and diagnostics. */
    public val id: RetryPolicyId

    /**
     * Evaluates [request] synchronously and deterministically.
     *
     * For the same request and policy configuration, implementations must
     * return the same decision.
     */
    public fun evaluate(
        request: RetryEvaluationRequest,
    ): RetryDecision
}
