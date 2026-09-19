package io.dataloom.plugin

import io.dataloom.api.plugin.PluginId
import io.dataloom.api.plugin.PluginLifecycleState
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Outcome of one [PluginExecutionBoundsEnforcer.execute] call.
 */
public sealed interface PluginExecutionBoundsResult<out T> {

    /**
     * [operation] completed within [io.dataloom.api.plugin.PluginExecutionBounds.maximumExecutionMillis],
     * without exceeding [io.dataloom.api.plugin.PluginExecutionBounds.maximumConcurrentInvocations].
     */
    public data class Completed<T>(public val value: T) : PluginExecutionBoundsResult<T>

    /**
     * `operation` was cancelled because it did not complete within
     * [maximumExecutionMillis].
     */
    public data class TimedOut(
        public val pluginId: PluginId,
        public val maximumExecutionMillis: Long,
    ) : PluginExecutionBoundsResult<Nothing>

    /**
     * `operation` was never invoked because [pluginId] already had
     * [maximumConcurrentInvocations] invocations in flight.
     */
    public data class ConcurrencyLimitExceeded(
        public val pluginId: PluginId,
        public val maximumConcurrentInvocations: Int,
    ) : PluginExecutionBoundsResult<Nothing>

    /**
     * `operation` was never invoked because [pluginId] was not in
     * [PluginLifecycleState.ACTIVE] when the call started; [state] is the
     * state observed. Every state other than `ACTIVE` (including `DEGRADED`)
     * refuses new invocations.
     */
    public data class NotActive(
        public val pluginId: PluginId,
        public val state: PluginLifecycleState,
    ) : PluginExecutionBoundsResult<Nothing>
}

/**
 * Enforces each plugin registered in a [PluginRegistry]'s declared
 * [io.dataloom.api.plugin.PluginExecutionBounds] (execution-time timeout and
 * concurrency ceiling) around an arbitrary invocation.
 *
 * ## Purpose and precedent
 *
 * `#98`'s own `docs/api/plugin-registry.md`
 * names this exact capability — "actual timeout cancellation, concurrency
 * limiting, and failure isolation/bulkheading over
 * [io.dataloom.api.plugin.PluginExecutionBounds]' declared numbers" — as not
 * blocked on anything external, with a directly analogous precedent already
 * shipped in this codebase:
 * [io.dataloom.runtime.retry.TimeoutEnforcingSchedulerProvider], which wraps
 * every [io.dataloom.api.scheduling.SchedulerProvider] invocation in
 * coroutine-cancellation timeout enforcement via
 * `io.dataloom.runtime.retry.CoroutineRetryTimeoutExecutor`
 * (`kotlinx.coroutines.withTimeoutOrNull`) and converts an expired timeout
 * into a canonical, non-throwing failure result rather than letting a
 * platform exception escape. [PluginExecutionBoundsEnforcer] applies that
 * same shape to plugins, adding concurrency limiting alongside it since
 * [io.dataloom.api.plugin.PluginExecutionBounds] declares both numbers
 * together.
 *
 * ## Why a generic `operation` parameter, not a `DataLoomPlugin` callback
 *
 * [io.dataloom.api.plugin.DataLoomPlugin] deliberately declares no lifecycle
 * or hook-invocation callback methods yet — see its own KDoc: those
 * signatures depend on the execution context this engine designs and are
 * not frozen. Unlike `SchedulerProvider`, there is today no fixed "invoke
 * this plugin" method to decorate. [execute] is therefore written
 * generically over any `suspend () -> T` block representing one invocation
 * of the plugin registered under [PluginId], the same generality
 * `TimeoutEnforcingSchedulerProvider` would need if `SchedulerProvider` had
 * no fixed operation set either. When a real invocation call site exists
 * (hook-point dispatch, `#98`'s own still-open item, blocked today on
 * consuming subsystems not having adopted a plugin extension point), it is
 * expected to route its invocation through this type rather than
 * reimplementing bounds enforcement.
 *
 * ## Timeout enforcement
 *
 * [io.dataloom.api.plugin.PluginExecutionBounds.maximumExecutionMillis] is
 * enforced with coroutine structured cancellation
 * (`kotlinx.coroutines.withTimeoutOrNull`), the exact mechanism
 * `io.dataloom.runtime.retry.CoroutineRetryTimeoutExecutor` uses for
 * providers. An operation that blocks without a suspension or other
 * cancellation checkpoint cannot be preempted by this timeout — the same
 * documented limitation that executor already carries.
 *
 * ## Concurrency limiting
 *
 * [io.dataloom.api.plugin.PluginExecutionBounds.maximumConcurrentInvocations]
 * is enforced with one [kotlinx.coroutines.sync.Semaphore] per registered
 * plugin, built once, immutably, at construction from [registry]'s
 * registered plugins. A call that would exceed the ceiling is rejected
 * immediately (`Semaphore.tryAcquire()` returning `false`) rather than
 * suspended to wait for a free slot: a fail-fast bulkhead, not a queue, so
 * one busy or slow plugin cannot silently stall an unrelated caller waiting
 * on a slot that may never free up in time.
 *
 * ## Lifecycle gating (decision D13)
 *
 * This enforcer is bound to a [PluginLifecycleStateTracker] and consults it
 * once, at the start of each [execute] call:
 *
 * - **New invocations are refused unless the plugin is
 *   [PluginLifecycleState.ACTIVE].** In any other state (including
 *   `DEGRADED`, `DISABLED`, and every pre-active state) [execute] returns
 *   [PluginExecutionBoundsResult.NotActive] without acquiring a concurrency
 *   slot or invoking the operation. The state check happens before the
 *   concurrency check, so a non-active plugin reports `NotActive` even when
 *   it is also at its ceiling.
 * - **An invocation already in flight is not disturbed by a later state
 *   change.** If its plugin leaves `ACTIVE` while it runs, it is neither
 *   cancelled nor shortened: it completes, or is cancelled by its own
 *   declared timeout ([PluginExecutionBoundsResult.TimedOut]), or by caller
 *   cancellation, exactly as it would have without the transition. Its
 *   concurrency slot is released when it finishes, so a disabled plugin
 *   drains rather than being torn down. The state is read only at invocation
 *   start, so an invocation that passed the check just before a transition
 *   is by definition already in flight.
 *
 * [PluginLifecycleStateTracker.stateOf] is safe to call concurrently with a
 * transition, so this check may run on any invocation thread.
 *
 * ## What this does not do
 *
 * - **Does not cancel in-flight work on a state change.** See "Lifecycle
 *   gating" above: draining is the specified behavior, not an omission.
 *   Actively cancelling in-flight invocations on `DISABLED` would be a new,
 *   separate policy.
 * - **Does not perform failure isolation/bulkheading beyond concurrency
 *   limiting.** A plugin operation throwing an ordinary exception
 *   propagates normally, uncaught — exactly as
 *   `TimeoutEnforcingSchedulerProvider` leaves "unexpected programming
 *   exceptions" to propagate rather than converting them into a bounded
 *   result.
 * - **Does not audit timeout or concurrency-rejection events.** Audit
 *   records remain an open `#98` item.
 *
 * ## Thread-safety
 *
 * Unlike [PluginLifecycleStateTracker] (which requires callers to serialize
 * `transition` calls), [execute] is safe to call concurrently, for the same
 * or different plugin IDs: [kotlinx.coroutines.sync.Semaphore] is itself
 * safe under concurrent `tryAcquire`/`release`, and the per-plugin semaphore
 * map is built once, immutably, at construction — concurrency limiting is
 * this type's whole purpose, so it must tolerate the concurrent calls it
 * exists to bound.
 *
 * @param lifecycle the tracker whose [PluginLifecycleStateTracker.registry]'s
 *   registered plugins' declared [io.dataloom.api.plugin.PluginExecutionBounds]
 *   this enforcer enforces, and whose tracked state gates new invocations.
 */
