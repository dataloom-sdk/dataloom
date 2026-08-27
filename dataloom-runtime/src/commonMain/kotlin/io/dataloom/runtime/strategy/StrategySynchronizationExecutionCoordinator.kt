package io.dataloom.runtime.strategy

import io.dataloom.api.execution.StrategyProviderSet
import io.dataloom.api.operational.DurableOperationalEventOutbox
import io.dataloom.api.operational.OperationalEventOutboxScope
import io.dataloom.api.policy.PolicyCheckOutcome
import io.dataloom.api.policy.PolicyDecision
import io.dataloom.api.policy.PolicyDecisionScope
import io.dataloom.api.policy.PolicyEvaluationInput
import io.dataloom.api.provider.ProviderLifecycleCoordinatorState
import io.dataloom.api.provider.StrategyProviderBindings
import io.dataloom.api.strategy.BuiltInSynchronizationStrategy
import io.dataloom.api.strategy.DurableStrategyDecisionEventLog
import io.dataloom.api.strategy.DurableStrategyDecisionOutcomeHistory
import io.dataloom.api.strategy.StrategyDecisionEvent
import io.dataloom.api.strategy.StrategyDecisionId
import io.dataloom.api.strategy.StrategyDecisionOutcomeKind
import io.dataloom.api.strategy.StrategyDisposition
import io.dataloom.api.strategy.StrategyEvaluationResult
import io.dataloom.api.strategy.StrategyExecutionTrigger
import io.dataloom.api.strategy.StrategyOperation
import io.dataloom.api.strategy.StrategyOperationInput
import io.dataloom.api.strategy.StrategySynchronizationRequest
import io.dataloom.api.runtime.RuntimeDependencies
import io.dataloom.api.time.DataLoomClock
import io.dataloom.core.provider.ProviderLifecycleCoordinator
import io.dataloom.core.provider.StrategyProviderResolutionResult
import io.dataloom.core.provider.StrategyProviderResolver
import io.dataloom.runtime.execution.SynchronizationPipelineRegistry
import io.dataloom.runtime.execution.lifecycle.SynchronizationLifecycleEventEmitter
import io.dataloom.runtime.observation.operational.StrategyDecisionOperationalEventBridge
import io.dataloom.runtime.submission.QueuedSynchronizationWorkEncoder
import kotlin.coroutines.cancellation.CancellationException

/**
 * Admits a strategy request before resolving or invoking any provider.
 *
 * ## DL-039 strategy-admission policy evaluation (optional)
 *
 * When [admissionPolicy] is configured (see
 * [io.dataloom.runtime.facade.DataLoomStrategyAdmissionPolicySpec] /
 * `DataLoomBuilder.strategyAdmissionPolicyConfiguration`), every [execute]
 * call evaluates [admissionPolicy]'s [io.dataloom.api.policy.PolicySet]
 * against an [io.dataloom.api.policy.PolicyEvaluationInput] built from
 * [StrategySynchronizationRequest.request]'s
 * [io.dataloom.api.context.ExecutionContext] via
 * [io.dataloom.api.policy.PolicyEvaluator.evaluate], before any provider is
 * resolved -- the same "before resolving or invoking any provider" admission
 * boundary this class already enforces for every other rejection reason.
 * Only [io.dataloom.api.policy.PolicyCheckOutcome.Allow] admits the request;
 * every other outcome kind produces
 * [StrategySynchronizationExecutionResult.Rejected] with reason
 * [StrategyExecutionRejectionReason.POLICY_DENIED]. When [admissionPolicy]
 * also carries a [io.dataloom.api.policy.DurablePolicyDecisionLog], the
 * combined [io.dataloom.api.policy.PolicyDecision] is durably committed --
 * after it has already been used to decide admission, never blocking or
 * altering that decision, and with a recording failure swallowed exactly
 * like every other optional durable bridge in this class (only
 * [CancellationException] still propagates). When [admissionPolicy] is
 * `null`, [execute] performs no policy evaluation at all -- byte-for-byte
 * the same behavior as before this feature existed.
 *
 * ## Per-attempt outcome history (optional)
 *
 * When [strategyDecisionOutcomeHistory] is configured -- and only when
 * [strategyDecisionEventLog] is *also* configured, the same "no effect
 * unless the diagnostics log itself is configured" posture
 * [io.dataloom.runtime.facade.DataLoomStrategyDecisionOperationalEventOutboxSpec]
 * already documents for the operational-event bridge, since the
 * [StrategyDecisionEvent] this appends is the exact one [strategyDecisionEventLog]
 * already built, never a second, independently constructed one -- the same
 * event is also appended to [io.dataloom.api.strategy.DurableStrategyDecisionOutcomeHistory]
 * after [strategyDecisionEventLog] has already recorded it, never before.
 * Unlike [strategyDecisionEventLog]'s commit-once single slot, every attempt
 * is retained here, so a caller can see the full sequence of outcomes a
 * retried [StrategyDecisionId] actually produced, not just the first one. A
 * recording failure never changes or hides the real
 * [StrategySynchronizationExecutionResult] this class returns, matching
 * every other optional durable bridge here (only [CancellationException]
 * still propagates). When [strategyDecisionOutcomeHistory] is `null`,
 * [execute] never appends to it -- byte-for-byte the same behavior as before
 * this feature existed.
 */
