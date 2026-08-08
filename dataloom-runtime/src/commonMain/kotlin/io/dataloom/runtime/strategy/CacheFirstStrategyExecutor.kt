package io.dataloom.runtime.strategy

import io.dataloom.api.connectivity.ConnectivityRequirement
import io.dataloom.api.context.DataLoomMetadata
import io.dataloom.api.error.DataLoomError
import io.dataloom.api.error.ErrorCategory
import io.dataloom.api.error.ErrorCode
import io.dataloom.api.error.ErrorSeverity
import io.dataloom.api.error.Recoverability
import io.dataloom.api.execution.StrategyProviderSet
import io.dataloom.api.model.SynchronizationDirection
import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.api.queue.QueueEnqueueRequest
import io.dataloom.api.queue.QueueEntry
import io.dataloom.api.queue.QueueEntryState
import io.dataloom.api.queue.QueueIdempotentAdmissionProvider
import io.dataloom.api.queue.QueueIdempotentAdmissionResult
import io.dataloom.api.runtime.RuntimeDependencies
import io.dataloom.api.scheduling.ExistingSchedulePolicy
import io.dataloom.api.scheduling.ScheduleConstraints
import io.dataloom.api.scheduling.ScheduleRequest
import io.dataloom.api.scheduling.SchedulerProvider
import io.dataloom.api.strategy.BuiltInSynchronizationStrategy
import io.dataloom.api.strategy.StrategyCacheAccessProvider
import io.dataloom.api.strategy.StrategyCacheAccessRequest
import io.dataloom.api.strategy.StrategyCacheAccessResult
import io.dataloom.api.strategy.StrategyCacheFreshnessEvidence
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
import io.dataloom.api.synchronization.SynchronizationSkipReason
import io.dataloom.api.time.DataLoomClock
import io.dataloom.core.provider.ResolvedSynchronizationProviders
import io.dataloom.runtime.execution.SynchronizationExecutionContext
import io.dataloom.runtime.execution.SynchronizationPipeline
import io.dataloom.runtime.execution.SynchronizationPipelineRegistry
import io.dataloom.runtime.execution.lifecycle.SynchronizationLifecycleEventEmitter

