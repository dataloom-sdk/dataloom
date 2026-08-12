package io.dataloom.runtime.strategy

import io.dataloom.api.error.DataLoomError
import io.dataloom.api.error.ErrorCategory
import io.dataloom.api.error.ErrorCode
import io.dataloom.api.error.ErrorSeverity
import io.dataloom.api.error.Recoverability
import io.dataloom.api.error.safeDiagnosticString
import io.dataloom.api.execution.StrategyProviderSet
import io.dataloom.api.identifier.QueueEntryId
import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.api.runtime.RuntimeDependencies
import io.dataloom.api.strategy.StrategyCacheState
import io.dataloom.api.strategy.StrategyEvaluationResult
import io.dataloom.api.strategy.StrategyLocalFallbackProvider
import io.dataloom.api.strategy.StrategyLocalFallbackRequest
import io.dataloom.api.strategy.StrategyLocalFallbackResult
import io.dataloom.api.strategy.StrategyOperation
import io.dataloom.api.strategy.StrategySynchronizationRequest
import io.dataloom.api.strategy.StrategyTransportOutput
import io.dataloom.api.synchronization.SynchronizationResult
import io.dataloom.api.time.DataLoomClock
import io.dataloom.core.provider.ResolvedSynchronizationProviders
import io.dataloom.runtime.execution.SynchronizationExecutionContext
import io.dataloom.runtime.execution.SynchronizationPipelineRegistry
import io.dataloom.runtime.execution.lifecycle.SynchronizationLifecycleEventEmitter

/**
 * Executes the cache-first branch: serve local cache state as the primary
 * outcome, optionally paired with a synchronous, non-durable remote refresh.
 *
 * ## Scope
 *
 * Only the branches [io.dataloom.runtime.strategy.BuiltInSynchronizationStrategyEvaluator]
 * produces for [io.dataloom.api.strategy.CacheFirstStrategyProfile] that are
 * directly, synchronously executable are handled here:
 *
 * - FRESH/STALE cache served with no refresh (`operations = [SERVE_LOCAL]`).
 * - FRESH/STALE cache served with a synchronous refresh
 *   (`requireDurableRefresh = false`, `operations` include `SERVE_LOCAL` plus
 *   `PULL_REMOTE`/`PERSIST_REMOTE`).
 * - MISSING cache with connectivity available — a pure remote fetch, no
 *   local serve (`operations = remoteOperations(direction, persistRemote =
 *   true)`, no `SERVE_LOCAL`).
 * - PUSH with connectivity available (`operations = [READ_LOCAL,
 *   PUSH_REMOTE]`).
 *
 * A plan that requires durable queue admission for a scheduled refresh
 * (`requireDurableRefresh = true`, the default — `operations` includes
 * `ENQUEUE_DURABLE_WORK`/`SCHEDULE_REFRESH`) never also includes
 * `PULL_REMOTE`/`PERSIST_REMOTE` alongside it — the durable refresh
 * replaces, rather than joins, the synchronous refresh — so admitting it
 * durably and still serving local state (`SERVE_LOCAL` is always present in
 * this branch) requires no special-casing beyond the admission call itself.
 * When [durableQueueAdmitter] is `null` or admission reports
 * [StrategyDurableQueueAdmissionOutcome.NotConfigured], the branch is
 * rejected with [StrategyExecutionRejectionReason.DURABLE_REFRESH_NOT_YET_SUPPORTED]
 * exactly as before durable admission wiring existed.
 */
internal class CacheFirstStrategyExecutor(
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
                is StrategyDurableQueueAdmissionOutcome.Admitted -> durableQueueEntryId = outcome.queueEntryId
                is StrategyDurableQueueAdmissionOutcome.Rejected -> return rejected(evaluation, outcome.reason)
                is StrategyDurableQueueAdmissionOutcome.Failed -> return failed(evaluation, outcome.error)
            }
        }

        val servesLocal = StrategyOperation.SERVE_LOCAL in operations
        val cacheState = if (servesLocal) {
            when (val served = serveLocal(request, evaluation, providers)) {
                is ServeLocalOutcome.Rejected -> return served.result
                is ServeLocalOutcome.Failed -> return served.result
                is ServeLocalOutcome.Served -> served.cacheState
            }
        } else {
            null
        }

        val needsRemote = StrategyOperation.PULL_REMOTE in operations ||
            StrategyOperation.PUSH_REMOTE in operations
        if (!needsRemote) {
            return StrategySynchronizationExecutionResult.ServedFromCache(
                evaluation = evaluation,
                completedAt = clock.now(),
                cacheState = requireNotNull(cacheState) {
                    "Cache-first plan without SERVE_LOCAL or a remote operation is unreachable."
                },
                durableQueueEntryId = durableQueueEntryId,
            )
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

        return mapPipelineResult(evaluation, cacheState, durableQueueEntryId, result)
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
            is ProviderOperationResult.Success -> when (val local = result.value) {
                is StrategyLocalFallbackResult.Available -> ServeLocalOutcome.Served(local.cacheState)
                is StrategyLocalFallbackResult.Unavailable -> ServeLocalOutcome.Failed(
                    failed(
                        evaluation,
                        contractError(
                            code = "DL-STRATEGY-CACHE-FIRST-LOCAL-STATE-MISMATCH",
                            message = "Evaluation evidence reported FRESH or STALE cache state " +
                                "but local fallback evaluation found no available local state.",
                        ),
                    ),
                )
            }
        }
    }

    private fun mapPipelineResult(
        evaluation: StrategyEvaluationResult,
        cacheState: StrategyCacheState?,
        durableQueueEntryId: QueueEntryId?,
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
        -> if (cacheState != null) {
            StrategySynchronizationExecutionResult.ServedFromCache(
                evaluation = evaluation,
                completedAt = clock.now(),
                cacheState = cacheState,
                refreshOutput = StrategyTransportOutput.ProviderBacked(result),
                durableQueueEntryId = durableQueueEntryId,
            )
        } else {
            StrategySynchronizationExecutionResult.Executed(
                evaluation = evaluation,
                completedAt = clock.now(),
                output = StrategyTransportOutput.ProviderBacked(result),
            )
        }
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
        CacheFirstContractError(code = ErrorCode(code), message = message)

    private sealed interface ServeLocalOutcome {
        data class Served(val cacheState: StrategyCacheState) : ServeLocalOutcome
        data class Rejected(val result: StrategySynchronizationExecutionResult.Rejected) : ServeLocalOutcome
        data class Failed(val result: StrategySynchronizationExecutionResult.Failed) : ServeLocalOutcome
    }

    private data class CacheFirstContractError(
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
