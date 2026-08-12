package io.dataloom.runtime.strategy

import io.dataloom.api.error.DataLoomError
import io.dataloom.api.execution.StrategyProviderSet
import io.dataloom.api.model.SynchronizationDirection
import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.api.runtime.RuntimeDependencies
import io.dataloom.api.strategy.RemoteFirstStrategyProfile
import io.dataloom.api.strategy.StrategyCacheState
import io.dataloom.api.strategy.StrategyEvaluationResult
import io.dataloom.api.strategy.StrategyLocalFallbackProvider
import io.dataloom.api.strategy.StrategyLocalFallbackRequest
import io.dataloom.api.strategy.StrategyLocalFallbackResult
import io.dataloom.api.strategy.StrategyOperation
import io.dataloom.api.strategy.StrategyRemoteOutcome
import io.dataloom.api.strategy.StrategySynchronizationRequest
import io.dataloom.api.strategy.StrategyTransportOutput
import io.dataloom.api.synchronization.SynchronizationResult
import io.dataloom.api.time.DataLoomClock
import io.dataloom.api.transport.PullChangesRequest
import io.dataloom.core.provider.ResolvedSynchronizationProviders
import io.dataloom.runtime.execution.SynchronizationExecutionContext
import io.dataloom.runtime.execution.SynchronizationPipeline
import io.dataloom.runtime.execution.SynchronizationPipelineRegistry
import io.dataloom.runtime.execution.lifecycle.SynchronizationLifecycleEventEmitter