/** Executes bounded direct cache-first local, inline-refresh, and remote slices. */
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
        val durableRefresh = isSupportedDurableRefreshPlan(request, evaluation)
        if (
            durableRefresh &&
            request.input !is StrategyOperationInput.CacheFirstDurableRefresh
        ) {
            return rejected(evaluation, StrategyExecutionRejectionReason.INCOMPATIBLE_INPUT)
        }
        if (!durableRefresh && request.input !is StrategyOperationInput.ProviderBacked) {
            return rejected(evaluation, StrategyExecutionRejectionReason.INCOMPATIBLE_INPUT)
        }

        return when {
            isSupportedLocalServingPlan(evaluation) ->
                executeLocalServing(request, evaluation, providers)
            isSupportedInlineRefreshPlan(request, evaluation) ->
                executeInlineRefresh(request, evaluation, providers)
            durableRefresh ->
                executeDurableRefresh(request, evaluation, providers)
            isSupportedRemotePlan(request, evaluation) ->
                executeRemotePlan(request, evaluation, providers)
            else -> rejected(evaluation, StrategyExecutionRejectionReason.UNSUPPORTED_PLAN)
        }
    }

    private suspend fun executeDurableRefresh(
        request: StrategySynchronizationRequest,
        evaluation: StrategyEvaluationResult,
        providers: StrategyProviderSet,
    ): StrategySynchronizationExecutionResult {
        val input = request.input as? StrategyOperationInput.CacheFirstDurableRefresh
            ?: return rejected(evaluation, StrategyExecutionRejectionReason.INCOMPATIBLE_INPUT)
        val queueProvider = providers.queueProvider as? QueueIdempotentAdmissionProvider
            ?: return rejected(
                evaluation,
                StrategyExecutionRejectionReason
                    .IDEMPOTENT_QUEUE_ADMISSION_PROVIDER_NOT_CONFIGURED,
            )
        val schedulerProvider = providers.schedulerProvider
            ?: return rejected(evaluation, StrategyExecutionRejectionReason.UNSUPPORTED_PLAN)
        val queueAdmission = when (val admission = StrategyQueueAdmissionEvaluator.evaluate(evaluation)) {
            is StrategyQueueAdmissionResult.Admitted -> admission
            is StrategyQueueAdmissionResult.Rejected ->
                return rejected(evaluation, StrategyExecutionRejectionReason.UNSUPPORTED_PLAN)
        }

        return when (val access = evaluateCacheAccess(request, evaluation, providers)) {
            is CacheAccessBoundaryResult.Terminal -> access.result
            is CacheAccessBoundaryResult.Available -> {
                val refresh = admitAndScheduleDurableRefresh(
                    request = request,
                    evaluation = evaluation,
                    input = input,
                    queueProvider = queueProvider,
                    schedulerProvider = schedulerProvider,
                    persistedDecision = queueAdmission.persistedDecision,
                )
                StrategyCacheServedWithDurableRefreshResult(
                    evaluation = evaluation,
                    evaluatedCacheState = access.evaluatedCacheState,
                    freshness = access.freshness,
                    refresh = refresh,
                )
            }
        }
    }

    private suspend fun admitAndScheduleDurableRefresh(
        request: StrategySynchronizationRequest,
        evaluation: StrategyEvaluationResult,
        input: StrategyOperationInput.CacheFirstDurableRefresh,
        queueProvider: QueueIdempotentAdmissionProvider,
        schedulerProvider: SchedulerProvider,
        persistedDecision: io.dataloom.api.strategy.PersistedStrategyDecision,
    ): StrategyCacheDurableRefreshResult {
        val admittedAt = clock.now()
        val entry = try {
            QueueEntry(
                id = input.queueEntryId,
                synchronizationRequest = request.request,
                state = QueueEntryState.PENDING,
                enqueuedAt = admittedAt,
                availableAt = admittedAt,
                metadata = durableRefreshMetadata(input.scheduleId),
                strategyDecision = persistedDecision,
                strategyPlan = evaluation.plan,
            )
        } catch (_: IllegalArgumentException) {
            return StrategyCacheDurableRefreshResult.QueueFailed(
                queueEntryId = input.queueEntryId,
                scheduleId = input.scheduleId,
                error = DurableRefreshAdmissionConstructionError,
                completedAt = clock.now(),
            )
        }

        return when (val result = queueProvider.admit(QueueEnqueueRequest(entry))) {
            is ProviderOperationResult.Failure ->
                StrategyCacheDurableRefreshResult.QueueFailed(
                    queueEntryId = input.queueEntryId,
                    scheduleId = input.scheduleId,
                    error = result.error,
                    completedAt = clock.now(),
                )
            is ProviderOperationResult.Success ->
                mapDurableQueueAdmission(
                    input = input,
                    schedulerProvider = schedulerProvider,
                    admission = result.value,
                )
        }
    }

    private suspend fun mapDurableQueueAdmission(
        input: StrategyOperationInput.CacheFirstDurableRefresh,
        schedulerProvider: SchedulerProvider,
        admission: QueueIdempotentAdmissionResult,
    ): StrategyCacheDurableRefreshResult {
        if (admission.queueEntryId != input.queueEntryId) {
            return StrategyCacheDurableRefreshResult.QueueFailed(
                queueEntryId = input.queueEntryId,
                scheduleId = input.scheduleId,
                error = DurableRefreshQueueIdentityMismatchError,
                completedAt = clock.now(),
            )
        }
        return when (admission) {
            is QueueIdempotentAdmissionResult.Accepted ->
                scheduleDurableRefresh(
                    input = input,
                    schedulerProvider = schedulerProvider,
                    queueDisposition =
                        StrategyCacheDurableQueueAdmissionDisposition.ACCEPTED,
                    queueState = admission.currentState,
                )
            is QueueIdempotentAdmissionResult.AlreadyAccepted ->
                when (admission.currentState) {
                    QueueEntryState.PENDING ->
                        scheduleDurableRefresh(
                            input = input,
                            schedulerProvider = schedulerProvider,
                            queueDisposition =
                                StrategyCacheDurableQueueAdmissionDisposition
                                    .ALREADY_ACCEPTED,
                            queueState = admission.currentState,
                        )
                    QueueEntryState.LEASED,
                    QueueEntryState.RETRY_WAITING,
                    -> StrategyCacheDurableRefreshResult.AlreadyInProgress(
                        queueEntryId = input.queueEntryId,
                        scheduleId = input.scheduleId,
                        queueState = admission.currentState,
                        completedAt = clock.now(),
                    )
                    QueueEntryState.COMPLETED,
                    QueueEntryState.FAILED,
                    QueueEntryState.CANCELLED,
                    QueueEntryState.DEAD_LETTER,
                    -> StrategyCacheDurableRefreshResult.AlreadyTerminal(
                        queueEntryId = input.queueEntryId,
                        scheduleId = input.scheduleId,
                        queueState = admission.currentState,
                        completedAt = clock.now(),
                    )
                }
            is QueueIdempotentAdmissionResult.IdentityConflict ->
                StrategyCacheDurableRefreshResult.IdentityConflict(
                    queueEntryId = input.queueEntryId,
                    scheduleId = input.scheduleId,
                    currentState = admission.currentState,
                    completedAt = clock.now(),
                )
        }
    }

    private suspend fun scheduleDurableRefresh(
        input: StrategyOperationInput.CacheFirstDurableRefresh,
        schedulerProvider: SchedulerProvider,
        queueDisposition: StrategyCacheDurableQueueAdmissionDisposition,
        queueState: QueueEntryState,
    ): StrategyCacheDurableRefreshResult {
        val request = ScheduleRequest(
            id = input.scheduleId,
            synchronizationRequest = null,
            constraints = ScheduleConstraints(
                connectivity = ConnectivityRequirement.AVAILABLE,
            ),
            existingPolicy = ExistingSchedulePolicy.KEEP,
        )
        return when (val result = schedulerProvider.schedule(request)) {
            is ProviderOperationResult.Failure ->
                StrategyCacheDurableRefreshResult.ScheduleFailed(
                    queueEntryId = input.queueEntryId,
                    scheduleId = input.scheduleId,
                    queueAdmissionDisposition = queueDisposition,
                    queueState = queueState,
                    error = result.error,
                    completedAt = clock.now(),
                )
            is ProviderOperationResult.Success -> {
                if (result.value.id != input.scheduleId) {
                    StrategyCacheDurableRefreshResult.ScheduleFailed(
                        queueEntryId = input.queueEntryId,
                        scheduleId = input.scheduleId,
                        queueAdmissionDisposition = queueDisposition,
                        queueState = queueState,
                        error = DurableRefreshScheduleIdentityMismatchError,
                        completedAt = clock.now(),
                    )
                } else {
                    StrategyCacheDurableRefreshResult.Scheduled(
                        queueEntryId = input.queueEntryId,
                        scheduleId = input.scheduleId,
                        queueAdmissionDisposition = queueDisposition,
                        queueState = queueState,
                        receipt = result.value,
                        completedAt = clock.now(),
                    )
                }
            }
        }
    }

    private suspend fun executeLocalServing(
        request: StrategySynchronizationRequest,
        evaluation: StrategyEvaluationResult,
        providers: StrategyProviderSet,
    ): StrategySynchronizationExecutionResult =
        when (val access = evaluateCacheAccess(request, evaluation, providers)) {
            is CacheAccessBoundaryResult.Terminal -> access.result
            is CacheAccessBoundaryResult.Available ->
                StrategySynchronizationExecutionResult.CacheServed(
                    evaluation = evaluation,
                    completedAt = clock.now(),
                    evaluatedCacheState = access.evaluatedCacheState,
                    freshness = access.freshness,
                )
        }

    private suspend fun executeInlineRefresh(
        request: StrategySynchronizationRequest,
        evaluation: StrategyEvaluationResult,
        providers: StrategyProviderSet,
    ): StrategySynchronizationExecutionResult {
        val storage = providers.storageProvider
            ?: return rejected(evaluation, StrategyExecutionRejectionReason.UNSUPPORTED_PLAN)
        val transport = providers.transportProvider
            ?: return rejected(evaluation, StrategyExecutionRejectionReason.UNSUPPORTED_PLAN)
        val pipeline = pipelineRegistry.lookup(SynchronizationDirection.PULL)
            ?: return rejected(evaluation, StrategyExecutionRejectionReason.UNSUPPORTED_PLAN)

        return when (val access = evaluateCacheAccess(request, evaluation, providers)) {
            is CacheAccessBoundaryResult.Terminal -> access.result
            is CacheAccessBoundaryResult.Available -> {
                val refresh = executeInlinePullRefresh(
                    request = request,
                    storage = storage,
                    transport = transport,
                    providers = providers,
                    pipeline = pipeline,
                )
                StrategyCacheServedWithInlineRefreshResult(
                    evaluation = evaluation,
                    evaluatedCacheState = access.evaluatedCacheState,
                    freshness = access.freshness,
                    refresh = refresh,
                )
            }
        }
    }

    private suspend fun evaluateCacheAccess(
        request: StrategySynchronizationRequest,
        evaluation: StrategyEvaluationResult,
        providers: StrategyProviderSet,
    ): CacheAccessBoundaryResult {
        val evaluatedCacheState = request.evidence.cacheState
        if (
            evaluatedCacheState != StrategyCacheState.FRESH &&
            evaluatedCacheState != StrategyCacheState.STALE
        ) {
            return CacheAccessBoundaryResult.Terminal(
                rejected(evaluation, StrategyExecutionRejectionReason.UNSUPPORTED_PLAN),
            )
        }

        val cacheProvider = providers.storageProvider as? StrategyCacheAccessProvider
            ?: return CacheAccessBoundaryResult.Terminal(
                rejected(
                    evaluation,
                    StrategyExecutionRejectionReason.CACHE_ACCESS_PROVIDER_NOT_CONFIGURED,
                ),
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
            return CacheAccessBoundaryResult.Terminal(
                rejected(evaluation, StrategyExecutionRejectionReason.UNSUPPORTED_PLAN),
            )
        }

        return when (val result = cacheProvider.evaluateCacheAccess(accessRequest)) {
            is ProviderOperationResult.Failure ->
                CacheAccessBoundaryResult.Terminal(
                    StrategySynchronizationExecutionResult.Failed(
                        evaluation = evaluation,
                        completedAt = clock.now(),
                        error = result.error,
                        transportAttempted = false,
                    ),
                )
            is ProviderOperationResult.Success -> when (val access = result.value) {
                is StrategyCacheAccessResult.Available -> {
                    val freshness = access.freshness
                    if (
                        evaluatedCacheState == StrategyCacheState.FRESH &&
                        freshness.cacheState == StrategyCacheState.STALE
                    ) {
                        CacheAccessBoundaryResult.Terminal(
                            StrategySynchronizationExecutionResult.CacheUnavailable(
                                evaluation = evaluation,
                                completedAt = clock.now(),
                                evaluatedCacheState = evaluatedCacheState,
                                providerCacheState = freshness.cacheState,
                                reason = StrategyCacheUnavailableReason.FRESHNESS_DOWNGRADED,
                                providerFreshness = freshness,
                            ),
                        )
                    } else {
                        CacheAccessBoundaryResult.Available(
                            evaluatedCacheState = evaluatedCacheState,
                            freshness = freshness,
                        )
                    }
                }
                is StrategyCacheAccessResult.Unavailable ->
                    CacheAccessBoundaryResult.Terminal(
                        StrategySynchronizationExecutionResult.CacheUnavailable(
                            evaluation = evaluation,
                            completedAt = clock.now(),
                            evaluatedCacheState = evaluatedCacheState,
                            providerCacheState = access.cacheState,
                            reason = StrategyCacheUnavailableReason.PROVIDER_REPORTED_UNAVAILABLE,
                        ),
                    )
            }
        }
    }

    private suspend fun executeInlinePullRefresh(
        request: StrategySynchronizationRequest,
        storage: io.dataloom.api.storage.StorageProvider,
        transport: io.dataloom.api.transport.TransportProvider,
        providers: StrategyProviderSet,
        pipeline: SynchronizationPipeline,
    ): StrategyCacheInlineRefreshResult {
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
        val output = StrategyTransportOutput.ProviderBacked(result)

        return when (result) {
            is SynchronizationResult.Succeeded ->
                StrategyCacheInlineRefreshResult.Completed(
                    completedOperations = trackingTransport.completedOperations,
                    output = output,
                )
            is SynchronizationResult.Skipped -> {
                check(result.reason == SynchronizationSkipReason.NO_CHANGES) {
                    "Canonical cache-first inline refresh returned an unsupported skip reason."
                }
                StrategyCacheInlineRefreshResult.Completed(
                    completedOperations = trackingTransport.completedOperations,
                    output = output,
                )
            }
            is SynchronizationResult.PartiallySucceeded ->
                StrategyCacheInlineRefreshResult.PartiallySucceeded(
                    completedOperations = trackingTransport.completedOperations,
                    output = output,
                )
            is SynchronizationResult.Failed -> {
                val remoteFailure = trackingTransport.lastFailure
                    ?.takeIf { it == result.error }
                StrategyCacheInlineRefreshResult.Failed(
                    transportAttempted = trackingTransport.attempted,
                    completedOperations = trackingTransport.completedOperations,
                    output = output,
                    remoteOutcome = remoteFailure?.let(
                        StrategyRemoteOutcomeClassifier::classify,
                    ),
                )
            }
            is SynchronizationResult.Cancelled ->
                StrategyCacheInlineRefreshResult.Cancelled(
                    transportAttempted = trackingTransport.attempted,
                    completedOperations = trackingTransport.completedOperations,
                    output = output,
                )
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

    private fun isSupportedInlineRefreshPlan(
        request: StrategySynchronizationRequest,
        evaluation: StrategyEvaluationResult,
    ): Boolean {
        val plan = evaluation.plan
        return request.request.direction == SynchronizationDirection.PULL &&
            (
                request.evidence.cacheState == StrategyCacheState.FRESH ||
                    request.evidence.cacheState == StrategyCacheState.STALE
                ) &&
            plan.effectiveStrategy == BuiltInSynchronizationStrategy.CACHE_FIRST &&
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

    private fun isSupportedDurableRefreshPlan(
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
            plan.effectiveStrategy == BuiltInSynchronizationStrategy.CACHE_FIRST &&
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

private sealed interface CacheAccessBoundaryResult {
    data class Available(
        val evaluatedCacheState: StrategyCacheState,
        val freshness: StrategyCacheFreshnessEvidence,
    ) : CacheAccessBoundaryResult

    data class Terminal(
        val result: StrategySynchronizationExecutionResult,
    ) : CacheAccessBoundaryResult
}

private fun durableRefreshMetadata(
    scheduleId: io.dataloom.api.identifier.ScheduleId,
): DataLoomMetadata = DataLoomMetadata.of(
    mapOf(DURABLE_REFRESH_SCHEDULE_ID_METADATA_KEY to scheduleId.value),
)

private const val DURABLE_REFRESH_SCHEDULE_ID_METADATA_KEY: String =
    "dataloom-schedule-id"

private object DurableRefreshAdmissionConstructionError : DataLoomError {
    override val code: ErrorCode =
        ErrorCode("STRATEGY_DURABLE_REFRESH_ADMISSION_CONSTRUCTION_FAILED")
    override val category: ErrorCategory = ErrorCategory.STATE
    override val severity: ErrorSeverity = ErrorSeverity.ERROR
    override val recoverability: Recoverability = Recoverability.NON_RECOVERABLE
    override val message: String =
        "Durable cache refresh work could not be represented by the accepted queue model."
    override val cause: Throwable? = null
}

private object DurableRefreshQueueIdentityMismatchError : DataLoomError {
    override val code: ErrorCode =
        ErrorCode("STRATEGY_DURABLE_REFRESH_QUEUE_IDENTITY_MISMATCH")
    override val category: ErrorCategory = ErrorCategory.PROVIDER
    override val severity: ErrorSeverity = ErrorSeverity.ERROR
    override val recoverability: Recoverability = Recoverability.NON_RECOVERABLE
    override val message: String =
        "Queue provider returned a different durable refresh identity."
    override val cause: Throwable? = null
}

private object DurableRefreshScheduleIdentityMismatchError : DataLoomError {
    override val code: ErrorCode =
        ErrorCode("STRATEGY_DURABLE_REFRESH_SCHEDULE_IDENTITY_MISMATCH")
    override val category: ErrorCategory = ErrorCategory.PROVIDER
    override val severity: ErrorSeverity = ErrorSeverity.ERROR
    override val recoverability: Recoverability = Recoverability.NON_RECOVERABLE
    override val message: String =
        "Scheduler provider returned a different durable refresh identity."
    override val cause: Throwable? = null
}
