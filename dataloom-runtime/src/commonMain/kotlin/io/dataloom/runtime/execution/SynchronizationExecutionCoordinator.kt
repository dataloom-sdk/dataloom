package io.dataloom.runtime.execution

import io.dataloom.api.model.SynchronizationRequest
import io.dataloom.core.provider.ProviderLifecycleCoordinator
import io.dataloom.core.provider.ProviderLifecycleCoordinatorState
import io.dataloom.core.provider.ProviderResolutionResult
import io.dataloom.core.provider.SynchronizationProviderBindings
import io.dataloom.core.provider.SynchronizationProviderResolver
import io.dataloom.core.runtime.RuntimeDependencies

/**
 * Coordinator responsible for preparing and delegating synchronization
 * pipeline execution.
 *
 * ## Purpose
 *
 * [SynchronizationExecutionCoordinator] connects provider lifecycle
 * initialization, provider resolution, and pipeline selection into a
 * deterministic pre-execution sequence. It delegates the actual
 * synchronization work to the matching [SynchronizationPipeline] and returns
 * the result unchanged.
 *
 * ## Explicit injection
 *
 * All dependencies are required at construction time:
 *
 * - [lifecycleCoordinator]: checked for the
 *   [ProviderLifecycleCoordinatorState.INITIALIZED] state before any other
 *   step.
 * - [providerResolver]: resolves explicit provider bindings.
 * - [pipelineRegistry]: supplies the direction-keyed pipeline set.
 * - [runtimeDependencies]: passed unchanged into the execution context.
 *
 * Construction performs no provider lifecycle operation, no provider
 * operation, no clock read, no identifier generation, and no
 * synchronization work.
 *
 * ## Execution order
 *
 * [execute] follows a strict, deterministic sequence:
 *
 * 1. Check [lifecycleCoordinator] state.
 * 2. If not [ProviderLifecycleCoordinatorState.INITIALIZED], return
 *    [SynchronizationExecutionResult.Rejected] with
 *    [SynchronizationExecutionRejectionReason.PROVIDERS_NOT_INITIALIZED].
 * 3. Resolve provider bindings via [providerResolver].
 * 4. If resolution fails, return
 *    [SynchronizationExecutionResult.Rejected] with
 *    [SynchronizationExecutionRejectionReason.PROVIDER_RESOLUTION_FAILED]
 *    and the ordered binding failures.
 * 5. Look up the pipeline matching [SynchronizationRequest.direction].
 * 6. If no pipeline exists, return
 *    [SynchronizationExecutionResult.Rejected] with
 *    [SynchronizationExecutionRejectionReason.PIPELINE_NOT_FOUND].
 * 7. Construct [SynchronizationExecutionContext].
 * 8. Invoke the selected pipeline exactly once.
 * 9. Return [SynchronizationExecutionResult.Executed] with the exact result.
 *
 * ## Lifecycle precondition
 *
 * Applications must call
 * [ProviderLifecycleCoordinator.initialize] and confirm a successful
 * [io.dataloom.core.provider.ProviderLifecycleResult.InitializeSuccess]
 * before calling [execute]. The coordinator does not initialize providers
 * automatically, retry initialization, or treat any non-INITIALIZED state as
 * initialized.
 *
 * ## Provider resolution
 *
 * The coordinator delegates provider resolution to [providerResolver].
 * It does not repeat resolution logic, look up providers by type, or expose
 * partially resolved provider instances.
 *
 * ## Pipeline selection
 *
 * Direction-based lookup is performed against [pipelineRegistry] using the
 * [SynchronizationRequest.direction] as the key.
 * [io.dataloom.api.model.SynchronizationMode] does not affect selection.
 *
 * ## Cancellation
 *
 * Coroutine cancellation propagates normally. [execute] does not catch
 * [kotlinx.coroutines.CancellationException] and does not convert
 * cancellation to a rejected result or a [SynchronizationExecutionResult].
 *
 * ## Exception boundary
 *
 * Unexpected exceptions from the pipeline propagate to the caller. The
 * coordinator does not catch arbitrary programming errors, assertion failures,
 * or unexpected runtime exceptions, and does not convert them to
 * [io.dataloom.api.synchronization.SynchronizationResult.Failed].
 *
 * ## Concurrency
 *
 * Thread-safety and concurrent execution policy are deferred. Each [execute]
 * call uses a fully local, immutable execution context. The coordinator
 * contains no mutable per-execution state. Applications must provide external
 * synchronization if concurrent execution must be serialized.
 *
 * ## Scope restrictions
 *
 * The coordinator does not expose a [kotlinx.coroutines.CoroutineScope],
 * select a dispatcher, choose a thread, use global state, use a DI framework,
 * read the clock directly, generate identifiers directly, call any provider
 * operation, initialize or shut down providers, call provider health,
 * access storage/transport/scheduler/connectivity/queue providers directly,
 * dispatch events, write checkpoints, or acknowledge changes.
 *
 * ## KMP compatibility
 *
 * Uses Kotlin standard-library and DataLoom API and core types only. Safe for
 * use in Kotlin Multiplatform common code.
 *
 * @param lifecycleCoordinator the [ProviderLifecycleCoordinator] whose state
 *   is checked before each execution.
 * @param providerResolver the [SynchronizationProviderResolver] used to
 *   resolve the explicit provider bindings.
 * @param pipelineRegistry the [SynchronizationPipelineRegistry] that maps
 *   directions to pipelines.
 * @param runtimeDependencies the [RuntimeDependencies] instance passed
 *   unchanged into each [SynchronizationExecutionContext].
 */
