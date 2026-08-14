package io.dataloom.runtime.strategy

import io.dataloom.api.error.DataLoomError
import io.dataloom.api.error.ErrorCategory
import io.dataloom.api.error.ErrorCode
import io.dataloom.api.error.ErrorSeverity
import io.dataloom.api.error.Recoverability
import io.dataloom.api.error.safeDiagnosticString
import io.dataloom.api.execution.StrategyProviderSet
import io.dataloom.api.identifier.QueueEntryId
import io.dataloom.api.model.SynchronizationDirection
import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.api.runtime.RuntimeDependencies
import io.dataloom.api.strategy.HybridStrategyProfile
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
import io.dataloom.api.synchronization.SynchronizationSkipReason
import io.dataloom.api.time.DataLoomClock
import io.dataloom.api.transport.PullChangesRequest
import io.dataloom.core.provider.ResolvedSynchronizationProviders
import io.dataloom.runtime.execution.SynchronizationExecutionContext
import io.dataloom.runtime.execution.SynchronizationPipelineRegistry
import io.dataloom.runtime.execution.lifecycle.SynchronizationLifecycleEventEmitter

/**
 * Executes the finite hybrid branch.
 *
 * [io.dataloom.runtime.strategy.BuiltInSynchronizationStrategyEvaluator]
 * pre-selects exactly one of `HybridSource.LOCAL` or `HybridSource.REMOTE` as
 * the effective source for the whole request from connectivity/cache
 * evidence at evaluation time. Unlike [RemoteFirstStrategyExecutor], this
 * executor never reacts to a runtime remote failure by improvising a
 * fallback — hybrid's evaluated plan never carries a `StrategyFallbackPlan`,
 * so whatever source the evaluator selected is simply attempted; its failure
 * is a plain [StrategySynchronizationExecutionResult.Failed], not a trigger
 * for a second local attempt.
 *
 * ## Scope
 *
 * - `SERVE_LOCAL in operations` (`LOCAL` selected, PULL or BIDIRECTIONAL):
 *   served via [StrategyLocalFallbackProvider], the same pattern
 *   cache-first/offline-first use, with terminal result
 *   [StrategySynchronizationExecutionResult.ServedFromCache] and no refresh
 *   output — hybrid's `LOCAL` branch never also runs a remote leg.
 * - `PUSH_REMOTE`/`PULL_REMOTE` in operations (`REMOTE` selected, any
 *   direction): runs the remote leg, honoring
 *   [HybridStrategyProfile.persistRemoteResult] the same way
 *   [RemoteFirstStrategyExecutor] does — the registered
 *   [io.dataloom.runtime.execution.SynchronizationPipeline] always persists,
 *   so a non-persisting PULL/BIDIRECTIONAL bypasses it for a direct
 *   transport call instead of silently persisting a result the plan never
 *   asked for.
 * - `ENQUEUE_DURABLE_WORK in operations` (`LOCAL` selected as an explicit
 *   fallback from a `REMOTE` primary, with `reconcileAfterFallback = true`,
 *   the default): admitted via [durableQueueAdmitter] when configured.
 *   `RECONCILE` only ever appears alongside `ENQUEUE_DURABLE_WORK` in the
 *   evaluator's hybrid branch — never alone — so admission is the only thing
 *   that needs to happen for it; unlike [OfflineFirstStrategyExecutor], this
 *   executor never calls `StrategyReconciliationProvider` directly — the
 *   durably admitted continuation owns `RECONCILE` entirely. For PULL/
 *   BIDIRECTIONAL (`SERVE_LOCAL` present alongside `ENQUEUE_DURABLE_WORK`),
 *   admission does not replace serving local state — both happen, with the
 *   durable queue entry attached to the terminal
 *   [StrategySynchronizationExecutionResult.ServedFromCache]. For PUSH
 *   (`SERVE_LOCAL` absent — see the next bullet), admission *is* the entire
 *   outcome: returns
 *   [StrategySynchronizationExecutionResult.DurablyEnqueued] immediately.
 *   When [durableQueueAdmitter] is `null` or admission reports
 *   [StrategyDurableQueueAdmissionOutcome.NotConfigured], the branch is
 *   rejected with
 *   [StrategyExecutionRejectionReason.DURABLE_REFRESH_NOT_YET_SUPPORTED]
 *   exactly as before durable admission wiring existed.
 * - A plan whose only operation is `READ_LOCAL` (`LOCAL` selected for a PUSH
 *   direction request with `ENQUEUE_DURABLE_WORK` absent too — nothing to
 *   serve, nothing to durably admit, and `LOCAL` was explicitly chosen over
 *   `REMOTE`, so no transport runs either) returns
 *   [StrategySynchronizationExecutionResult.AcceptedLocally] — accepting
 *   local state genuinely is the entire outcome here, the same `ACCEPT_LOCAL`
 *   no-op meaning [CacheFirstStrategyExecutor]/[OfflineFirstStrategyExecutor]
 *   already use elsewhere.
 */