internal class StrategySynchronizationExecutionCoordinator(
    private val lifecycleCoordinator: ProviderLifecycleCoordinator,
    private val evaluator: BuiltInSynchronizationStrategyEvaluator,
    private val providerResolver: StrategyProviderResolver,
    private val clock: DataLoomClock,
    private val runtimeDependencies: RuntimeDependencies,
    private val pipelineRegistry: SynchronizationPipelineRegistry,
    private val lifecycleEventEmitter: SynchronizationLifecycleEventEmitter?,
    private val durableQueueWorkEncoder: QueuedSynchronizationWorkEncoder? = null,
    private val strategyDecisionEventLog: DurableStrategyDecisionEventLog? = null,
    private val strategyDecisionOutcomeHistory: DurableStrategyDecisionOutcomeHistory? = null,
    private val operationalEventOutbox: DurableOperationalEventOutbox? = null,
    private val operationalEventOutboxScope: OperationalEventOutboxScope? = null,
    private val admissionPolicy: StrategyAdmissionPolicyConfiguration? = null,
) {
    private val durableQueueAdmitter = StrategyDurableQueueAdmitter(
        clock = clock,
        runtimeDependencies = runtimeDependencies,
        encoder = durableQueueWorkEncoder,
    )

    public suspend fun execute(
        request: StrategySynchronizationRequest,
        bindings: StrategyProviderBindings,
    ): StrategySynchronizationExecutionResult = execute(
        request = request,
        bindings = bindings,
        providerBoundary = StrategyProviderExecutionBoundary.Identity,
    )

    /**
     * Delegates to [executeCore] for the real admission/dispatch logic, then
     * -- when [strategyDecisionEventLog] is configured -- durably records a
     * payload-free [StrategyDecisionEvent] describing the terminal result,
     * and -- when [operationalEventOutbox] and [operationalEventOutboxScope]
     * are also both configured -- bridges that same event into an
     * [io.dataloom.api.operational.OperationalEventEnvelope] via
     * [StrategyDecisionOperationalEventBridge] and durably appends it. See
     * [io.dataloom.runtime.facade.DataLoomStrategyDecisionOperationalEventOutboxSpec]
     * for why this bridge is opt-in on top of [strategyDecisionEventLog]
     * rather than independently configurable: the event only ever exists
     * when [strategyDecisionEventLog] is configured, so there is nothing to
     * bridge otherwise.
     *
     * This is the single choke point every [execute] overload and every
     * `DEFER`-disposition path (via [handleDefer], itself only ever reached
     * from [executeCore]) already funnels through, so every terminal
     * [StrategySynchronizationExecutionResult] -- including early admission
     * rejections like `PROVIDERS_NOT_INITIALIZED` -- is covered by one
     * wrapper rather than duplicated at each of [executeCore]'s many return
     * points. Matches
     * [io.dataloom.runtime.conflict.DurableConflictDetectionCoordinator]'s
     * own posture: a durable-recording failure never changes or hides the
     * real result this method returns.
     */
    internal suspend fun execute(
        request: StrategySynchronizationRequest,
        bindings: StrategyProviderBindings,
        providerBoundary: StrategyProviderExecutionBoundary,
    ): StrategySynchronizationExecutionResult {
        val result = executeCore(request, bindings, providerBoundary)
        recordDecisionEvent(result)
        return result
    }

    private suspend fun recordDecisionEvent(result: StrategySynchronizationExecutionResult) {
        val log = strategyDecisionEventLog ?: return
        val (outcomeKind, outcomeDetail) = result.toDecisionOutcome()
        val plan = result.evaluation.plan
        val decisionId = result.evaluation.decisionId
        val event = StrategyDecisionEvent(
            planId = plan.id,
            requestedStrategy = plan.requestedStrategy,
            effectiveStrategy = plan.effectiveStrategy,
            effectiveProfileId = plan.effectiveProfileId,
            configurationVersion = plan.configurationVersion,
            direction = plan.direction,
            mode = plan.mode,
            disposition = plan.disposition,
            reasonCodes = result.evaluation.reasonCodes,
            outcomeKind = outcomeKind,
            outcomeDetail = outcomeDetail,
            committedAt = result.completedAt,
        )
        log.record(decisionId, event)
        recordOutcomeHistoryAttempt(decisionId, event)
        recordOperationalEvent(decisionId, event)
    }

    /**
     * When [strategyDecisionOutcomeHistory] is configured, appends [event] as
     * a new per-attempt entry for [decisionId], after [strategyDecisionEventLog]
     * has already recorded it (this method is only ever reached from
     * [recordDecisionEvent], which is itself only reached once [strategyDecisionEventLog]
     * is non-null -- see this class's own "Per-attempt outcome history
     * (optional)" documentation). [DurableStrategyDecisionOutcomeHistory.append]
     * reports every failure mode -- underlying store failure or exhausted
     * contention retries -- as a
     * [io.dataloom.api.strategy.DurableStrategyDecisionOutcomeAppendOutcome]
     * value rather than throwing, the same non-throwing contract
     * [strategyDecisionEventLog]'s own `record` call above already relies on
     * without a `try`/`catch`, so a durable-recording failure here never
     * changes or hides the real [StrategySynchronizationExecutionResult]
     * already computed. Only [kotlin.coroutines.cancellation.CancellationException]
     * propagates, exactly as it would from any other suspend call in this
     * method.
     */
    private suspend fun recordOutcomeHistoryAttempt(decisionId: StrategyDecisionId, event: StrategyDecisionEvent) {
        val history = strategyDecisionOutcomeHistory ?: return
        history.append(decisionId, event)
    }

    /**
     * When [operationalEventOutbox] and [operationalEventOutboxScope] are
     * both configured, bridges [event] into an
     * [io.dataloom.api.operational.OperationalEventEnvelope] via
     * [StrategyDecisionOperationalEventBridge] and durably appends it, after
     * [event] has already been durably recorded by [strategyDecisionEventLog]
     * (see [recordDecisionEvent]) -- never before, and never in a way that
     * changes the [StrategySynchronizationExecutionResult] already returned
     * to the caller.
     *
     * A durable-recording failure -- whether envelope construction throws, or
     * [io.dataloom.api.operational.DurableOperationalEventOutbox.append]
     * itself returns anything other than success -- is swallowed and never
     * surfaces as a thrown exception; only [CancellationException] still
     * propagates. Matches
     * [io.dataloom.runtime.execution.lifecycle.DispatchingSynchronizationLifecycleEventEmitter.recordOperationalEvent]'s
     * own posture exactly. When either collaborator is `null`, this method
     * performs no work -- byte-for-byte the same behavior as before this
     * bridge existed.
     */
    private suspend fun recordOperationalEvent(decisionId: StrategyDecisionId, event: StrategyDecisionEvent) {
        val outbox = operationalEventOutbox ?: return
        val scope = operationalEventOutboxScope ?: return
        try {
            val envelope = StrategyDecisionOperationalEventBridge.toEnvelope(decisionId, event)
            outbox.append(scope, envelope)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (ordinary: Exception) {
            // Intentionally swallowed -- see method doc above.
        }
    }

    /**
     * Maps a terminal [StrategySynchronizationExecutionResult] to the
     * bounded, payload-free [StrategyDecisionOutcomeKind] vocabulary a
     * [StrategyDecisionEvent] persists, plus an optional short, stable,
     * non-sensitive detail code -- never an exception message or payload
     * content, matching [StrategyDecisionEvent]'s own documented scope.
     */
    private fun StrategySynchronizationExecutionResult.toDecisionOutcome(): Pair<StrategyDecisionOutcomeKind, String?> =
        when (this) {
            is StrategySynchronizationExecutionResult.Executed ->
                StrategyDecisionOutcomeKind.EXECUTED to null
            is StrategySynchronizationExecutionResult.ServedFromCache ->
                StrategyDecisionOutcomeKind.SERVED_FROM_CACHE to cacheState.name
            is StrategySynchronizationExecutionResult.Failed ->
                StrategyDecisionOutcomeKind.FAILED to error.code.value
            is StrategySynchronizationExecutionResult.FallbackActivated ->
                StrategyDecisionOutcomeKind.FALLBACK_ACTIVATED to remoteOutcome.name
            is StrategySynchronizationExecutionResult.FallbackUnavailable ->
                StrategyDecisionOutcomeKind.FALLBACK_UNAVAILABLE to remoteOutcome.name
            is StrategySynchronizationExecutionResult.Cancelled ->
                StrategyDecisionOutcomeKind.CANCELLED to null
            is StrategySynchronizationExecutionResult.Deferred ->
                StrategyDecisionOutcomeKind.DEFERRED to null
            is StrategySynchronizationExecutionResult.DurablyEnqueued ->
                StrategyDecisionOutcomeKind.DURABLY_ENQUEUED to null
            is StrategySynchronizationExecutionResult.AcceptedLocally ->
                StrategyDecisionOutcomeKind.ACCEPTED_LOCALLY to null
            is StrategySynchronizationExecutionResult.Rejected ->
                StrategyDecisionOutcomeKind.REJECTED to reason.name
        }

    private suspend fun executeCore(
        request: StrategySynchronizationRequest,
        bindings: StrategyProviderBindings,
        providerBoundary: StrategyProviderExecutionBoundary,
    ): StrategySynchronizationExecutionResult {
        val evaluation = evaluator.evaluate(request.evaluationRequest())

        if (!evaluateAdmissionPolicy(request)) {
            return rejected(
                evaluation = evaluation,
                reason = StrategyExecutionRejectionReason.POLICY_DENIED,
            )
        }

        if (lifecycleCoordinator.state != ProviderLifecycleCoordinatorState.INITIALIZED) {
            return rejected(
                evaluation = evaluation,
                reason = StrategyExecutionRejectionReason.PROVIDERS_NOT_INITIALIZED,
            )
        }

        when (evaluation.plan.disposition) {
            StrategyDisposition.REJECT -> return rejected(
                evaluation = evaluation,
                reason = StrategyExecutionRejectionReason.STRATEGY_REJECTED,
            )
            StrategyDisposition.DEFER -> return handleDefer(
                request = request,
                bindings = bindings,
                evaluation = evaluation,
                providerBoundary = providerBoundary,
            )
            StrategyDisposition.EXECUTE,
            StrategyDisposition.SERVE_AND_REFRESH,
            -> Unit
        }

        if (request.trigger == StrategyExecutionTrigger.DURABLE_QUEUE) {
            return rejected(
                evaluation = evaluation,
                reason = StrategyExecutionRejectionReason.INCOMPATIBLE_TRIGGER,
            )
        }
        when (evaluation.plan.effectiveStrategy) {
            BuiltInSynchronizationStrategy.NETWORK_ONLY -> {
                if (request.input !is StrategyOperationInput.DirectTransport) {
                    return rejected(
                        evaluation = evaluation,
                        reason = StrategyExecutionRejectionReason.INCOMPATIBLE_INPUT,
                    )
                }
            }
            BuiltInSynchronizationStrategy.REMOTE_FIRST,
            BuiltInSynchronizationStrategy.CACHE_FIRST,
            BuiltInSynchronizationStrategy.OFFLINE_FIRST,
            BuiltInSynchronizationStrategy.HYBRID,
            -> {
                if (request.input !is StrategyOperationInput.ProviderBacked) {
                    return rejected(
                        evaluation = evaluation,
                        reason = StrategyExecutionRejectionReason.INCOMPATIBLE_INPUT,
                    )
                }
            }
            else -> return rejected(
                evaluation = evaluation,
                reason = StrategyExecutionRejectionReason.UNSUPPORTED_PLAN,
            )
        }

        val executionProviders = when (
            val resolved = resolveExecutionProviders(
                bindings = bindings,
                requiredCapabilities = admissionAwareCapabilities(evaluation),
                evaluation = evaluation,
                providerBoundary = providerBoundary,
            )
        ) {
            is ResolvedProviders.Prepared -> resolved.providers
            is ResolvedProviders.Rejected -> return resolved.result
        }

        if (
            evaluation.plan.effectiveStrategy ==
            BuiltInSynchronizationStrategy.NETWORK_ONLY
        ) {
            return NetworkOnlyStrategyExecutor(clock).execute(
                request = request,
                evaluation = evaluation,
                providers = executionProviders,
            )
        }
        if (
            evaluation.plan.effectiveStrategy ==
            BuiltInSynchronizationStrategy.REMOTE_FIRST
        ) {
            return RemoteFirstStrategyExecutor(
                clock = clock,
                runtimeDependencies = runtimeDependencies,
                pipelineRegistry = pipelineRegistry,
                lifecycleEventEmitter = lifecycleEventEmitter,
            ).execute(
                request = request,
                evaluation = evaluation,
                providers = executionProviders,
            )
        }
        if (
            evaluation.plan.effectiveStrategy ==
            BuiltInSynchronizationStrategy.CACHE_FIRST
        ) {
            return CacheFirstStrategyExecutor(
                clock = clock,
                runtimeDependencies = runtimeDependencies,
                pipelineRegistry = pipelineRegistry,
                lifecycleEventEmitter = lifecycleEventEmitter,
                durableQueueAdmitter = durableQueueAdmitter,
            ).execute(
                request = request,
                evaluation = evaluation,
                providers = executionProviders,
            )
        }
        if (
            evaluation.plan.effectiveStrategy ==
            BuiltInSynchronizationStrategy.OFFLINE_FIRST
        ) {
            return OfflineFirstStrategyExecutor(
                clock = clock,
                runtimeDependencies = runtimeDependencies,
                pipelineRegistry = pipelineRegistry,
                lifecycleEventEmitter = lifecycleEventEmitter,
                durableQueueAdmitter = durableQueueAdmitter,
            ).execute(
                request = request,
                evaluation = evaluation,
                providers = executionProviders,
            )
        }
        if (
            evaluation.plan.effectiveStrategy ==
            BuiltInSynchronizationStrategy.HYBRID
        ) {
            return HybridStrategyExecutor(
                clock = clock,
                runtimeDependencies = runtimeDependencies,
                pipelineRegistry = pipelineRegistry,
                lifecycleEventEmitter = lifecycleEventEmitter,
                durableQueueAdmitter = durableQueueAdmitter,
            ).execute(
                request = request,
                evaluation = evaluation,
                providers = executionProviders,
            )
        }
        // NOTE: with all five concrete built-in strategies now dispatched
        // above, this branch is currently unreachable via the public
        // evaluator — `StrategyExecutionPlan.effectiveStrategy` can never be
        // `ADAPTIVE` itself (see its init-block `require`), and adaptive
        // resolution always produces one of the five strategies handled
        // above or a REJECT-disposition plan that never reaches this switch.
        // Kept as a defensive fallback for a hypothetical future concrete
        // strategy added without updating this dispatch.
        return rejected(
            evaluation = evaluation,
            reason = StrategyExecutionRejectionReason.UNSUPPORTED_PLAN,
        )
    }

    /**
     * Handles a `DEFER`-disposition plan.
     *
     * When [durableQueueWorkEncoder] is not configured, or the plan does not
     * require durable admission (`ENQUEUE_DURABLE_WORK` absent — e.g. a
     * network-only/remote-first connectivity defer that carries no durable
     * continuation), this returns the same bare
     * [StrategySynchronizationExecutionResult.Deferred] every caller has
     * always received — a pure in-memory signal, no provider ever resolved
     * or called.
     *
     * When an encoder is configured and the plan does require durable
     * admission, providers are resolved (scoped to the plan's own
     * `requiredCapabilities`, exactly like the EXECUTE/SERVE_AND_REFRESH
     * path below) and [StrategyDurableQueueAdmitter] is invoked for real.
     * Once an application opts into durable admission this way, a deferral
     * that cannot actually be admitted is surfaced as a real
     * [StrategySynchronizationExecutionResult.Rejected]/[StrategySynchronizationExecutionResult.Failed]
     * rather than a silent bare `Deferred` — the whole point of opting in is
     * knowing for certain whether intent survived, not a soft "maybe."
     */
    private suspend fun handleDefer(
        request: StrategySynchronizationRequest,
        bindings: StrategyProviderBindings,
        evaluation: StrategyEvaluationResult,
        providerBoundary: StrategyProviderExecutionBoundary,
    ): StrategySynchronizationExecutionResult {
        if (
            durableQueueWorkEncoder == null ||
            StrategyOperation.ENQUEUE_DURABLE_WORK !in evaluation.plan.operations
        ) {
            return StrategySynchronizationExecutionResult.Deferred(
                evaluation = evaluation,
                completedAt = clock.now(),
            )
        }

        val executionProviders = when (
            val resolved = resolveExecutionProviders(
                bindings = bindings,
                requiredCapabilities = admissionAwareCapabilities(evaluation),
                evaluation = evaluation,
                providerBoundary = providerBoundary,
            )
        ) {
            is ResolvedProviders.Prepared -> resolved.providers
            is ResolvedProviders.Rejected -> return resolved.result
        }

        return when (
            val outcome = durableQueueAdmitter.admit(request, evaluation, executionProviders)
        ) {
            is StrategyDurableQueueAdmissionOutcome.NotConfigured ->
                StrategySynchronizationExecutionResult.Deferred(
                    evaluation = evaluation,
                    completedAt = clock.now(),
                )
            is StrategyDurableQueueAdmissionOutcome.Admitted ->
                StrategySynchronizationExecutionResult.Deferred(
                    evaluation = evaluation,
                    completedAt = clock.now(),
                    queueEntryId = outcome.queueEntryId,
                )
            is StrategyDurableQueueAdmissionOutcome.Rejected -> rejected(evaluation, outcome.reason)
            is StrategyDurableQueueAdmissionOutcome.Failed -> StrategySynchronizationExecutionResult.Failed(
                evaluation = evaluation,
                completedAt = clock.now(),
                error = outcome.error,
                transportAttempted = false,
            )
        }
    }

    /**
     * The capabilities to resolve providers for, widened to also cover the
     * plan's durable continuation when durable admission is actually
     * configured.
     *
     * A plan's own `requiredCapabilities` reflect only what runs
     * *synchronously* right now — cache-first's durable-refresh branch, for
     * example, never requires `TRANSPORT` at the top level, since the
     * refresh itself is meant to run later via the durable continuation.
     * But [StrategyDurableQueueAdmitter] needs to persist a real
     * [io.dataloom.api.provider.SynchronizationProviderBindings] on the
     * queue entry for that later replay, which requires a non-null
     * transport (and storage) provider ID *now*, at admission time — not
     * just whatever the synchronous portion happens to need. Widening only
     * when [durableQueueWorkEncoder] is configured keeps an unconfigured
     * caller's resolution requirements byte-for-byte identical to before
     * durable admission wiring existed.
     */
    private fun admissionAwareCapabilities(
        evaluation: StrategyEvaluationResult,
    ): Set<io.dataloom.api.strategy.StrategyProviderCapability> {
        val continuationCapabilities = if (durableQueueWorkEncoder != null) {
            evaluation.plan.durableContinuation?.requiredCapabilities ?: emptySet()
        } else {
            emptySet()
        }
        return evaluation.plan.requiredCapabilities + continuationCapabilities
    }

    private suspend fun resolveExecutionProviders(
        bindings: StrategyProviderBindings,
        requiredCapabilities: Set<io.dataloom.api.strategy.StrategyProviderCapability>,
        evaluation: StrategyEvaluationResult,
        providerBoundary: StrategyProviderExecutionBoundary,
    ): ResolvedProviders {
        val providers = when (
            val resolution = providerResolver.resolve(
                bindings = bindings,
                requiredCapabilities = requiredCapabilities,
            )
        ) {
            is StrategyProviderResolutionResult.Success -> resolution.providers
            is StrategyProviderResolutionResult.Failure -> {
                return ResolvedProviders.Rejected(
                    StrategySynchronizationExecutionResult.Rejected(
                        evaluation = evaluation,
                        completedAt = clock.now(),
                        reason = StrategyExecutionRejectionReason.PROVIDER_RESOLUTION_FAILED,
                        missingCapabilities = resolution.missingCapabilities,
                        bindingFailures = resolution.bindingFailures,
                    ),
                )
            }
        }

        return when (val preparation = providerBoundary.prepare(evaluation, providers)) {
            is StrategyProviderExecutionPreparation.Prepared ->
                ResolvedProviders.Prepared(preparation.providers)
            is StrategyProviderExecutionPreparation.Rejected ->
                ResolvedProviders.Rejected(rejected(evaluation, preparation.reason))
        }
    }

    private sealed interface ResolvedProviders {
        data class Prepared(val providers: StrategyProviderSet) : ResolvedProviders
        data class Rejected(val result: StrategySynchronizationExecutionResult) : ResolvedProviders
    }

    /**
     * Returns `true` when [request] may proceed: either no [admissionPolicy]
     * is configured, or the combined [PolicyDecision] it produces has an
     * [PolicyCheckOutcome.Allow] outcome. See this class's own KDoc for the
     * full contract, including the durable-recording bridge.
     */
    private suspend fun evaluateAdmissionPolicy(request: StrategySynchronizationRequest): Boolean {
        val policy = admissionPolicy ?: return true
        val input = PolicyEvaluationInput(
            executionContext = request.request.context,
            configurationSnapshot = policy.configurationSnapshot,
        )
        val decision = policy.evaluator.evaluate(policy.policySet, input, policy.budget)
        recordAdmissionPolicyDecision(policy, request, decision)
        return decision.outcome is PolicyCheckOutcome.Allow
    }

    /**
     * When [policy] carries a [io.dataloom.api.policy.DurablePolicyDecisionLog],
     * durably commits [decision] under a [PolicyDecisionScope] keyed by
     * [decision]'s own [PolicyDecision.policySetId] and [request]'s
     * [io.dataloom.api.context.ExecutionContext.executionId] -- after
     * [decision] has already determined admission (see
     * [evaluateAdmissionPolicy]), never blocking or altering it. A recording
     * failure is swallowed and never surfaces as a thrown exception; only
     * [CancellationException] still propagates. Matches this class's existing
     * `recordOperationalEvent` posture exactly.
     */
    private suspend fun recordAdmissionPolicyDecision(
        policy: StrategyAdmissionPolicyConfiguration,
        request: StrategySynchronizationRequest,
        decision: PolicyDecision,
    ) {
        val log = policy.decisionLog ?: return
        try {
            log.commit(
                scope = PolicyDecisionScope(
                    policySetId = decision.policySetId,
                    executionId = request.request.context.executionId,
                ),
                decision = decision,
                committedAt = clock.now(),
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (ordinary: Exception) {
            // Intentionally swallowed -- see method doc above.
        }
    }

    private fun rejected(
        evaluation: io.dataloom.api.strategy.StrategyEvaluationResult,
        reason: StrategyExecutionRejectionReason,
    ): StrategySynchronizationExecutionResult =
        StrategySynchronizationExecutionResult.Rejected(
            evaluation = evaluation,
            completedAt = clock.now(),
            reason = reason,
        )
}