public class SynchronizationExecutionCoordinator(
    private val lifecycleCoordinator: ProviderLifecycleCoordinator,
    private val providerResolver: SynchronizationProviderResolver,
    private val pipelineRegistry: SynchronizationPipelineRegistry,
    private val runtimeDependencies: RuntimeDependencies,
) {

    /**
     * Prepares and delegates a synchronization pipeline execution.
     *
     * Follows the deterministic execution order documented on
     * [SynchronizationExecutionCoordinator]:
     *
     * 1. Check provider lifecycle state.
     * 2. Reject with [SynchronizationExecutionRejectionReason.PROVIDERS_NOT_INITIALIZED]
     *    if not initialized.
     * 3. Resolve provider bindings.
     * 4. Reject with [SynchronizationExecutionRejectionReason.PROVIDER_RESOLUTION_FAILED]
     *    if resolution fails.
     * 5. Look up the pipeline for [request] direction.
     * 6. Reject with [SynchronizationExecutionRejectionReason.PIPELINE_NOT_FOUND]
     *    if no pipeline exists.
     * 7. Construct [SynchronizationExecutionContext].
     * 8. Invoke the selected pipeline exactly once.
     * 9. Return [SynchronizationExecutionResult.Executed] with the exact result.
     *
     * Coroutine cancellation propagates normally.
     *
     * @param request the synchronization request to execute.
     * @param bindings the explicit provider bindings to resolve for this request.
     * @return a [SynchronizationExecutionResult] describing the outcome of the
     *   pre-execution sequence and, when not rejected, the pipeline result.
     */
    public suspend fun execute(
        request: SynchronizationRequest,
        bindings: SynchronizationProviderBindings,
    ): SynchronizationExecutionResult {

        // Step 1–2: Lifecycle guard.
        if (lifecycleCoordinator.state != ProviderLifecycleCoordinatorState.INITIALIZED) {
            return SynchronizationExecutionResult.Rejected(
                reason = SynchronizationExecutionRejectionReason.PROVIDERS_NOT_INITIALIZED,
            )
        }

        // Step 3–4: Provider resolution.
        val resolved = when (val resolution = providerResolver.resolve(bindings)) {
            is ProviderResolutionResult.Success -> resolution.providers
            is ProviderResolutionResult.Failure -> {
                return SynchronizationExecutionResult.Rejected(
                    reason = SynchronizationExecutionRejectionReason.PROVIDER_RESOLUTION_FAILED,
                    providerBindingFailures = resolution.bindingFailures,
                )
            }
        }

        // Step 5–6: Pipeline lookup.
        val pipeline = pipelineRegistry.lookup(request.direction)
            ?: return SynchronizationExecutionResult.Rejected(
                reason = SynchronizationExecutionRejectionReason.PIPELINE_NOT_FOUND,
            )

        // Step 7: Construct immutable execution context.
        val context = SynchronizationExecutionContext(
            request = request,
            providers = resolved,
            runtimeDependencies = runtimeDependencies,
        )

        // Step 8–9: Invoke pipeline exactly once and return result unchanged.
        val pipelineResult = pipeline.execute(context)
        return SynchronizationExecutionResult.Executed(result = pipelineResult)
    }
}
