package io.dataloom.api.policy

import io.dataloom.api.identifier.PolicyCheckId

/**
 * Platform-independent contract for one deterministic, side-effect-free,
 * bounded policy rule.
 *
 * A [PolicyCheck] is the generalization of
 * [io.dataloom.api.retry.RetryPolicy]'s own `evaluate` shape: it receives a
 * [PolicyEvaluationInput] containing everything already available about
 * execution context, state, provider health, and configuration, and returns
 * exactly one [PolicyCheckOutcome]. [PolicySet] composes many [PolicyCheck]
 * instances into one deterministic, ordered evaluation; [PolicyEvaluator]
 * performs that composition.
 *
 * ## Why evaluation is synchronous
 *
 * Exactly [io.dataloom.api.retry.RetryPolicy]'s own rationale: a policy check
 * calculates a result using already-available information. It must not make
 * network requests, query storage, refresh credentials, call providers,
 * sleep, wait for connectivity, or schedule background work.
 *
 * ## Determinism
 *
 * For the same [PolicyEvaluationInput] (structural equality) and the same
 * check configuration, [evaluate] must always return an equal
 * [PolicyCheckOutcome].
 *
 * ## Coroutine cancellation
 *
 * [evaluate] must not catch or translate `CancellationException` when invoked
 * from within a coroutine. Coroutine cancellation must propagate normally and
 * must not become a [PolicyCheckOutcome].
 *
 * ## Dependency injection
 *
 * Check implementations may receive configuration through constructors or
 * constructor-injected dependencies. DataLoom does not depend on a particular
 * dependency-injection framework, and does not access implementations
 * through a global singleton.
 *
 * ## Evaluation restrictions
 *
 * [evaluate] must not block, sleep, access network services or storage,
 * schedule work, mutate external state, automatically log sensitive context,
 * expose provider-specific exception types, read a live clock, or catch
 * coroutine cancellation.
 *
 * ## Scope
 *
 * This interface is the generic evaluation primitive only. It does not
 * itself implement retry reclassification, conflict selection, content
 * policy, plugin permission, residency, or administrative-override rules —
 * those remain each subsystem's own responsibility, built on top of this
 * contract in separate, later work.
 */
public interface PolicyCheck {

    /** Stable non-blank identifier used for evidence attribution and diagnostics. */
    public val id: PolicyCheckId

    /**
     * Evaluates [input] synchronously and deterministically.
     */
    public fun evaluate(input: PolicyEvaluationInput): PolicyCheckOutcome
}
