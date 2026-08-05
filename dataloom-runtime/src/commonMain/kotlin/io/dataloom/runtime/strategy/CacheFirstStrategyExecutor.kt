package io.dataloom.runtime.strategy

import io.dataloom.api.error.DataLoomError
import io.dataloom.api.execution.StrategyProviderSet
import io.dataloom.api.model.SynchronizationDirection
import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.api.runtime.RuntimeDependencies
import io.dataloom.api.strategy.BuiltInSynchronizationStrategy
import io.dataloom.api.strategy.StrategyCacheAccessProvider
import io.dataloom.api.strategy.StrategyCacheAccessRequest
import io.dataloom.api.strategy.StrategyCacheAccessResult
import io.dataloom.api.strategy.StrategyCacheState
import io.dataloom.api.strategy.StrategyDataOrigin
import io.dataloom.api.strategy.StrategyDisposition
import io.dataloom.api.strategy.StrategyEvaluationResult
import io.dataloom.api.strategy.StrategyOperation
import io.dataloom.api.strategy.StrategyOperationInput
import io.dataloom.api.strategy.StrategyProviderCapability
import io.dataloom.api.strategy.StrategyRemoteOutcome
import io.dataloom.api.strategy.StrategySynchronizationRequest
import io.dataloom.api.strategy.StrategyTransportOutput
import io.dataloom.api.synchronization.SynchronizationResult
import io.dataloom.api.time.DataLoomClock
import io.dataloom.core.provider.ResolvedSynchronizationProviders
import io.dataloom.runtime.execution.SynchronizationExecutionContext
import io.dataloom.runtime.execution.SynchronizationPipelineRegistry
import io.dataloom.runtime.execution.lifecycle.SynchronizationLifecycleEventEmitter

