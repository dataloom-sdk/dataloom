package io.dataloom.consumer

import io.dataloom.api.execution.StrategyProviderSet
import io.dataloom.api.execution.SynchronizationProviderSet
import io.dataloom.api.identifier.RetryPolicyId
import io.dataloom.api.model.SynchronizationRequest
import io.dataloom.api.provider.ProviderBindingFailure
import io.dataloom.api.provider.ProviderLifecycleResult
import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.api.provider.StrategyProviderBindings
import io.dataloom.api.provider.SynchronizationProviderBindings
import io.dataloom.api.queue.QueueDeferralRequest
import io.dataloom.api.queue.QueueProvider
import io.dataloom.api.retry.RetryDecision
import io.dataloom.api.retry.RetryEvaluationRequest
import io.dataloom.api.runtime.RuntimeDependencies
import io.dataloom.api.scheduling.SchedulingDelay
import io.dataloom.api.strategy.ClassifiedStrategyRemoteError
import io.dataloom.api.strategy.StrategyFallbackPlan
import io.dataloom.api.strategy.StrategyLocalFallbackProvider
import io.dataloom.api.strategy.StrategyRemoteOutcome
import io.dataloom.api.strategy.StrategySynchronizationRequest
import io.dataloom.runtime.execution.SynchronizationExecutionResult
import io.dataloom.runtime.facade.DataLoom
import io.dataloom.runtime.retry.RetryBackoffStrategy
import io.dataloom.runtime.retry.StandardRetryPolicy
import io.dataloom.runtime.strategy.StrategySynchronizationExecutionResult

/**
 * Compile-only use of the supported runtime surface from outside all SDK
 * implementation modules.
 */
internal suspend fun compileRuntimeConsumer(
    dataLoom: DataLoom,
    request: SynchronizationRequest,
    bindings: SynchronizationProviderBindings,
    dependencies: RuntimeDependencies,
    providers: SynchronizationProviderSet,
): List<ProviderBindingFailure> {
    val initialized: ProviderLifecycleResult = dataLoom.initialize()
    val execution: SynchronizationExecutionResult =
        dataLoom.synchronize(request, bindings)
    val shutdown: ProviderLifecycleResult = dataLoom.shutdown()

    dependencies.clock.now()
    providers.storageProvider
    initialized.toString()
    shutdown.toString()

    return when (execution) {
        is SynchronizationExecutionResult.Executed -> emptyList()
        is SynchronizationExecutionResult.Rejected -> execution.providerBindingFailures
    }
}

/** Compile-only use of the plan-aware strategy surface from an external module. */
internal suspend fun compileStrategyRuntimeConsumer(
    dataLoom: DataLoom,
    request: StrategySynchronizationRequest,
    bindings: StrategyProviderBindings,
    providers: StrategyProviderSet,
): StrategySynchronizationExecutionResult {
    providers.transportProvider
    return dataLoom.synchronize(request, bindings)
}

/** Compile-only use of the public remote-first fallback surface. */
internal fun compileRemoteFirstRuntimeConsumer(
    fallbackPlan: StrategyFallbackPlan,
    fallbackProvider: StrategyLocalFallbackProvider,
    remoteError: ClassifiedStrategyRemoteError,
    result: StrategySynchronizationExecutionResult,
): StrategyRemoteOutcome {
    fallbackPlan.operations
    fallbackPlan.remoteOutcomes
    fallbackProvider.descriptor
    if (result is StrategySynchronizationExecutionResult.FallbackActivated) {
        result.cacheState
        result.completedOperations
    }
    return remoteError.remoteOutcome
}

/** Compile-only use of the public non-retry queue-deferral contract. */
internal suspend fun compileQueueDeferralConsumer(
    queueProvider: QueueProvider,
    request: QueueDeferralRequest,
): ProviderOperationResult<Unit> {
    request.reason
    return queueProvider.defer(request)
}

/** Compile-only use of all built-in standard retry strategy variants. */
internal fun compileStandardRetryPolicyConsumer(
    request: RetryEvaluationRequest,
): RetryDecision {
    val immediate: RetryBackoffStrategy = RetryBackoffStrategy.Immediate
    val fixed: RetryBackoffStrategy = RetryBackoffStrategy.Fixed(
        delay = SchedulingDelay(1_000L),
    )
    val linear: RetryBackoffStrategy = RetryBackoffStrategy.Linear(
        initialDelay = SchedulingDelay(1_000L),
        increment = SchedulingDelay(500L),
        maximumDelay = SchedulingDelay(10_000L),
    )
    val exponential: RetryBackoffStrategy = RetryBackoffStrategy.Exponential(
        initialDelay = SchedulingDelay(1_000L),
        multiplier = 2,
        maximumDelay = SchedulingDelay(60_000L),
    )

    immediate.toString()
    fixed.toString()
    linear.toString()

    val policy = StandardRetryPolicy(
        id = RetryPolicyId("external-standard-retry"),
        strategy = exponential,
        maximumAttempts = 5,
    )
    policy.id
    policy.strategy
    policy.maximumAttempts
    return policy.evaluate(request)
}