internal class HybridStrategyExecutor(
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

        var durableQueueEntryId: QueueEntryId? = null
        if (StrategyOperation.ENQUEUE_DURABLE_WORK in operations) {
            val admitter = durableQueueAdmitter
                ?: return rejected(evaluation, StrategyExecutionRejectionReason.DURABLE_REFRESH_NOT_YET_SUPPORTED)
            when (val outcome = admitter.admit(request, evaluation, providers)) {
                is StrategyDurableQueueAdmissionOutcome.NotConfigured -> return rejected(
                    evaluation,
                    StrategyExecutionRejectionReason.DURABLE_REFRESH_NOT_YET_SUPPORTED,
                )
                is StrategyDurableQueueAdmissionOutcome.Admitted -> {
                    if (StrategyOperation.SERVE_LOCAL !in operations) {
                        // PUSH direction: nothing left to run synchronously.
                        return StrategySynchronizationExecutionResult.DurablyEnqueued(
                            evaluation = evaluation,
                            completedAt = clock.now(),
                            queueEntryId = outcome.queueEntryId,
                        )
                    }
                    durableQueueEntryId = outcome.queueEntryId
                }
                is StrategyDurableQueueAdmissionOutcome.Rejected -> return rejected(evaluation, outcome.reason)
                is StrategyDurableQueueAdmissionOutcome.Failed -> return failed(evaluation, outcome.error)
            }
        }

        if (StrategyOperation.SERVE_LOCAL in operations) {
            return when (val served = serveLocal(request, evaluation, providers)) {
                is ServeLocalOutcome.Rejected -> served.result
                is ServeLocalOutcome.Failed -> served.result
                is ServeLocalOutcome.Served ->
                    StrategySynchronizationExecutionResult.ServedFromCache(
                        evaluation = evaluation,
                        completedAt = clock.now(),
                        cacheState = served.cacheState,
                        durableQueueEntryId = durableQueueEntryId,
                    )
            }
        }

        val needsRemote = StrategyOperation.PULL_REMOTE in operations ||
            StrategyOperation.PUSH_REMOTE in operations
        if (!needsRemote) {
            return StrategySynchronizationExecutionResult.AcceptedLocally(
                evaluation = evaluation,
                completedAt = clock.now(),
            )
        }

        val profile = resolvedProfile(request, evaluation) as HybridStrategyProfile
        return when (request.request.direction) {
            SynchronizationDirection.PUSH ->
                executeViaPipeline(request, evaluation, providers, SynchronizationDirection.PUSH)
            SynchronizationDirection.PULL -> if (profile.persistRemoteResult) {
                executeViaPipeline(request, evaluation, providers, SynchronizationDirection.PULL)
            } else {
                executeTransportOnlyPull(request, evaluation, providers)
            }
            SynchronizationDirection.BIDIRECTIONAL -> if (profile.persistRemoteResult) {
                executeViaPipeline(
                    request,
                    evaluation,
                    providers,
                    SynchronizationDirection.BIDIRECTIONAL,
                )
            } else {
                executeNonPersistingBidirectional(request, evaluation, providers)
            }
        }
    }

    private suspend fun executeViaPipeline(
        request: StrategySynchronizationRequest,
        evaluation: StrategyEvaluationResult,
        providers: StrategyProviderSet,
        direction: SynchronizationDirection,
    ): StrategySynchronizationExecutionResult {
        val pipeline = requireNotNull(pipelineRegistry.lookup(direction)) {
            "No SynchronizationPipeline registered for $direction."
        }
        val context = context(request, providers)
        lifecycleEventEmitter?.emitStarted(context)
        val result = pipeline.execute(context)
        lifecycleEventEmitter?.emitCompleted(context, result)
        return mapPipelineResult(evaluation, result)
    }

    private suspend fun executeTransportOnlyPull(
        request: StrategySynchronizationRequest,
        evaluation: StrategyEvaluationResult,
        providers: StrategyProviderSet,
    ): StrategySynchronizationExecutionResult {
        val transport = requireNotNull(providers.transportProvider)
        return when (
            val result = transport.pullChanges(PullChangesRequest(request = request.request))
        ) {
            is ProviderOperationResult.Success ->
                StrategySynchronizationExecutionResult.Executed(
                    evaluation = evaluation,
                    completedAt = clock.now(),
                    output = StrategyTransportOutput.Pulled(result.value),
                )
            is ProviderOperationResult.Failure -> failed(evaluation, result.error)
        }
    }

    private suspend fun executeNonPersistingBidirectional(
        request: StrategySynchronizationRequest,
        evaluation: StrategyEvaluationResult,
        providers: StrategyProviderSet,
    ): StrategySynchronizationExecutionResult {
        val transport = requireNotNull(providers.transportProvider)
        val pushPipeline = requireNotNull(
            pipelineRegistry.lookup(SynchronizationDirection.PUSH),
        )
        val pushResult = pushPipeline.execute(context(request, providers))

        if (!permitsNextOperation(pushResult)) {
            return mapPipelineResult(evaluation, pushResult)
        }

        return when (
            val pulled = transport.pullChanges(PullChangesRequest(request = request.request))
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
            is ProviderOperationResult.Failure -> failed(evaluation, pulled.error)
        }
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
            remoteOutcome = StrategyRemoteOutcome.UNKNOWN_FAILURE,
            remoteAttempted = false,
            evaluatedCacheState = request.evidence.cacheState,
        )

        return when (val result = fallbackProvider.evaluateLocalFallback(fallbackRequest)) {
            is ProviderOperationResult.Failure -> ServeLocalOutcome.Failed(
                failed(evaluation, result.error),
            )
            is ProviderOperationResult.Success -> when (val local = result.value) {
                is StrategyLocalFallbackResult.Available -> ServeLocalOutcome.Served(local.cacheState)
                is StrategyLocalFallbackResult.Unavailable -> ServeLocalOutcome.Failed(
                    failed(
                        evaluation,
                        contractError(
                            code = "DL-STRATEGY-HYBRID-LOCAL-STATE-MISMATCH",
                            message = "Evaluation evidence reported FRESH or STALE cache state " +
                                "but local fallback evaluation found no available local state.",
                        ),
                    ),
                )
            }
        }
    }

    private fun context(
        request: StrategySynchronizationRequest,
        providers: StrategyProviderSet,
    ): SynchronizationExecutionContext =
        SynchronizationExecutionContext(
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

    private fun permitsNextOperation(result: SynchronizationResult): Boolean = when (result) {
        is SynchronizationResult.Succeeded,
        is SynchronizationResult.PartiallySucceeded,
        -> true
        is SynchronizationResult.Skipped ->
            result.reason == SynchronizationSkipReason.NO_CHANGES
        is SynchronizationResult.Failed,
        is SynchronizationResult.Cancelled,
        -> false
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
        HybridContractError(code = ErrorCode(code), message = message)

    private sealed interface ServeLocalOutcome {
        data class Served(val cacheState: StrategyCacheState) : ServeLocalOutcome
        data class Rejected(val result: StrategySynchronizationExecutionResult.Rejected) : ServeLocalOutcome
        data class Failed(val result: StrategySynchronizationExecutionResult.Failed) : ServeLocalOutcome
    }

    private data class HybridContractError(
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
