package io.dataloom.runtime.strategy

import io.dataloom.api.error.DataLoomError
import io.dataloom.api.execution.StrategyProviderSet
import io.dataloom.api.model.SynchronizationDirection
import io.dataloom.api.model.SynchronizationRequest
import io.dataloom.api.provider.ProviderLifecycleCoordinatorState
import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.api.provider.StrategyProviderBindings
import io.dataloom.api.runtime.RuntimeDependencies
import io.dataloom.api.strategy.PersistedStrategyDecision
import io.dataloom.api.strategy.StrategyCacheState
import io.dataloom.api.strategy.StrategyDisposition
import io.dataloom.api.strategy.StrategyDurableContinuationPlan
import io.dataloom.api.strategy.StrategyEvaluationResult
import io.dataloom.api.strategy.StrategyExecutionPlan
import io.dataloom.api.strategy.StrategyLocalFallbackProvider
import io.dataloom.api.strategy.StrategyLocalFallbackRequest
import io.dataloom.api.strategy.StrategyLocalFallbackResult
import io.dataloom.api.strategy.StrategyOperation
import io.dataloom.api.strategy.StrategyProviderCapability
import io.dataloom.api.strategy.StrategyReconciliationProvider
import io.dataloom.api.strategy.StrategyReconciliationRequest
import io.dataloom.api.strategy.StrategyRemoteOutcome
import io.dataloom.api.strategy.StrategySynchronizationRequest
import io.dataloom.api.strategy.StrategyTransportOutput
import io.dataloom.api.strategy.correspondsTo
import io.dataloom.api.synchronization.SynchronizationResult
import io.dataloom.api.time.DataLoomClock
import io.dataloom.api.transport.PullChangesRequest
import io.dataloom.core.provider.ProviderLifecycleCoordinator
import io.dataloom.core.provider.ResolvedSynchronizationProviders
import io.dataloom.core.provider.StrategyProviderResolutionResult
import io.dataloom.core.provider.StrategyProviderResolver
import io.dataloom.runtime.execution.SynchronizationExecutionContext
import io.dataloom.runtime.execution.SynchronizationPipeline
import io.dataloom.runtime.execution.SynchronizationPipelineRegistry
import io.dataloom.runtime.execution.lifecycle.SynchronizationLifecycleEventEmitter

/**
 * Executes an already accepted immutable strategy plan without policy
 * re-evaluation.
 *
 * This coordinator accepts no strategy profile and no current runtime evidence.
 * Provider requirements, fallback, cache-state evidence, and reconciliation are
 * read only from the persisted [StrategyExecutionPlan.durableContinuation].
 */
