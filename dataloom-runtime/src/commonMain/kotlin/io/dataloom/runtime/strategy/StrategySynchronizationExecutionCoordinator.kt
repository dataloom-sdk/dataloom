package io.dataloom.runtime.strategy

import io.dataloom.api.error.DataLoomError
import io.dataloom.api.error.ErrorCategory
import io.dataloom.api.error.ErrorCode
import io.dataloom.api.error.ErrorSeverity
import io.dataloom.api.error.Recoverability
import io.dataloom.api.model.SynchronizationDirection
import io.dataloom.api.provider.ProviderLifecycleCoordinatorState
import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.api.provider.StrategyProviderBindings
import io.dataloom.api.runtime.RuntimeDependencies
import io.dataloom.api.strategy.BuiltInSynchronizationStrategy
import io.dataloom.api.strategy.StrategyCacheState
import io.dataloom.api.strategy.StrategyDataOrigin
import io.dataloom.api.strategy.StrategyDisposition
import io.dataloom.api.strategy.StrategyEvaluationResult
import io.dataloom.api.strategy.StrategyExecutionTrigger
import io.dataloom.api.strategy.StrategyOfflineFirstAdmissionProvider
import io.dataloom.api.strategy.StrategyOfflineFirstAdmissionRequest
import io.dataloom.api.strategy.StrategyOfflineFirstAdmissionResult
import io.dataloom.api.strategy.StrategyOperation
import io.dataloom.api.strategy.StrategyOperationInput
import io.dataloom.api.strategy.StrategyProviderCapability
import io.dataloom.api.strategy.StrategySynchronizationRequest
import io.dataloom.api.time.DataLoomClock
import io.dataloom.core.provider.ProviderLifecycleCoordinator
import io.dataloom.core.provider.StrategyProviderResolutionResult
import io.dataloom.core.provider.StrategyProviderResolver
import io.dataloom.runtime.execution.SynchronizationPipelineRegistry
import io.dataloom.runtime.execution.lifecycle.SynchronizationLifecycleEventEmitter