/** Executes the provider-backed remote-first branch and its finite fallback. */
internal class RemoteFirstStrategyExecutor(
    private val clock: DataLoomClock,
    private val runtimeDependencies: RuntimeDependencies,
    private val pipelineRegistry: SynchronizationPipelineRegistry,
    private val lifecycleEventEmitter: SynchronizationLifecycleEventEmitter?,
) {
    public suspend fun execute(
        request: StrategySynchronizationRequest,
        evaluation: StrategyEvaluationResult,
        providers: StrategyProviderSet,
    ): StrategySynchronizationExecutionResult {
        val profile = resolvedProfile(request, evaluation) as RemoteFirstStrategyProfile
        val fallbackProvider = localFallbackProvider(evaluation, providers)
            ?: if (requiresLocalFallback(evaluation)) {
                return rejected(
                    evaluation,
                    StrategyExecutionRejectionReason.LOCAL_FALLBACK_PROVIDER_NOT_CONFIGURED,
                )
            } else {
                null
            }

        if (StrategyOperation.SERVE_LOCAL in evaluation.plan.operations) {
            return executeFallback(
                request = request,
                evaluation = evaluation,
                provider = requireNotNull(fallbackProvider),
                remoteOutcome = StrategyRemoteOutcome.UNAVAILABLE,
                remoteAttempted = false,
                primaryError = null,
                completedOperations = emptyList(),
            )
        }

        if (
            request.request.direction == SynchronizationDirection.PULL &&
            !profile.persistRemoteResult
        ) {
            return executeTransportOnlyPull(
                request,
                evaluation,
                providers,
                fallbackProvider,
            )
        }

        if (
            request.request.direction == SynchronizationDirection.BIDIRECTIONAL &&
            !profile.persistRemoteResult
        ) {
            return executeNonPersistingBidirectional(
                request,
                evaluation,
                providers,
                fallbackProvider,
            )
        }

        return executeProviderBackedPipeline(
            request,
            evaluation,
            providers,
            fallbackProvider,
            pipeline = requireNotNull(pipelineRegistry.lookup(request.request.direction)),
        )
    }

    private suspend fun executeProviderBackedPipeline(
        request: StrategySynchronizationRequest,
        evaluation: StrategyEvaluationResult,
        providers: StrategyProviderSet,
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

        return mapPipelineResult(
            request,
            evaluation,
            fallbackProvider,
            trackingTransport,
            result,
        )
    }

    private suspend fun executeTransportOnlyPull(
        request: StrategySynchronizationRequest,
        evaluation: StrategyEvaluationResult,
        providers: StrategyProviderSet,
        fallbackProvider: StrategyLocalFallbackProvider?,
    ): StrategySynchronizationExecutionResult {
        val transport = requireNotNull(providers.transportProvider)
        return when (
            val result = transport.pullChanges(
                PullChangesRequest(request = request.request),
            )
        ) {
            is ProviderOperationResult.Success ->
                StrategySynchronizationExecutionResult.Executed(
                    evaluation = evaluation,
                    completedAt = clock.now(),
                    output = StrategyTransportOutput.Pulled(result.value),
                )
            is ProviderOperationResult.Failure ->
                handleRemoteFailure(
                    request = request,
                    evaluation = evaluation,
                    fallbackProvider = fallbackProvider,
                    error = result.error,
                    operation = StrategyOperation.PULL_REMOTE,
                    completedOperations = emptyList(),
                )
        }
    }

    private suspend fun executeNonPersistingBidirectional(
        request: StrategySynchronizationRequest,
        evaluation: StrategyEvaluationResult,
        providers: StrategyProviderSet,
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
                fallbackProvider = null,
                trackingTransport = trackingTransport,
                result = pushResult,
            )
        }

        return when (
            val pulled = trackingTransport.pullChanges(
                PullChangesRequest(request = request.request),
            )
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
                    fallbackProvider = fallbackProvider,
                    error = pulled.error,
                    operation = StrategyOperation.PULL_REMOTE,
                    completedOperations = trackingTransport.completedOperations,
                    partialOutput = StrategyTransportOutput.ProviderBacked(pushResult),
                )
        }
    }

    private suspend fun mapPipelineResult(
        request: StrategySynchronizationRequest,
        evaluation: StrategyEvaluationResult,
        fallbackProvider: StrategyLocalFallbackProvider?,
        trackingTransport: TrackingTransportProvider,
        result: SynchronizationResult,
    ): StrategySynchronizationExecutionResult = when (result) {
        is SynchronizationResult.Failed -> {
            val remoteFailure = trackingTransport.lastFailure
                ?.takeIf { it == result.error }
            if (
                remoteFailure != null &&
                trackingTransport.lastOperation == StrategyOperation.PULL_REMOTE
            ) {
                handleRemoteFailure(
                    request = request,
                    evaluation = evaluation,
                    fallbackProvider = fallbackProvider,
                    error = remoteFailure,
                    operation = StrategyOperation.PULL_REMOTE,
                    completedOperations = trackingTransport.completedOperations,
                    partialOutput = StrategyTransportOutput.ProviderBacked(result),
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
        request: StrategySynchronizationRequest,
        evaluation: StrategyEvaluationResult,
        fallbackProvider: StrategyLocalFallbackProvider?,
        error: DataLoomError,
        operation: StrategyOperation,
        completedOperations: List<StrategyOperation>,
        partialOutput: StrategyTransportOutput? = null,
    ): StrategySynchronizationExecutionResult {
        val outcome = StrategyRemoteOutcomeClassifier.classify(error)
        val fallbackPlan = evaluation.plan.fallbackPlan
        if (
            operation == StrategyOperation.PULL_REMOTE &&
            fallbackPlan != null &&
            outcome in fallbackPlan.remoteOutcomes &&
            fallbackProvider != null
        ) {
            return executeFallback(
                request = request,
                evaluation = evaluation,
                provider = fallbackProvider,
                remoteOutcome = outcome,
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
        request: StrategySynchronizationRequest,
        evaluation: StrategyEvaluationResult,
        provider: StrategyLocalFallbackProvider,
        remoteOutcome: StrategyRemoteOutcome,
        remoteAttempted: Boolean,
        primaryError: DataLoomError?,
        completedOperations: List<StrategyOperation>,
        partialOutput: StrategyTransportOutput? = null,
    ): StrategySynchronizationExecutionResult {
        val fallbackRequest = StrategyLocalFallbackRequest(
            request = request.request,
            decisionId = evaluation.decisionId,
            planId = evaluation.plan.id,
            profileId = evaluation.plan.effectiveProfileId,
            configurationVersion = evaluation.plan.configurationVersion,
            remoteOutcome = remoteOutcome,
            remoteAttempted = remoteAttempted,
            evaluatedCacheState = request.evidence.cacheState,
        )
        return when (val result = provider.evaluateLocalFallback(fallbackRequest)) {
            is ProviderOperationResult.Success -> when (val local = result.value) {
                is StrategyLocalFallbackResult.Available ->
                    StrategySynchronizationExecutionResult.FallbackActivated(
                        evaluation = evaluation,
                        completedAt = clock.now(),
                        remoteOutcome = remoteOutcome,
                        remoteAttempted = remoteAttempted,
                        cacheState = local.cacheState,
                        primaryError = primaryError,
                        completedOperations = completedOperations +
                            StrategyOperation.SERVE_LOCAL,
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
    }

    private fun context(
        request: StrategySynchronizationRequest,
        providers: StrategyProviderSet,
        trackingTransport: TrackingTransportProvider,
    ): SynchronizationExecutionContext =
        SynchronizationExecutionContext(
            request = request.request,
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

    private fun localFallbackProvider(
        evaluation: StrategyEvaluationResult,
        providers: StrategyProviderSet,
    ): StrategyLocalFallbackProvider? =
        if (requiresLocalFallback(evaluation)) {
            providers.storageProvider as? StrategyLocalFallbackProvider
        } else {
            null
        }

    private fun requiresLocalFallback(
        evaluation: StrategyEvaluationResult,
    ): Boolean =
        evaluation.plan.fallbackPlan != null ||
            StrategyOperation.SERVE_LOCAL in evaluation.plan.operations

    private fun permitsNextOperation(result: SynchronizationResult): Boolean =
        when (result) {
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