public class PluginExecutionBoundsEnforcer(private val lifecycle: PluginLifecycleStateTracker) {

    private val registry: PluginRegistry = lifecycle.registry

    private val semaphores: Map<PluginId, Semaphore> = registry.plugins.associate { plugin ->
        plugin.manifest.id to Semaphore(plugin.executionBounds.maximumConcurrentInvocations)
    }

    /**
     * Runs [operation] as one bounded invocation of the plugin registered
     * under [id].
     *
     * If [id] is not [PluginLifecycleState.ACTIVE], [operation] is never
     * invoked and [PluginExecutionBoundsResult.NotActive] is returned.
     *
     * If [id] already has [io.dataloom.api.plugin.PluginExecutionBounds.maximumConcurrentInvocations]
     * invocations in flight, [operation] is never invoked and
     * [PluginExecutionBoundsResult.ConcurrencyLimitExceeded] is returned
     * immediately.
     *
     * Otherwise [operation] runs under a timeout of
     * [io.dataloom.api.plugin.PluginExecutionBounds.maximumExecutionMillis].
     * If it does not complete in time, it is cancelled and
     * [PluginExecutionBoundsResult.TimedOut] is returned. Otherwise its
     * result is returned as [PluginExecutionBoundsResult.Completed],
     * preserving a `null` value exactly (mirroring
     * `io.dataloom.runtime.retry.CoroutineRetryTimeoutExecutor`'s own
     * null-safe handling of `withTimeoutOrNull`).
     *
     * The concurrency slot acquired for this call is always released before
     * returning, including when [operation] throws or is cancelled.
     *
     * This method never throws for a timeout or a concurrency-limit
     * rejection. A `CancellationException` from caller cancellation, or from
     * [operation] itself, propagates normally and is not reclassified as
     * [PluginExecutionBoundsResult.TimedOut].
     *
     * @throws IllegalArgumentException if [id] is not registered in
     *   [registry].
     */
    public suspend fun <T> execute(
        id: PluginId,
        operation: suspend () -> T,
    ): PluginExecutionBoundsResult<T> {
        val plugin = requireNotNull(registry.findById(id)) {
            "PluginExecutionBoundsEnforcer: '$id' is not registered in this enforcer's registry."
        }
        val bounds = plugin.executionBounds
        val semaphore = semaphores.getValue(id)

        val state = lifecycle.stateOf(id)
        if (state != PluginLifecycleState.ACTIVE) {
            return PluginExecutionBoundsResult.NotActive(pluginId = id, state = state)
        }

        if (!semaphore.tryAcquire()) {
            return PluginExecutionBoundsResult.ConcurrencyLimitExceeded(
                pluginId = id,
                maximumConcurrentInvocations = bounds.maximumConcurrentInvocations,
            )
        }

        try {
            val completed = withTimeoutOrNull(bounds.maximumExecutionMillis) {
                CompletedInvocation(operation())
            }
            return if (completed == null) {
                PluginExecutionBoundsResult.TimedOut(
                    pluginId = id,
                    maximumExecutionMillis = bounds.maximumExecutionMillis,
                )
            } else {
                PluginExecutionBoundsResult.Completed(completed.value)
            }
        } finally {
            semaphore.release()
        }
    }
}

private class CompletedInvocation<out T>(val value: T)