/** Admits a strategy request before resolving or invoking any provider. */
internal class StrategySynchronizationExecutionCoordinator(
    private val lifecycleCoordinator: ProviderLifecycleCoordinator,
    private val evaluator: BuiltInSynchronizationStrategyEvaluator,
    private val providerResolver: StrategyProviderResolver,
    private val clock: DataLoomClock,
    private val runtimeDependencies: RuntimeDependencies,
    private val pipelineRegistry: SynchronizationPipelineRegistry,
    private val lifecycleEventEmitter: SynchronizationLifecycleEventEmitter?,
) {
    public suspend fun execute(
        request: StrategySynchronizationRequest,
        bindings: StrategyProviderBindings,
    ): StrategySynchronizationExecutionResult = execute(
        request = request,
        bindings = bindings,
        providerBoundary = StrategyProviderExecutionBoundary.Identity,
    )

    internal suspend fun execute(
        request: StrategySynchronizationRequest,
        bindings: StrategyProviderBindings,
        providerBoundary: StrategyProviderExecutionBoundary,
    ): StrategySynchronizationExecutionResult {
        val evaluation = evaluator.evaluate(request.evaluationRequest())

        if (lifecycleCoordinator.state != ProviderLifecycleCoordinatorState.INITIALIZED) {
            return rejected(
                evaluation = evaluation,
                reason = StrategyExecutionRejectionReason.PROVIDERS_NOT_INITIALIZED,
            )
        }

        if (evaluation.plan.disposition == StrategyDisposition.REJECT) {
            return rejected(
                evaluation = evaluation,
                reason = StrategyExecutionRejectionReason.STRATEGY_REJECTED,
            )
        }

        if (
            evaluation.plan.effectiveStrategy ==
            BuiltInSynchronizationStrategy.OFFLINE_FIRST
        ) {
            return executeDeferredOfflineFirstAdmission(
                request = request,
                evaluation = evaluation,
                bindings = bindings,
                providerBoundary = providerBoundary,
            )
        }

        // Offline-first durable deferral is handled above by its atomic local
        // intent/outbox boundary. Cache-first durable refresh is admitted below
        // as SERVE_AND_REFRESH; unsupported non-offline DEFER plans still fail closed.
        when (evaluation.plan.disposition) {
            StrategyDisposition.DEFER -> return rejected(
                evaluation = evaluation,
                reason = StrategyExecutionRejectionReason.UNSUPPORTED_PLAN,
            )
            StrategyDisposition.EXECUTE,
            StrategyDisposition.SERVE_AND_REFRESH,
            -> Unit
            StrategyDisposition.REJECT -> error("Rejected strategy was handled above.")
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
            BuiltInSynchronizationStrategy.REMOTE_FIRST -> {
                if (request.input !is StrategyOperationInput.ProviderBacked) {
                    return rejected(
                        evaluation = evaluation,
                        reason = StrategyExecutionRejectionReason.INCOMPATIBLE_INPUT,
                    )
                }
            }
            BuiltInSynchronizationStrategy.CACHE_FIRST -> {
                val durableRefresh =
                    isDirectDurableCacheRefreshPlan(request, evaluation)
                if (
                    durableRefresh &&
                    request.input !is StrategyOperationInput.CacheFirstDurableRefresh
                ) {
                    return rejected(
                        evaluation = evaluation,
                        reason = StrategyExecutionRejectionReason.INCOMPATIBLE_INPUT,
                    )
                }
                if (!durableRefresh && request.input !is StrategyOperationInput.ProviderBacked) {
                    return rejected(
                        evaluation = evaluation,
                        reason = StrategyExecutionRejectionReason.INCOMPATIBLE_INPUT,
                    )
                }
                if (!isSupportedDirectCacheFirstPlan(request, evaluation)) {
                    return rejected(
                        evaluation = evaluation,
                        reason = StrategyExecutionRejectionReason.UNSUPPORTED_PLAN,
                    )
                }
            }
            else -> return rejected(
                evaluation = evaluation,
                reason = StrategyExecutionRejectionReason.UNSUPPORTED_PLAN,
            )
        }

        val providers = when (
            val resolution = providerResolver.resolve(
                bindings = bindings,
                requiredCapabilities = evaluation.plan.requiredCapabilities,
            )
        ) {
            is StrategyProviderResolutionResult.Success -> resolution.providers
            is StrategyProviderResolutionResult.Failure -> {
                return StrategySynchronizationExecutionResult.Rejected(
                    evaluation = evaluation,
                    completedAt = clock.now(),
                    reason = StrategyExecutionRejectionReason.PROVIDER_RESOLUTION_FAILED,
                    missingCapabilities = resolution.missingCapabilities,
                    bindingFailures = resolution.bindingFailures,
                )
            }
        }

        val executionProviders = when (
            val preparation = providerBoundary.prepare(evaluation, providers)
        ) {
            is StrategyProviderExecutionPreparation.Prepared -> preparation.providers
            is StrategyProviderExecutionPreparation.Rejected -> return rejected(
                evaluation = evaluation,
                reason = preparation.reason,
            )
        }

        return when (evaluation.plan.effectiveStrategy) {
            BuiltInSynchronizationStrategy.NETWORK_ONLY ->
                NetworkOnlyStrategyExecutor(clock).execute(
                    request = request,
                    evaluation = evaluation,
                    providers = executionProviders,
                )
            BuiltInSynchronizationStrategy.REMOTE_FIRST ->
                RemoteFirstStrategyExecutor(
                    clock = clock,
                    runtimeDependencies = runtimeDependencies,
                    pipelineRegistry = pipelineRegistry,
                    lifecycleEventEmitter = lifecycleEventEmitter,
                ).execute(
                    request = request,
                    evaluation = evaluation,
                    providers = executionProviders,
                )
            BuiltInSynchronizationStrategy.CACHE_FIRST ->
                CacheFirstStrategyExecutor(
                    clock = clock,
                    runtimeDependencies = runtimeDependencies,
                    pipelineRegistry = pipelineRegistry,
                    lifecycleEventEmitter = lifecycleEventEmitter,
                ).execute(
                    request = request,
                    evaluation = evaluation,
                    providers = executionProviders,
                )
            else -> rejected(
                evaluation = evaluation,
                reason = StrategyExecutionRejectionReason.UNSUPPORTED_PLAN,
            )
        }
    }

    private fun isSupportedDirectCacheFirstPlan(
        request: StrategySynchronizationRequest,
        evaluation: StrategyEvaluationResult,
    ): Boolean =
        isDirectCacheServingPlan(evaluation) ||
            isDirectInlineCacheRefreshPlan(request, evaluation) ||
            isDirectDurableCacheRefreshPlan(request, evaluation) ||
            isDirectCacheRemotePlan(request, evaluation)

    private fun isDirectCacheServingPlan(
        evaluation: StrategyEvaluationResult,
    ): Boolean {
        val plan = evaluation.plan
        return plan.disposition == StrategyDisposition.EXECUTE &&
            plan.operations == listOf(StrategyOperation.SERVE_LOCAL) &&
            plan.requiredCapabilities == setOf(
                StrategyProviderCapability.STORAGE,
                StrategyProviderCapability.CACHE_ACCESS,
            ) &&
            plan.dataOrigin == StrategyDataOrigin.LOCAL
    }

    private fun isDirectInlineCacheRefreshPlan(
        request: StrategySynchronizationRequest,
        evaluation: StrategyEvaluationResult,
    ): Boolean {
        val plan = evaluation.plan
        return request.request.direction == SynchronizationDirection.PULL &&
            (
                request.evidence.cacheState == StrategyCacheState.FRESH ||
                    request.evidence.cacheState == StrategyCacheState.STALE
                ) &&
            plan.disposition == StrategyDisposition.SERVE_AND_REFRESH &&
            plan.operations == listOf(
                StrategyOperation.SERVE_LOCAL,
                StrategyOperation.READ_CHECKPOINT,
                StrategyOperation.PULL_REMOTE,
                StrategyOperation.PERSIST_REMOTE,
            ) &&
            plan.requiredCapabilities == setOf(
                StrategyProviderCapability.STORAGE,
                StrategyProviderCapability.CACHE_ACCESS,
                StrategyProviderCapability.TRANSPORT,
            ) &&
            plan.dataOrigin == StrategyDataOrigin.LOCAL &&
            plan.durableContinuation == null &&
            plan.fallbackPlan == null
    }

    private fun isDirectDurableCacheRefreshPlan(
        request: StrategySynchronizationRequest,
        evaluation: StrategyEvaluationResult,
    ): Boolean {
        val plan = evaluation.plan
        val continuation = plan.durableContinuation ?: return false
        return request.request.direction == SynchronizationDirection.PULL &&
            (
                request.evidence.cacheState == StrategyCacheState.FRESH ||
                    request.evidence.cacheState == StrategyCacheState.STALE
                ) &&
            plan.disposition == StrategyDisposition.SERVE_AND_REFRESH &&
            plan.operations == listOf(
                StrategyOperation.SERVE_LOCAL,
                StrategyOperation.ENQUEUE_DURABLE_WORK,
                StrategyOperation.SCHEDULE_REFRESH,
            ) &&
            plan.requiredCapabilities == setOf(
                StrategyProviderCapability.STORAGE,
                StrategyProviderCapability.CACHE_ACCESS,
                StrategyProviderCapability.QUEUE,
                StrategyProviderCapability.SCHEDULER,
            ) &&
            plan.dataOrigin == StrategyDataOrigin.LOCAL &&
            plan.fallbackPlan == null &&
            continuation.operations == listOf(
                StrategyOperation.READ_CHECKPOINT,
                StrategyOperation.PULL_REMOTE,
                StrategyOperation.PERSIST_REMOTE,
            ) &&
            continuation.requiredCapabilities == setOf(
                StrategyProviderCapability.STORAGE,
                StrategyProviderCapability.TRANSPORT,
            ) &&
            continuation.dataOrigin == StrategyDataOrigin.REMOTE &&
            continuation.consistency == plan.consistency &&
            continuation.evaluatedCacheState == request.evidence.cacheState &&
            continuation.fallbackPlan == null
    }

    private fun isDirectCacheRemotePlan(
        request: StrategySynchronizationRequest,
        evaluation: StrategyEvaluationResult,
    ): Boolean {
        val plan = evaluation.plan
        if (
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

    private suspend fun executeDeferredOfflineFirstAdmission(
        request: StrategySynchronizationRequest,
        evaluation: StrategyEvaluationResult,
        bindings: StrategyProviderBindings,
        providerBoundary: StrategyProviderExecutionBoundary,
    ): StrategySynchronizationExecutionResult {
        if (request.trigger == StrategyExecutionTrigger.DURABLE_QUEUE) {
            return rejected(
                evaluation = evaluation,
                reason = StrategyExecutionRejectionReason.INCOMPATIBLE_TRIGGER,
            )
        }
        if (evaluation.plan.disposition != StrategyDisposition.DEFER) {
            return rejected(
                evaluation = evaluation,
                reason = StrategyExecutionRejectionReason.UNSUPPORTED_PLAN,
            )
        }
        val input = request.input as? StrategyOperationInput.OfflineFirstAdmission
            ?: return rejected(
                evaluation = evaluation,
                reason = StrategyExecutionRejectionReason.INCOMPATIBLE_INPUT,
            )
        if (
            StrategyOperation.ACCEPT_LOCAL !in evaluation.plan.operations ||
            StrategyOperation.ENQUEUE_DURABLE_WORK !in evaluation.plan.operations ||
            StrategyProviderCapability.ATOMIC_LOCAL_ADMISSION !in
            evaluation.plan.requiredCapabilities ||
            evaluation.plan.durableContinuation == null
        ) {
            return rejected(
                evaluation = evaluation,
                reason = StrategyExecutionRejectionReason.UNSUPPORTED_PLAN,
            )
        }

        val providers = when (
            val resolution = providerResolver.resolve(
                bindings = bindings,
                requiredCapabilities = evaluation.plan.requiredCapabilities,
            )
        ) {
            is StrategyProviderResolutionResult.Success -> resolution.providers
            is StrategyProviderResolutionResult.Failure -> {
                return StrategySynchronizationExecutionResult.Rejected(
                    evaluation = evaluation,
                    completedAt = clock.now(),
                    reason = StrategyExecutionRejectionReason.PROVIDER_RESOLUTION_FAILED,
                    missingCapabilities = resolution.missingCapabilities,
                    bindingFailures = resolution.bindingFailures,
                )
            }
        }
        val executionProviders = when (
            val preparation = providerBoundary.prepare(evaluation, providers)
        ) {
            is StrategyProviderExecutionPreparation.Prepared -> preparation.providers
            is StrategyProviderExecutionPreparation.Rejected -> return rejected(
                evaluation = evaluation,
                reason = preparation.reason,
            )
        }
        val admissionProvider =
            executionProviders.storageProvider as? StrategyOfflineFirstAdmissionProvider
                ?: return rejected(
                    evaluation = evaluation,
                    reason = StrategyExecutionRejectionReason
                        .ATOMIC_LOCAL_ADMISSION_PROVIDER_NOT_CONFIGURED,
                )

        val admissionRequest = try {
            StrategyOfflineFirstAdmissionRequest(
                request = request.request,
                decisionId = evaluation.decisionId,
                plan = evaluation.plan,
                trigger = request.trigger,
                queueEntryId = input.queueEntryId,
                idempotencyKey = input.idempotencyKey,
            )
        } catch (_: IllegalArgumentException) {
            return rejected(
                evaluation = evaluation,
                reason = StrategyExecutionRejectionReason.UNSUPPORTED_PLAN,
            )
        }

        return when (
            val result = admissionProvider.admitLocalIntentAndOutbox(admissionRequest)
        ) {
            is ProviderOperationResult.Success -> {
                val disposition = when (val admission = result.value) {
                    is StrategyOfflineFirstAdmissionResult.Accepted -> {
                        if (
                            admission.queueEntryId != input.queueEntryId ||
                            admission.idempotencyKey != input.idempotencyKey
                        ) {
                            return admissionIdentityMismatch(evaluation)
                        }
                        StrategyOfflineFirstAdmissionDisposition.ACCEPTED
                    }
                    is StrategyOfflineFirstAdmissionResult.AlreadyAccepted -> {
                        if (
                            admission.queueEntryId != input.queueEntryId ||
                            admission.idempotencyKey != input.idempotencyKey
                        ) {
                            return admissionIdentityMismatch(evaluation)
                        }
                        StrategyOfflineFirstAdmissionDisposition.ALREADY_ACCEPTED
                    }
                }
                StrategySynchronizationExecutionResult.Deferred(
                    evaluation = evaluation,
                    completedAt = clock.now(),
                    admissionDisposition = disposition,
                )
            }
            is ProviderOperationResult.Failure ->
                StrategySynchronizationExecutionResult.Failed(
                    evaluation = evaluation,
                    completedAt = clock.now(),
                    error = result.error,
                    transportAttempted = false,
                )
        }
    }

    private fun admissionIdentityMismatch(
        evaluation: StrategyEvaluationResult,
    ): StrategySynchronizationExecutionResult.Failed =
        StrategySynchronizationExecutionResult.Failed(
            evaluation = evaluation,
            completedAt = clock.now(),
            error = OfflineFirstAdmissionIdentityMismatchError,
            transportAttempted = false,
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

private object OfflineFirstAdmissionIdentityMismatchError : DataLoomError {
    override val code: ErrorCode =
        ErrorCode("STRATEGY_OFFLINE_FIRST_ADMISSION_IDENTITY_MISMATCH")
    override val category: ErrorCategory = ErrorCategory.PROVIDER
    override val severity: ErrorSeverity = ErrorSeverity.ERROR
    override val recoverability: Recoverability = Recoverability.NON_RECOVERABLE
    override val message: String =
        "Offline-first admission provider returned mismatched durable identity."
    override val cause: Throwable? = null
}
