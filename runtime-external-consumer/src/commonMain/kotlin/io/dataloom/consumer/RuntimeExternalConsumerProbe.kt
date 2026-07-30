package io.dataloom.consumer

import io.dataloom.api.error.RetryDelayHint
import io.dataloom.api.error.RetryDelayHintCarrier
import io.dataloom.api.error.RetryDelayHintSource
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
import io.dataloom.api.retry.RetryBudgetState
import io.dataloom.api.retry.RetryDecision
import io.dataloom.api.retry.RetryEvaluationRequest
import io.dataloom.api.retry.RetryPolicy
import io.dataloom.api.runtime.RuntimeDependencies
import io.dataloom.api.scheduling.ExistingSchedulePolicy
import io.dataloom.api.scheduling.ScheduleConstraints
import io.dataloom.api.scheduling.SchedulerProvider
import io.dataloom.api.scheduling.SchedulingDelay
import io.dataloom.api.strategy.ClassifiedStrategyRemoteError
import io.dataloom.api.strategy.StrategyFallbackPlan
import io.dataloom.api.strategy.StrategyLocalFallbackProvider
import io.dataloom.api.strategy.StrategyRemoteOutcome
import io.dataloom.api.strategy.StrategySynchronizationRequest
import io.dataloom.api.time.DataLoomClock
import io.dataloom.runtime.execution.SynchronizationExecutionResult
import io.dataloom.runtime.facade.DataLoom
import io.dataloom.runtime.retry.RetryBackoffStrategy
import io.dataloom.runtime.retry.RetryBudgetConfiguration
import io.dataloom.runtime.retry.RetryHintConfiguration
import io.dataloom.runtime.retry.RetryJitterStrategy
import io.dataloom.runtime.retry.RetryRandomRequest
import io.dataloom.runtime.retry.RetryRandomSource
import io.dataloom.runtime.retry.RetrySchedulingConfiguration
import io.dataloom.runtime.retry.SeededRetryRandomSource
import io.dataloom.runtime.retry.StandardRetryPolicy
import io.dataloom.runtime.retry.RetryTimeoutConfiguration
import io.dataloom.runtime.retry.RetryTimeoutExecutionRequest
import io.dataloom.runtime.retry.RetryTimeoutExecutionResult
import io.dataloom.runtime.retry.RetryTimeoutExecutor
import io.dataloom.runtime.retry.RetryTimeoutKind
import io.dataloom.runtime.retry.SynchronizationRetryEvaluator
import io.dataloom.runtime.retry.SynchronizationRetryOrchestrator
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

/** Compile-only use of all built-in standard retry and jitter variants. */
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
    val noJitter: RetryJitterStrategy = RetryJitterStrategy.None
    val fullJitter: RetryJitterStrategy = RetryJitterStrategy.Full
    val equalJitter: RetryJitterStrategy = RetryJitterStrategy.Equal
    val randomSource: RetryRandomSource = SeededRetryRandomSource(seed = 42L)

    immediate.toString()
    fixed.toString()
    linear.toString()
    noJitter.toString()
    equalJitter.toString()

    val policy = StandardRetryPolicy(
        id = RetryPolicyId("external-standard-retry"),
        strategy = exponential,
        maximumAttempts = 5,
    )
    val jitteredPolicy = StandardRetryPolicy(
        id = RetryPolicyId("external-standard-retry-jittered"),
        strategy = exponential,
        maximumAttempts = 5,
        jitterStrategy = fullJitter,
        randomSource = randomSource,
    )
    val randomRequest = RetryRandomRequest(
        policyId = jitteredPolicy.id,
        workflowId = request.synchronizationRequest.workflowId,
        sessionId = request.synchronizationRequest.sessionId,
        operation = request.operation,
        errorCode = request.error.code,
        attempt = request.attempt,
        maximumInclusive = 60_000L,
    )

    policy.id
    policy.strategy
    policy.maximumAttempts
    policy.evaluate(request).toString()
    jitteredPolicy.jitterStrategy
    randomSource.sample(randomRequest)
    return jitteredPolicy.evaluate(request)
}

/** Compile-only use of durable retry budget state and configuration. */
internal fun compileRetryBudgetConsumer(
    request: RetryEvaluationRequest,
    state: RetryBudgetState,
): RetryDecision {
    val configuration = RetryBudgetConfiguration(
        maximumElapsedTime = SchedulingDelay(120_000L),
        maximumCumulativeDelay = SchedulingDelay(90_000L),
    )
    state.windowStartedAt
    state.lastEvaluatedAt
    state.cumulativeDelay
    configuration.maximumElapsedTime
    configuration.maximumCumulativeDelay
    return StandardRetryPolicy(
        id = RetryPolicyId("external-budget-policy"),
        strategy = RetryBackoffStrategy.Fixed(SchedulingDelay(1_000L)),
        maximumAttempts = 3,
    ).evaluate(request)
}

/** Compile-only use of normalized hints and evaluator/orchestrator constructors. */
internal fun compileRetryHintConsumer(
    retryPolicy: RetryPolicy,
    clock: DataLoomClock,
    schedulerProvider: SchedulerProvider?,
    carrier: RetryDelayHintCarrier,
    request: RetryEvaluationRequest,
): Pair<SynchronizationRetryEvaluator, SynchronizationRetryOrchestrator> {
    val serverHint = RetryDelayHint(
        delayMilliseconds = 5_000L,
        source = RetryDelayHintSource.SERVER,
    )
    val providerHint = RetryDelayHint(
        delayMilliseconds = 7_500L,
        source = RetryDelayHintSource.PROVIDER,
    )
    val hintConfiguration = RetryHintConfiguration(
        maximumHintDelay = SchedulingDelay(60_000L),
    )
    val budgetConfiguration = RetryBudgetConfiguration(
        maximumElapsedTime = SchedulingDelay(120_000L),
        maximumCumulativeDelay = SchedulingDelay(90_000L),
    )

    serverHint.source
    providerHint.delayMilliseconds
    carrier.retryDelayHint
    request.retryDelayHint
    hintConfiguration.maximumHintDelay

    val evaluator = SynchronizationRetryEvaluator(
        retryPolicy = retryPolicy,
        clock = clock,
        budgetConfiguration = budgetConfiguration,
        hintConfiguration = hintConfiguration,
    )
    val orchestrator = SynchronizationRetryOrchestrator(
        retryPolicy = retryPolicy,
        schedulerProvider = schedulerProvider,
        configuration = RetrySchedulingConfiguration(
            constraints = ScheduleConstraints(),
            existingSchedulePolicy = ExistingSchedulePolicy.REPLACE,
        ),
        clock = clock,
        budgetConfiguration = budgetConfiguration,
        hintConfiguration = hintConfiguration,
    )
    return evaluator to orchestrator
}


internal suspend fun compileTimeoutConsumer(executor: RetryTimeoutExecutor) {
    val configuration = RetryTimeoutConfiguration(
        connectionTimeout = SchedulingDelay(1_000L),
        requestTimeout = SchedulingDelay(2_000L),
        idleTimeout = SchedulingDelay(3_000L),
        providerTimeout = SchedulingDelay(4_000L),
        policyTimeout = SchedulingDelay(500L),
        workflowTimeout = SchedulingDelay(10_000L),
    )
    val request = RetryTimeoutExecutionRequest(
        kind = RetryTimeoutKind.PROVIDER,
        timeout = configuration.timeoutFor(RetryTimeoutKind.PROVIDER)!!,
    )
    val result: RetryTimeoutExecutionResult<String> = executor.execute(request) { "ok" }
    result.toString()
}