public class AcceptedStrategyPlanExecutionCoordinator(
    private val lifecycleCoordinator: ProviderLifecycleCoordinator,
    private val providerResolver: StrategyProviderResolver,
    private val clock: DataLoomClock,
    runtimeDependencies: RuntimeDependencies,
    pipelineRegistry: SynchronizationPipelineRegistry,
    lifecycleEventEmitter: SynchronizationLifecycleEventEmitter? = null,
) {
    private val executor = AcceptedStrategyContinuationExecutor(
        clock = clock,
        runtimeDependencies = runtimeDependencies,
        pipelineRegistry = pipelineRegistry,
        lifecycleEventEmitter = lifecycleEventEmitter,
    )

    /**
     * Replays [acceptedPlan] through its exact durable continuation.
     *
     * No evaluator, profile lookup, connectivity observation, provider-health
     * observation, configuration lookup, identifier generation, or queue
     * mutation occurs before provider resolution.
     */
    public suspend fun execute(
        request: SynchronizationRequest,
        decision: PersistedStrategyDecision,
        acceptedPlan: StrategyExecutionPlan,
        bindings: StrategyProviderBindings,
        providerBoundary: StrategyProviderExecutionBoundary =
            IdentityStrategyProviderExecutionBoundary,
    ): StrategySynchronizationExecutionResult {
        val evaluation = replayEvaluation(decision, acceptedPlan)

        if (lifecycleCoordinator.state != ProviderLifecycleCoordinatorState.INITIALIZED) {
            return rejected(
                evaluation,
                StrategyExecutionRejectionReason.PROVIDERS_NOT_INITIALIZED,
            )
        }
        if (
            !decision.correspondsTo(acceptedPlan) ||
            acceptedPlan.direction != request.direction ||
            acceptedPlan.mode != request.mode
        ) {
            return rejected(
                evaluation,
                StrategyExecutionRejectionReason.ACCEPTED_PLAN_MISMATCH,
            )
        }

        val continuation = acceptedPlan.durableContinuation
            ?: return rejected(
                evaluation,
                StrategyExecutionRejectionReason.ACCEPTED_PLAN_CONTINUATION_MISSING,
            )

        val unsupportedCapabilities = continuation.requiredCapabilities -
            SUPPORTED_REPLAY_CAPABILITIES
        if (unsupportedCapabilities.isNotEmpty()) {
            return StrategySynchronizationExecutionResult.Rejected(
                evaluation = evaluation,
                completedAt = clock.now(),
                reason = StrategyExecutionRejectionReason.UNSUPPORTED_PLAN,
                missingCapabilities = unsupportedCapabilities,
            )
        }

        val providerCapabilities = continuation.requiredCapabilities -
            StrategyProviderCapability.CONFLICT_STATE
        val resolved = when (
            val result = providerResolver.resolve(bindings, providerCapabilities)
        ) {
            is StrategyProviderResolutionResult.Success -> result.providers
            is StrategyProviderResolutionResult.Failure ->
                return StrategySynchronizationExecutionResult.Rejected(
                    evaluation = evaluation,
                    completedAt = clock.now(),
                    reason = StrategyExecutionRejectionReason.PROVIDER_RESOLUTION_FAILED,
                    missingCapabilities = result.missingCapabilities,
                    bindingFailures = result.bindingFailures,
                )
        }

        val executableProviders = when (
            val preparation = providerBoundary.prepare(evaluation, resolved)
        ) {
            is StrategyProviderExecutionPreparation.Prepared -> preparation.providers
            is StrategyProviderExecutionPreparation.Rejected ->
                return rejected(evaluation, preparation.reason)
        }

        return executor.execute(
            request = request,
            evaluation = evaluation,
            providers = executableProviders,
            continuation = continuation,
        )
    }

    private fun replayEvaluation(
        decision: PersistedStrategyDecision,
        acceptedPlan: StrategyExecutionPlan,
    ): StrategyEvaluationResult {
        val continuation = acceptedPlan.durableContinuation
        val replayPlan = if (continuation == null) {
            acceptedPlan
        } else {
            StrategyExecutionPlan(
                id = acceptedPlan.id,
                requestedStrategy = acceptedPlan.requestedStrategy,
                effectiveProfileId = acceptedPlan.effectiveProfileId,
                effectiveStrategy = acceptedPlan.effectiveStrategy,
                configurationVersion = acceptedPlan.configurationVersion,
                direction = acceptedPlan.direction,
                mode = acceptedPlan.mode,
                disposition = StrategyDisposition.EXECUTE,
                operations = continuation.operations,
                requiredCapabilities = continuation.requiredCapabilities,
                dataOrigin = continuation.dataOrigin,
                consistency = continuation.consistency,
                fallbackPlan = continuation.fallbackPlan,
            )
        }
        return StrategyEvaluationResult(
            decisionId = decision.decisionId,
            plan = replayPlan,
            reasonCodes = listOf(REPLAY_REASON_CODE),
        )
    }

    private fun rejected(
        evaluation: StrategyEvaluationResult,
        reason: StrategyExecutionRejectionReason,
    ): StrategySynchronizationExecutionResult =
        StrategySynchronizationExecutionResult.Rejected(
            evaluation = evaluation,
            completedAt = clock.now(),
            reason = reason,
        )

    private companion object {
        const val REPLAY_REASON_CODE: String = "strategy.accepted-plan-replay"
        val SUPPORTED_REPLAY_CAPABILITIES: Set<StrategyProviderCapability> = setOf(
            StrategyProviderCapability.STORAGE,
            StrategyProviderCapability.TRANSPORT,
            StrategyProviderCapability.CONFLICT_STATE,
        )
    }
}

