package io.dataloom.runtime.strategy

import io.dataloom.api.error.DataLoomError
import io.dataloom.api.error.ErrorCategory
import io.dataloom.api.error.ErrorCode
import io.dataloom.api.error.ErrorSeverity
import io.dataloom.api.error.Recoverability
import io.dataloom.api.error.safeDiagnosticString
import io.dataloom.api.execution.StrategyProviderSet
import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.api.runtime.RuntimeDependencies
import io.dataloom.api.strategy.StrategyEvaluationResult
import io.dataloom.api.strategy.StrategyLocalFallbackProvider
import io.dataloom.api.strategy.StrategyLocalFallbackRequest
import io.dataloom.api.strategy.StrategyLocalFallbackResult
import io.dataloom.api.strategy.StrategyOperation
import io.dataloom.api.strategy.StrategyReconciliationProvider
import io.dataloom.api.strategy.StrategyReconciliationRequest
import io.dataloom.api.strategy.StrategySynchronizationRequest
import io.dataloom.api.strategy.StrategyTransportOutput
import io.dataloom.api.synchronization.SynchronizationResult
import io.dataloom.api.time.DataLoomClock
import io.dataloom.core.provider.ResolvedSynchronizationProviders
import io.dataloom.runtime.execution.SynchronizationExecutionContext
import io.dataloom.runtime.execution.SynchronizationPipelineRegistry
import io.dataloom.runtime.execution.lifecycle.SynchronizationLifecycleEventEmitter

/**
 * Executes the offline-first branch: accept/serve local state unconditionally,
 * then synchronize with the remote endpoint now that connectivity is
 * available.
 *
 * ## Scope
 *
 * [io.dataloom.runtime.strategy.BuiltInSynchronizationStrategyEvaluator] only
 * produces an `EXECUTE`-disposition plan for
 * [io.dataloom.api.strategy.OfflineFirstStrategyProfile] when connectivity is
 * available (`StrategyConnectivity.AVAILABLE`); when connectivity is
 * unavailable or unknown, the plan is `DEFER`, which
 * [StrategySynchronizationExecutionCoordinator] already handles generically
 * before any executor is invoked. So every plan this executor receives always
 * carries at least one remote operation (`PUSH_REMOTE`/`PULL_REMOTE`) — unlike
 * [CacheFirstStrategyExecutor], there is no local-only `EXECUTE` branch to
 * handle here.
 *
 * `ACCEPT_LOCAL` requires no executor action of its own: it documents that the
 * local write was already accepted by the time this plan was evaluated (the
 * whole point of offline-first), not a distinct operation this executor
 * performs.
 *
 * `SERVE_LOCAL` is handled the same way
 * [CacheFirstStrategyExecutor] handles it, via
 * [StrategyLocalFallbackProvider.evaluateLocalFallback] — but the served
 * cache state itself is not part of this executor's terminal result: unlike
 * cache-first, offline-first's primary deliverable is the synchronized state
 * from the pipeline run, not a served cache snapshot.
 *
 * A plan that requires durable queue admission (`profile.requireDurableQueue`,
 * the default — `operations` includes `ENQUEUE_DURABLE_WORK`) is, unlike
 * cache-first's own durable branch, always paired with the *same* plan's
 * full remote operations and (when `reconcileWhenOnline`) `RECONCILE` too —
 * connectivity is available, so the evaluator expects both "try now" and
 * "guarantee durability" from one plan. Running both would risk a duplicate
 * remote call once the durably admitted continuation is later processed by
 * a queue worker (there is no reliable way to cancel an entry that a
 * concurrent worker may already have picked up), so when
 * [durableQueueAdmitter] is configured and admission succeeds, durable
 * admission *replaces* the synchronous remote attempt for this call: the
 * method returns [StrategySynchronizationExecutionResult.DurablyEnqueued]
 * immediately and never runs the pipeline or reconciliation below. This
 * matches offline-first's own one-line description ("commit local intent
 * durably, then synchronize") — the actual push/pull happens later, off
 * this call, when the durably admitted continuation is processed.
 *
 * When [durableQueueAdmitter] is `null` or admission reports
 * [StrategyDurableQueueAdmissionOutcome.NotConfigured], the branch is
 * rejected with
 * [StrategyExecutionRejectionReason.DURABLE_REFRESH_NOT_YET_SUPPORTED]
 * exactly as before durable admission wiring existed — nothing here changes
 * observable behavior for a caller that has not configured a
 * [io.dataloom.runtime.submission.QueuedSynchronizationWorkEncoder].
 *
 * A plan that requires reconciliation (`profile.reconcileWhenOnline`, the
 * default — `operations` includes `RECONCILE`) is honored for real via
 * [StrategyReconciliationProvider.reconcileStrategy], mirroring the proven
 * pattern [AcceptedStrategyPlanExecutionCoordinator.finalizeReconciliation]
 * already established for durable-continuation plans. When the storage
 * provider does not implement [StrategyReconciliationProvider], the plan is
 * rejected with
 * [StrategyExecutionRejectionReason.RECONCILIATION_PROVIDER_NOT_CONFIGURED]
 * rather than silently skipping reconciliation. This only applies to the
 * `requireDurableQueue = false` synchronous path — the durable-admission
 * branch above never reaches this reconciliation step; the durably admitted
 * continuation carries its own `RECONCILE` operation, owned entirely by
 * whichever coordinator later replays it.
 */