/** Executes bounded direct cache-first local-serving and remote pipeline slices. */
internal class CacheFirstStrategyExecutor(
    private val clock: DataLoomClock,
    private val runtimeDependencies: RuntimeDependencies,
    private val pipelineRegistry: SynchronizationPipelineRegistry,
    private val lifecycleEventEmitter: SynchronizationLifecycleEventEmitter?,
) {
    suspend fun execute(
        request: StrategySynchronizationRequest,
        evaluation: StrategyEvaluationResult,
        providers: StrategyProviderSet,
    ): StrategySynchronizationExecutionResult {
        if (request.input !is StrategyOperationInput.ProviderBacked) {
            return rejected(evaluation, StrategyExecutionRejectionReason.INCOMPATIBLE_INPUT)
        }

        return when {
            isSupportedLocalServingPlan(evaluation) ->
                executeLocalServing(request, evaluation, providers)
            isSupportedRemotePlan(request, evaluation) ->
                executeRemotePlan(request, evaluation, providers)
            else -> rejected(evaluation, StrategyExecutionRejectionReason.UNSUPPORTED_PLAN)
        }
    }

    private suspend fun executeLocalServing(
        request: StrategySynchronizationRequest,
        evaluation: StrategyEvaluationResult,
        providers: StrategyProviderSet,
    ): StrategySynchronizationExecutionResult {
        val evaluatedCacheState = request.evidence.cacheState
        if (
            evaluatedCacheState != StrategyCacheState.FRESH &&
            evaluatedCacheState != StrategyCacheState.STALE
        ) {
            return rejected(evaluation, StrategyExecutionRejectionReason.UNSUPPORTED_PLAN)
        }

        val cacheProvider = providers.storageProvider as? StrategyCacheAccessProvider
            ?: return rejected(
                evaluation,
                StrategyExecutionRejectionReason.CACHE_ACCESS_PROVIDER_NOT_CONFIGURED,
            )

        val accessRequest = try {
            StrategyCacheAccessRequest(
                request = request.request,
                decisionId = evaluation.decisionId,
                planId = evaluation.plan.id,
                profileId = evaluation.plan.effectiveProfileId,
                configurationVersion = evaluation.plan.configurationVersion,
                evaluatedCacheState = evaluatedCacheState,
                allowStale = evaluatedCacheState == StrategyCacheState.STALE,
            )
        } catch (_: IllegalArgumentException) {
            return rejected(evaluation, StrategyExecutionRejectionReason.UNSUPPORTED_PLAN)
        }

        return when (val result = cacheProvider.evaluateCacheAccess(accessRequest)) {
            is ProviderOperationResult.Failure ->
                StrategySynchronizationExecutionResult.Failed(
                    evaluation = evaluation,
                    completedAt = clock.now(),
                    error = result.error,
                    transportAttempted = false,
                )
            is ProviderOperationResult.Success -> when (val access = result.value) {
                is StrategyCacheAccessResult.Available ->
                    available(
                        evaluation = evaluation,
                        evaluatedCacheState = evaluatedCacheState,
                        access = access,
                    )
                is StrategyCacheAccessResult.Unavailable ->
                    StrategySynchronizationExecutionResult.CacheUnavailable(
                        evaluation = evaluation,
                        completedAt = clock.now(),
                        evaluatedCacheState = evaluatedCacheState,
                        providerCacheState = access.cacheState,
                        reason = StrategyCacheUnavailableReason.PROVIDER_REPORTED_UNAVAILABLE,
                    )
            }
        }
    }

    private suspend fun executeRemotePlan(
        request: StrategySynchronizationRequest,
        evaluation: StrategyEvaluationResult,
        providers: StrategyProviderSet,
    ): StrategySynchronizationExecutionResult {
        val storage = providers.storageProvider
            ?: return rejected(evaluation, StrategyExecutionRejectionReason.UNSUPPORTED_PLAN)
        val transport = providers.transportProvider
            ?: return rejected(evaluation, StrategyExecutionRejectionReason.UNSUPPORTED_PLAN)
        val pipeline = pipelineRegistry.lookup(request.request.direction)
            ?: return rejected(evaluation, StrategyExecutionRejectionReason.UNSUPPORTED_PLAN)
        val trackingTransport = TrackingTransportProvider(transport)
        val context = SynchronizationExecutionContext(
            request = request.request,
            providers = ResolvedSynchronizationProviders(
                storageProvider = storage,
                transportProvider = trackingTransport,
                schedulerProvider = providers.schedulerProvider,
                connectivityProvider = providers.connectivityProvider,
                queueProvider = providers.queueProvider,
            ),
            runtimeDependencies = runtimeDependencies,
            lifecycleEventEmitter = lifecycleEventEmitter,
        )

        lifecycleEventEmitter?.emitStarted(context)
        val result = pipeline.execute(context)
        lifecycleEventEmitter?.emitCompleted(context, result)

        return mapRemotePlanResult(
            evaluation = evaluation,
            trackingTransport = trackingTransport,
            result = result,
        )
    }

    private fun mapRemotePlanResult(
        evaluation: StrategyEvaluationResult,
        trackingTransport: TrackingTransportProvider,
        result: SynchronizationResult,
    ): StrategySynchronizationExecutionResult = when (result) {
        is SynchronizationResult.Failed -> {
            val remoteFailure = trackingTransport.lastFailure
                ?.takeIf { it == result.error }
            failed(
                evaluation = evaluation,
                error = result.error,
                transportAttempted = trackingTransport.attempted,
                completedOperations = trackingTransport.completedOperations,
                partialOutput = StrategyTransportOutput.ProviderBacked(result),
                remoteOutcome = remoteFailure?.let(StrategyRemoteOutcomeClassifier::classify),
            )
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

    private fun available(
        evaluation: StrategyEvaluationResult,
        evaluatedCacheState: StrategyCacheState,
        access: StrategyCacheAccessResult.Available,
    ): StrategySynchronizationExecutionResult {
        val freshness = access.freshness
        if (
            evaluatedCacheState == StrategyCacheState.FRESH &&
            freshness.cacheState == StrategyCacheState.STALE
        ) {
            return StrategySynchronizationExecutionResult.CacheUnavailable(
                evaluation = evaluation,
                completedAt = clock.now(),
                evaluatedCacheState = evaluatedCacheState,
                providerCacheState = freshness.cacheState,
                reason = StrategyCacheUnavailableReason.FRESHNESS_DOWNGRADED,
                providerFreshness = freshness,
            )
        }
        return StrategySynchronizationExecutionResult.CacheServed(
            evaluation = evaluation,
            completedAt = clock.now(),
            evaluatedCacheState = evaluatedCacheState,
            freshness = freshness,
        )
    }

    private fun isSupportedLocalServingPlan(
        evaluation: StrategyEvaluationResult,
    ): Boolean {
        val plan = evaluation.plan
        return plan.effectiveStrategy == BuiltInSynchronizationStrategy.CACHE_FIRST &&
            plan.disposition == StrategyDisposition.EXECUTE &&
            plan.operations == listOf(StrategyOperation.SERVE_LOCAL) &&
            plan.requiredCapabilities == setOf(
                StrategyProviderCapability.STORAGE,
                StrategyProviderCapability.CACHE_ACCESS,
            ) &&
            plan.dataOrigin == StrategyDataOrigin.LOCAL
    }

    private fun isSupportedRemotePlan(
        request: StrategySynchronizationRequest,
        evaluation: StrategyEvaluationResult,
    ): Boolean {
        val plan = evaluation.plan
        if (
            plan.effectiveStrategy != BuiltInSynchronizationStrategy.CACHE_FIRST ||
            plan.disposition != StrategyDisposition.EXECUTE ||
            plan.requiredCapabilities != setOf(
                StrategyProviderCapability.STORAGE,
                StrategyProviderCapability.TRANSPORT,
            )
        ) {
            return false
        }

        return when (request.request.direction) {
            SynchronizationDirection.PUSH ->
                plan.operations == listOf(
                    StrategyOperation.READ_LOCAL,
                    StrategyOperation.PUSH_REMOTE,
                ) &&
                    plan.dataOrigin == StrategyDataOrigin.LOCAL
            SynchronizationDirection.PULL ->
                request.evidence.cacheState == StrategyCacheState.MISSING &&
                    plan.operations == listOf(
                        StrategyOperation.READ_CHECKPOINT,
                        StrategyOperation.PULL_REMOTE,
                        StrategyOperation.PERSIST_REMOTE,
                    ) &&
                    plan.dataOrigin == StrategyDataOrigin.REMOTE
            SynchronizationDirection.BIDIRECTIONAL ->
                request.evidence.cacheState == StrategyCacheState.MISSING &&
                    plan.operations == listOf(
                        StrategyOperation.READ_LOCAL,
                        StrategyOperation.PUSH_REMOTE,
                        StrategyOperation.READ_CHECKPOINT,
                        StrategyOperation.PULL_REMOTE,
                        StrategyOperation.PERSIST_REMOTE,
                    ) &&
                    plan.dataOrigin == StrategyDataOrigin.MIXED
        }
    }

    private fun failed(
        evaluation: StrategyEvaluationResult,
        error: DataLoomError,
        transportAttempted: Boolean,
        completedOperations: List<StrategyOperation>,
        partialOutput: StrategyTransportOutput? = null,
        remoteOutcome: StrategyRemoteOutcome? = null,
    ): StrategySynchronizationExecutionResult =
        StrategySynchronizationExecutionResult.Failed(
            evaluation = evaluation,
            completedAt = clock.now(),
            error = error,
            transportAttempted = transportAttempted,
            completedOperations = completedOperations,
            partialOutput = partialOutput,
            remoteOutcome = remoteOutcome,
        )

    private fun rejected(
        evaluation: StrategyEvaluationResult,
        reason: StrategyExecutionRejectionReason,
    ): StrategySynchronizationExecutionResult.Rejected =
        StrategySynchronizationExecutionResult.Rejected(
            evaluation = evaluation,
            completedAt = clock.now(),
            reason = reason,
        )
}