/** Executes the provider operations encoded by one durable continuation. */
private class AcceptedStrategyContinuationExecutor(
    private val clock: DataLoomClock,
    private val runtimeDependencies: RuntimeDependencies,
    private val pipelineRegistry: SynchronizationPipelineRegistry,
    private val lifecycleEventEmitter: SynchronizationLifecycleEventEmitter?,
) {
    suspend fun execute(
        request: SynchronizationRequest,
        evaluation: StrategyEvaluationResult,
        providers: StrategyProviderSet,
        continuation: StrategyDurableContinuationPlan,
    ): StrategySynchronizationExecutionResult {
        val fallbackProvider = if (continuation.fallbackPlan != null) {
            providers.storageProvider as? StrategyLocalFallbackProvider
                ?: return rejected(
                    evaluation,
                    StrategyExecutionRejectionReason.LOCAL_FALLBACK_PROVIDER_NOT_CONFIGURED,
                )
        } else {
            null
        }

        if (StrategyOperation.SERVE_LOCAL in continuation.operations) {
            return executeFallback(
                request = request,
                evaluation = evaluation,
                providers = providers,
                continuation = continuation,
                provider = requireNotNull(fallbackProvider),
                remoteOutcome = StrategyRemoteOutcome.UNAVAILABLE,
                remoteAttempted = false,
                primaryError = null,
                completedOperations = emptyList(),
            )
        }

        val persistRemote =
            StrategyOperation.PERSIST_REMOTE in continuation.operations ||
                StrategyOperation.READ_CHECKPOINT in continuation.operations

        return when {
            request.direction == SynchronizationDirection.PULL && !persistRemote ->
                executeTransportOnlyPull(
                    request,
                    evaluation,
                    providers,
                    continuation,
                    fallbackProvider,
                )
            request.direction == SynchronizationDirection.BIDIRECTIONAL && !persistRemote ->
                executeNonPersistingBidirectional(
                    request,
                    evaluation,
                    providers,
                    continuation,
                    fallbackProvider,
                )
            else -> executeProviderBackedPipeline(
                request,
                evaluation,
                providers,
                continuation,
                fallbackProvider,
                requireNotNull(pipelineRegistry.lookup(request.direction)),
            )
        }
    }

    private suspend fun executeProviderBackedPipeline(
        request: SynchronizationRequest,
        evaluation: StrategyEvaluationResult,
        providers: StrategyProviderSet,
        continuation: StrategyDurableContinuationPlan,
        fallbackProvider: StrategyLocalFallbackProvider?,
        pipeline: SynchronizationPipeline,
    ): StrategySynchronizationExecutionResult {
        val trackingTransport = TrackingTransportProvider(
            requireNotNull(providers.transportProvider),
        )
        val context = context(request, providers, trackingTransport)
        lifecycleEventEmitter?.emitStarted(context)
        val result = pipeline.execute(context)
        lifecycleEventEmitter?.emitCompleted(context, result)

        val mapped = mapPipelineResult(
            request,
            evaluation,
            providers,
            continuation,
            fallbackProvider,
            trackingTransport,
            result,
        )
        return finalizeReconciliation(
            request = request,
            evaluation = evaluation,
            providers = providers,
            continuation = continuation,
            result = mapped,
            completedOperations = trackingTransport.completedOperations,
        )
    }

    private suspend fun executeTransportOnlyPull(
        request: SynchronizationRequest,
        evaluation: StrategyEvaluationResult,
        providers: StrategyProviderSet,
        continuation: StrategyDurableContinuationPlan,
        fallbackProvider: StrategyLocalFallbackProvider?,
    ): StrategySynchronizationExecutionResult {
        val transport = requireNotNull(providers.transportProvider)
        val result = when (
            val pulled = transport.pullChanges(PullChangesRequest(request = request))
        ) {
            is ProviderOperationResult.Success ->
                StrategySynchronizationExecutionResult.Executed(
                    evaluation = evaluation,
                    completedAt = clock.now(),
                    output = StrategyTransportOutput.Pulled(pulled.value),
                )
            is ProviderOperationResult.Failure ->
                handleRemoteFailure(
                    request = request,
                    evaluation = evaluation,
                    providers = providers,
                    continuation = continuation,
                    fallbackProvider = fallbackProvider,
                    error = pulled.error,
                    operation = StrategyOperation.PULL_REMOTE,
                    completedOperations = emptyList(),
                )
        }
        return finalizeReconciliation(
            request,
            evaluation,
            providers,
            continuation,
            result,
            completedOperations = listOf(StrategyOperation.PULL_REMOTE),
        )
    }

    private suspend fun executeNonPersistingBidirectional(
        request: SynchronizationRequest,
        evaluation: StrategyEvaluationResult,
        providers: StrategyProviderSet,
        continuation: StrategyDurableContinuationPlan,
        fallbackProvider: StrategyLocalFallbackProvider?,
    ): StrategySynchronizationExecutionResult {
        val trackingTransport = TrackingTransportProvider(
            requireNotNull(providers.transportProvider),
        )
        val pushPipeline = requireNotNull(
            pipelineRegistry.lookup(SynchronizationDirection.PUSH),
        )
        val pushResult = pushPipeline.execute(context(request, providers, trackingTransport))

        if (!permitsNextOperation(pushResult)) {
            return mapPipelineResult(
                request,
                evaluation,
                providers,
                continuation,
                fallbackProvider = null,
                trackingTransport = trackingTransport,
                result = pushResult,
            )
        }

        val mapped = when (
            val pulled = trackingTransport.pullChanges(PullChangesRequest(request = request))
        ) {
            is ProviderOperationResult.Success ->
                StrategySynchronizationExecutionResult.Executed(
                    evaluation = evaluation,
                    completedAt = clock.now(),
                    output = StrategyTransportOutput.RemoteFirstBidirectional(
                        pushResult = pushResult,
                        pullResult = pulled.value,
                    ),
                )
            is ProviderOperationResult.Failure ->
                handleRemoteFailure(
                    request = request,
                    evaluation = evaluation,
                    providers = providers,
                    continuation = continuation,
                    fallbackProvider = fallbackProvider,
                    error = pulled.error,
                    operation = StrategyOperation.PULL_REMOTE,
                    completedOperations = trackingTransport.completedOperations,
                    partialOutput = StrategyTransportOutput.ProviderBacked(pushResult),
                )
        }
        return finalizeReconciliation(
            request,
            evaluation,
            providers,
            continuation,
            mapped,
            trackingTransport.completedOperations,
        )
    }

    private suspend fun mapPipelineResult(
        request: SynchronizationRequest,
        evaluation: StrategyEvaluationResult,
        providers: StrategyProviderSet,
        continuation: StrategyDurableContinuationPlan,
        fallbackProvider: StrategyLocalFallbackProvider?,
        trackingTransport: TrackingTransportProvider,
        result: SynchronizationResult,
    ): StrategySynchronizationExecutionResult = when (result) {
        is SynchronizationResult.Failed -> {
            val remoteFailure = trackingTransport.lastFailure?.takeIf { it == result.error }
            if (
                remoteFailure != null &&
                trackingTransport.lastOperation == StrategyOperation.PULL_REMOTE
            ) {
                handleRemoteFailure(
                    request,
                    evaluation,
                    providers,
                    continuation,
                    fallbackProvider,
                    remoteFailure,
                    StrategyOperation.PULL_REMOTE,
                    trackingTransport.completedOperations,
                    StrategyTransportOutput.ProviderBacked(result),
                )
            } else {
                failed(
                    evaluation = evaluation,
                    error = result.error,
                    transportAttempted = trackingTransport.attempted,
                    completedOperations = trackingTransport.completedOperations,
                    partialOutput = StrategyTransportOutput.ProviderBacked(result),
                    remoteOutcome = remoteFailure?.let(StrategyRemoteOutcomeClassifier::classify),
                )
            }
        }
        is SynchronizationResult.Cancelled ->
            StrategySynchronizationExecutionResult.Cancelled(
                evaluation = evaluation,
                completedAt = clock.now(),
                output = StrategyTransportOutput.ProviderBacked(result),
            )
        is SynchronizationResult.Succeeded,
        is SynchronizationResult.PartiallySucceeded,
        is SynchronizationResult.Skipped,
        -> StrategySynchronizationExecutionResult.Executed(
            evaluation = evaluation,
            completedAt = clock.now(),
            output = StrategyTransportOutput.ProviderBacked(result),
        )
    }

    private suspend fun handleRemoteFailure(
        request: SynchronizationRequest,
        evaluation: StrategyEvaluationResult,
        providers: StrategyProviderSet,
        continuation: StrategyDurableContinuationPlan,
        fallbackProvider: StrategyLocalFallbackProvider?,
        error: DataLoomError,
        operation: StrategyOperation,
        completedOperations: List<StrategyOperation>,
        partialOutput: StrategyTransportOutput? = null,
    ): StrategySynchronizationExecutionResult {
        val outcome = StrategyRemoteOutcomeClassifier.classify(error)
        val fallbackPlan = continuation.fallbackPlan
        if (
            operation == StrategyOperation.PULL_REMOTE &&
            fallbackPlan != null &&
            outcome in fallbackPlan.remoteOutcomes &&
            fallbackProvider != null
        ) {
            return executeFallback(
                request,
                evaluation,
                providers,
                continuation,
                fallbackProvider,
                outcome,
                remoteAttempted = true,
                primaryError = error,
                completedOperations = completedOperations,
                partialOutput = partialOutput,
            )
        }
        return failed(
            evaluation = evaluation,
            error = error,
            transportAttempted = true,
            completedOperations = completedOperations,
            partialOutput = partialOutput,
            remoteOutcome = outcome,
        )
    }

    private suspend fun executeFallback(
        request: SynchronizationRequest,
        evaluation: StrategyEvaluationResult,
        providers: StrategyProviderSet,
        continuation: StrategyDurableContinuationPlan,
        provider: StrategyLocalFallbackProvider,
        remoteOutcome: StrategyRemoteOutcome,
        remoteAttempted: Boolean,
        primaryError: DataLoomError?,
        completedOperations: List<StrategyOperation>,
        partialOutput: StrategyTransportOutput? = null,
    ): StrategySynchronizationExecutionResult {
        val fallbackRequest = StrategyLocalFallbackRequest(
            request = request,
            decisionId = evaluation.decisionId,
            planId = evaluation.plan.id,
            profileId = evaluation.plan.effectiveProfileId,
            configurationVersion = evaluation.plan.configurationVersion,
            remoteOutcome = remoteOutcome,
            remoteAttempted = remoteAttempted,
            evaluatedCacheState =
                continuation.evaluatedCacheState ?: StrategyCacheState.MISSING,
        )
        val mapped = when (val result = provider.evaluateLocalFallback(fallbackRequest)) {
            is ProviderOperationResult.Success -> when (val local = result.value) {
                is StrategyLocalFallbackResult.Available ->
                    StrategySynchronizationExecutionResult.FallbackActivated(
                        evaluation = evaluation,
                        completedAt = clock.now(),
                        remoteOutcome = remoteOutcome,
                        remoteAttempted = remoteAttempted,
                        cacheState = local.cacheState,
                        primaryError = primaryError,
                        completedOperations = completedOperations + StrategyOperation.SERVE_LOCAL,
                        partialOutput = partialOutput,
                    )
                is StrategyLocalFallbackResult.Unavailable ->
                    StrategySynchronizationExecutionResult.FallbackUnavailable(
                        evaluation = evaluation,
                        completedAt = clock.now(),
                        remoteOutcome = remoteOutcome,
                        remoteAttempted = remoteAttempted,
                        localResult = local,
                        primaryError = primaryError,
                        partialOutput = partialOutput,
                    )
            }
            is ProviderOperationResult.Failure ->
                failed(
                    evaluation = evaluation,
                    error = result.error,
                    transportAttempted = remoteAttempted,
                    completedOperations = completedOperations,
                    remoteOutcome = remoteOutcome,
                    primaryError = primaryError,
                    fallbackAttempted = true,
                    partialOutput = partialOutput,
                )
        }
        return finalizeReconciliation(
            request,
            evaluation,
            providers,
            continuation,
            mapped,
            completedOperations + StrategyOperation.SERVE_LOCAL,
        )
    }

    private suspend fun finalizeReconciliation(
        request: SynchronizationRequest,
        evaluation: StrategyEvaluationResult,
        providers: StrategyProviderSet,
        continuation: StrategyDurableContinuationPlan,
        result: StrategySynchronizationExecutionResult,
        completedOperations: List<StrategyOperation>,
    ): StrategySynchronizationExecutionResult {
        if (StrategyOperation.RECONCILE !in continuation.operations) return result
        if (
            result !is StrategySynchronizationExecutionResult.Executed &&
            result !is StrategySynchronizationExecutionResult.FallbackActivated
        ) {
            return result
        }

        val reconciliationProvider =
            providers.storageProvider as? StrategyReconciliationProvider
                ?: return rejected(
                    evaluation,
                    StrategyExecutionRejectionReason.RECONCILIATION_PROVIDER_NOT_CONFIGURED,
                )
        val evidence = completedOperations
            .filterNot { it == StrategyOperation.RECONCILE }
            .ifEmpty {
                continuation.operations.filterNot { it == StrategyOperation.RECONCILE }
            }
        return when (
            val reconciliation = reconciliationProvider.reconcileStrategy(
                StrategyReconciliationRequest(
                    request = request,
                    decisionId = evaluation.decisionId,
                    planId = evaluation.plan.id,
                    profileId = evaluation.plan.effectiveProfileId,
                    configurationVersion = evaluation.plan.configurationVersion,
                    completedOperations = evidence,
                ),
            )
        ) {
            is ProviderOperationResult.Success -> result
            is ProviderOperationResult.Failure -> failed(
                evaluation = evaluation,
                error = reconciliation.error,
                transportAttempted = completedOperations.any {
                    it == StrategyOperation.PUSH_REMOTE ||
                        it == StrategyOperation.PULL_REMOTE
                },
                completedOperations = evidence,
                primaryError = if (
                    result is StrategySynchronizationExecutionResult.FallbackActivated
                ) {
                    result.primaryError
                } else {
                    null
                },
                fallbackAttempted =
                    result is StrategySynchronizationExecutionResult.FallbackActivated,
            )
        }
    }

    private fun context(
        request: SynchronizationRequest,
        providers: StrategyProviderSet,
        trackingTransport: TrackingTransportProvider,
    ): SynchronizationExecutionContext =
        SynchronizationExecutionContext(
            request = request,
            providers = ResolvedSynchronizationProviders(
                storageProvider = requireNotNull(providers.storageProvider),
                transportProvider = trackingTransport,
                schedulerProvider = providers.schedulerProvider,
                connectivityProvider = providers.connectivityProvider,
                queueProvider = providers.queueProvider,
            ),
            runtimeDependencies = runtimeDependencies,
            lifecycleEventEmitter = lifecycleEventEmitter,
        )

    private fun permitsNextOperation(result: SynchronizationResult): Boolean = when (result) {
        is SynchronizationResult.Succeeded,
        is SynchronizationResult.PartiallySucceeded,
        -> true
        is SynchronizationResult.Skipped ->
            result.reason == io.dataloom.api.synchronization.SynchronizationSkipReason.NO_CHANGES
        is SynchronizationResult.Failed,
        is SynchronizationResult.Cancelled,
        -> false
    }

    private fun failed(
        evaluation: StrategyEvaluationResult,
        error: DataLoomError,
        transportAttempted: Boolean,
        completedOperations: List<StrategyOperation>,
        partialOutput: StrategyTransportOutput? = null,
        remoteOutcome: StrategyRemoteOutcome? = null,
        primaryError: DataLoomError? = null,
        fallbackAttempted: Boolean = false,
    ): StrategySynchronizationExecutionResult =
        StrategySynchronizationExecutionResult.Failed(
            evaluation = evaluation,
            completedAt = clock.now(),
            error = error,
            transportAttempted = transportAttempted,
            completedOperations = completedOperations,
            partialOutput = partialOutput,
            remoteOutcome = remoteOutcome,
            primaryError = primaryError,
            fallbackAttempted = fallbackAttempted,
        )

    private fun rejected(
        evaluation: StrategyEvaluationResult,
        reason: StrategyExecutionRejectionReason,
    ): StrategySynchronizationExecutionResult =
        StrategySynchronizationExecutionResult.Rejected(
            evaluation = evaluation,
            completedAt = clock.now(),
            reason = reason,
        )
}