internal class OfflineFirstStrategyExecutor(
    private val clock: DataLoomClock,
    private val runtimeDependencies: RuntimeDependencies,
    private val pipelineRegistry: SynchronizationPipelineRegistry,
    private val lifecycleEventEmitter: SynchronizationLifecycleEventEmitter?,
    private val durableQueueAdmitter: StrategyDurableQueueAdmitter? = null,
) {
    public suspend fun execute(
        request: StrategySynchronizationRequest,
        evaluation: StrategyEvaluationResult,
        providers: StrategyProviderSet,
    ): StrategySynchronizationExecutionResult {
        val operations = evaluation.plan.operations

        if (StrategyOperation.ENQUEUE_DURABLE_WORK in operations) {
            val admitter = durableQueueAdmitter
                ?: return rejected(evaluation, StrategyExecutionRejectionReason.DURABLE_REFRESH_NOT_YET_SUPPORTED)
            return when (val outcome = admitter.admit(request, evaluation, providers)) {
                is StrategyDurableQueueAdmissionOutcome.NotConfigured -> rejected(
                    evaluation,
                    StrategyExecutionRejectionReason.DURABLE_REFRESH_NOT_YET_SUPPORTED,
                )
                is StrategyDurableQueueAdmissionOutcome.Admitted ->
                    StrategySynchronizationExecutionResult.DurablyEnqueued(
                        evaluation = evaluation,
                        completedAt = clock.now(),
                        queueEntryId = outcome.queueEntryId,
                    )
                is StrategyDurableQueueAdmissionOutcome.Rejected -> rejected(evaluation, outcome.reason)
                is StrategyDurableQueueAdmissionOutcome.Failed -> failed(evaluation, outcome.error)
            }
        }

        if (StrategyOperation.SERVE_LOCAL in operations) {
            when (val served = serveLocal(request, evaluation, providers)) {
                is ServeLocalOutcome.Rejected -> return served.result
                is ServeLocalOutcome.Failed -> return served.result
                is ServeLocalOutcome.Served -> Unit
            }
        }

        val pipeline = requireNotNull(pipelineRegistry.lookup(request.request.direction)) {
            "No SynchronizationPipeline registered for ${request.request.direction}."
        }
        val context = SynchronizationExecutionContext(
            request = request.request,
            providers = ResolvedSynchronizationProviders(
                storageProvider = requireNotNull(providers.storageProvider),
                transportProvider = requireNotNull(providers.transportProvider),
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

        val mapped = mapPipelineResult(evaluation, result)
        return finalizeReconciliation(request, evaluation, providers, operations, mapped)
    }

    private suspend fun serveLocal(
        request: StrategySynchronizationRequest,
        evaluation: StrategyEvaluationResult,
        providers: StrategyProviderSet,
    ): ServeLocalOutcome {
        val fallbackProvider = providers.storageProvider as? StrategyLocalFallbackProvider
            ?: return ServeLocalOutcome.Rejected(
                rejected(
                    evaluation,
                    StrategyExecutionRejectionReason.LOCAL_FALLBACK_PROVIDER_NOT_CONFIGURED,
                ),
            )

        val fallbackRequest = StrategyLocalFallbackRequest(
            request = request.request,
            decisionId = evaluation.decisionId,
            planId = evaluation.plan.id,
            profileId = evaluation.plan.effectiveProfileId,
            configurationVersion = evaluation.plan.configurationVersion,
            remoteOutcome = io.dataloom.api.strategy.StrategyRemoteOutcome.UNKNOWN_FAILURE,
            remoteAttempted = false,
            evaluatedCacheState = request.evidence.cacheState,
        )

        return when (val result = fallbackProvider.evaluateLocalFallback(fallbackRequest)) {
            is ProviderOperationResult.Failure -> ServeLocalOutcome.Failed(
                failed(evaluation, result.error),
            )
            is ProviderOperationResult.Success -> when (result.value) {
                is StrategyLocalFallbackResult.Available -> ServeLocalOutcome.Served
                is StrategyLocalFallbackResult.Unavailable -> ServeLocalOutcome.Failed(
                    failed(
                        evaluation,
                        contractError(
                            code = "DL-STRATEGY-OFFLINE-FIRST-LOCAL-STATE-MISMATCH",
                            message = "Evaluation evidence reported FRESH or STALE cache state " +
                                "but local fallback evaluation found no available local state.",
                        ),
                    ),
                )
            }
        }
    }

    private suspend fun finalizeReconciliation(
        request: StrategySynchronizationRequest,
        evaluation: StrategyEvaluationResult,
        providers: StrategyProviderSet,
        operations: List<StrategyOperation>,
        result: StrategySynchronizationExecutionResult,
    ): StrategySynchronizationExecutionResult {
        if (StrategyOperation.RECONCILE !in operations) return result
        if (result !is StrategySynchronizationExecutionResult.Executed) return result

        val reconciliationProvider = providers.storageProvider as? StrategyReconciliationProvider
            ?: return rejected(
                evaluation,
                StrategyExecutionRejectionReason.RECONCILIATION_PROVIDER_NOT_CONFIGURED,
            )

        val evidence = operations.filterNot { it == StrategyOperation.RECONCILE }
        if (evidence.isEmpty()) return result

        return when (
            val reconciliation = reconciliationProvider.reconcileStrategy(
                StrategyReconciliationRequest(
                    request = request.request,
                    decisionId = evaluation.decisionId,
                    planId = evaluation.plan.id,
                    profileId = evaluation.plan.effectiveProfileId,
                    configurationVersion = evaluation.plan.configurationVersion,
                    completedOperations = evidence,
                ),
            )
        ) {
            is ProviderOperationResult.Success -> result
            is ProviderOperationResult.Failure -> failed(evaluation, reconciliation.error)
        }
    }

    private fun mapPipelineResult(
        evaluation: StrategyEvaluationResult,
        result: SynchronizationResult,
    ): StrategySynchronizationExecutionResult = when (result) {
        is SynchronizationResult.Failed -> failed(evaluation, result.error)
        is SynchronizationResult.Cancelled -> StrategySynchronizationExecutionResult.Cancelled(
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

    private fun failed(
        evaluation: StrategyEvaluationResult,
        error: DataLoomError,
    ): StrategySynchronizationExecutionResult.Failed =
        StrategySynchronizationExecutionResult.Failed(
            evaluation = evaluation,
            completedAt = clock.now(),
            error = error,
            transportAttempted = false,
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

    private fun contractError(code: String, message: String): DataLoomError =
        OfflineFirstContractError(code = ErrorCode(code), message = message)

    private sealed interface ServeLocalOutcome {
        data object Served : ServeLocalOutcome
        data class Rejected(val result: StrategySynchronizationExecutionResult.Rejected) : ServeLocalOutcome
        data class Failed(val result: StrategySynchronizationExecutionResult.Failed) : ServeLocalOutcome
    }

    private data class OfflineFirstContractError(
        override val code: ErrorCode,
        override val message: String,
        override val category: ErrorCategory = ErrorCategory.VALIDATION,
        override val severity: ErrorSeverity = ErrorSeverity.ERROR,
        override val recoverability: Recoverability = Recoverability.NON_RECOVERABLE,
        override val cause: Throwable? = null,
    ) : DataLoomError {
        override fun toString(): String = safeDiagnosticString()
    }
}
