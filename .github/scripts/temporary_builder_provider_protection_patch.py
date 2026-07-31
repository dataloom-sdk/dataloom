from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected one anchor, found {count}")
    return text.replace(old, new, 1)


# ---------------------------------------------------------------------------
# DataLoom facade property
# ---------------------------------------------------------------------------
path = Path("dataloom-runtime/src/commonMain/kotlin/io/dataloom/runtime/facade/DataLoom.kt")
text = path.read_text()
anchor = '''    public val circuitQueueWorker: DataLoomCircuitQueueWorker?
        get() = null

    /**
     * The optional queue-submission capability.
'''
replacement = '''    public val circuitQueueWorker: DataLoomCircuitQueueWorker?
        get() = null

    /**
     * The optional protected direct-synchronization capability.
     *
     * `null` unless
     * [DataLoomBuilder.providerProtectionConfiguration] was supplied during
     * build. The historical [synchronize] methods remain unchanged; callers
     * select provider timeout/circuit evidence explicitly through this property.
     *
     * A default getter preserves source compatibility for custom pre-V1
     * [DataLoom] implementations.
     */
    public val protectedSynchronization: DataLoomProtectedSynchronization?
        get() = null

    /**
     * The optional queue-submission capability.
'''
text = replace_once(text, anchor, replacement, "DataLoom protected property")
path.write_text(text)


# ---------------------------------------------------------------------------
# DefaultDataLoom immutable capability wiring
# ---------------------------------------------------------------------------
path = Path("dataloom-runtime/src/commonMain/kotlin/io/dataloom/runtime/facade/DefaultDataLoom.kt")
text = path.read_text()
anchor = '''    override val queueWorker: DataLoomQueueWorker?,
    override val circuitQueueWorker: DataLoomCircuitQueueWorker?,
    override val queueSubmission: DataLoomQueueSubmission?,
) : DataLoom {
'''
replacement = '''    override val queueWorker: DataLoomQueueWorker?,
    override val circuitQueueWorker: DataLoomCircuitQueueWorker?,
    override val protectedSynchronization: DataLoomProtectedSynchronization?,
    override val queueSubmission: DataLoomQueueSubmission?,
) : DataLoom {
'''
text = replace_once(text, anchor, replacement, "DefaultDataLoom constructor")
path.write_text(text)


# ---------------------------------------------------------------------------
# DataLoomBuilder imports, state, setter, assembly, and helper
# ---------------------------------------------------------------------------
path = Path("dataloom-runtime/src/commonMain/kotlin/io/dataloom/runtime/facade/DataLoomBuilder.kt")
text = path.read_text()

anchor = '''import io.dataloom.runtime.execution.lifecycle.DispatchingSynchronizationLifecycleEventEmitter
import io.dataloom.runtime.execution.outbound.OutboundPushPipelineConfiguration
'''
replacement = '''import io.dataloom.runtime.execution.lifecycle.DispatchingSynchronizationLifecycleEventEmitter
import io.dataloom.runtime.execution.protection.ProviderProtectedSynchronizationCoordinator
import io.dataloom.runtime.execution.outbound.OutboundPushPipelineConfiguration
'''
text = replace_once(text, anchor, replacement, "Builder coordinator import")

anchor = '''import io.dataloom.runtime.retry.SchedulerCircuitOperation
import io.dataloom.runtime.retry.SynchronizationRetryEvaluator
'''
replacement = '''import io.dataloom.runtime.retry.SchedulerCircuitOperation
import io.dataloom.runtime.retry.StorageCircuitProtectionRuntime
import io.dataloom.runtime.retry.SynchronizationRetryEvaluator
import io.dataloom.runtime.retry.TransportCircuitProtectionRuntime
'''
text = replace_once(text, anchor, replacement, "Builder protection runtime imports")

anchor = '''    private var circuitQueueWorkerSchedulerSpec: DataLoomCircuitQueueWorkerSchedulerSpec? = null
    private var queueSubmissionSpecValue: DataLoomQueueSubmissionSpec? = null
    private var built: Boolean = false
'''
replacement = '''    private var circuitQueueWorkerSchedulerSpec: DataLoomCircuitQueueWorkerSchedulerSpec? = null
    private var queueSubmissionSpecValue: DataLoomQueueSubmissionSpec? = null
    private var providerProtectionSpec: DataLoomProviderProtectionSpec? = null
    private var built: Boolean = false
'''
text = replace_once(text, anchor, replacement, "Builder protection state")

anchor = '''    public fun pipeline(pipeline: SynchronizationPipeline): DataLoomBuilder = apply {
        customPipelineList.add(pipeline)
    }

    /**
     * Configures the optional queue-worker capability.
'''
replacement = '''    public fun pipeline(pipeline: SynchronizationPipeline): DataLoomBuilder = apply {
        customPipelineList.add(pipeline)
    }

    /**
     * Configures the optional protected direct-synchronization capability.
     *
     * Storage and transport protection remain independently configured inside
     * [spec]. Build requires valid default provider bindings and validates every
     * provider- and operation-bearing scope before provider, store, clock,
     * timeout, I/O, identifier, or coroutine activity.
     *
     * The historical [DataLoom.synchronize] path is not redirected. Callers use
     * [DataLoom.protectedSynchronization] explicitly.
     */
    public fun providerProtectionConfiguration(
        spec: DataLoomProviderProtectionSpec,
    ): DataLoomBuilder = apply {
        providerProtectionSpec = spec
    }

    /**
     * Configures the optional queue-worker capability.
'''
text = replace_once(text, anchor, replacement, "Builder protection setter")

anchor = '''        val strategyExecutionCoordinator = StrategySynchronizationExecutionCoordinator(
            lifecycleCoordinator = lifecycleCoordinator,
            evaluator = BuiltInSynchronizationStrategyEvaluator(),
            providerResolver = strategyResolver,
            clock = deps.clock,
            runtimeDependencies = deps,
            pipelineRegistry = buildStrategyPipelineRegistry(),
            lifecycleEventEmitter = lifecycleEventEmitter,
        )

        // --- 9. Build optional queue worker ---
'''
replacement = '''        val strategyExecutionCoordinator = StrategySynchronizationExecutionCoordinator(
            lifecycleCoordinator = lifecycleCoordinator,
            evaluator = BuiltInSynchronizationStrategyEvaluator(),
            providerResolver = strategyResolver,
            clock = deps.clock,
            runtimeDependencies = deps,
            pipelineRegistry = buildStrategyPipelineRegistry(),
            lifecycleEventEmitter = lifecycleEventEmitter,
        )

        // --- 9. Build optional provider-protected direct synchronization ---
        val protectedSynchronization = providerProtectionSpec?.let { spec ->
            val legacyBindings = bindings
                ?: throw DataLoomBuildException(
                    "DataLoomBuilder providerProtectionConfiguration requires " +
                        "defaultProviderBindings.",
                )
            buildProtectedSynchronization(
                spec = spec,
                resolver = resolver,
                bindings = legacyBindings,
                lifecycleCoordinator = lifecycleCoordinator,
                pipelineRegistry = finalPipelineRegistry,
                deps = deps,
                lifecycleEventEmitter = lifecycleEventEmitter,
                connectivityConfiguration = effectiveConnectivityConfiguration,
                connectivityPreflight = connectivityPreflight,
            )
        }

        // --- 10. Build optional queue worker ---
'''
text = replace_once(text, anchor, replacement, "Builder protection assembly")

text = text.replace("        // --- 10. Build optional circuit-aware queue worker ---", "        // --- 11. Build optional circuit-aware queue worker ---", 1)
text = text.replace("        // --- 11. Build optional queue submission ---", "        // --- 12. Build optional queue submission ---", 1)

anchor = '''            defaultStrategyBindings = strategyBindings,
            queueWorker = queueWorker,
            circuitQueueWorker = circuitQueueWorker,
            queueSubmission = queueSubmission,
        )
'''
replacement = '''            defaultStrategyBindings = strategyBindings,
            queueWorker = queueWorker,
            circuitQueueWorker = circuitQueueWorker,
            protectedSynchronization = protectedSynchronization,
            queueSubmission = queueSubmission,
        )
'''
text = replace_once(text, anchor, replacement, "Builder DefaultDataLoom wiring")

anchor = '''    /**
     * Assembles the queue-worker runtime components.
'''
helper = '''    /**
     * Assembles protected direct synchronization from the same lifecycle,
     * resolver, pipeline registry, connectivity, runtime, and event components
     * as direct synchronization.
     */
    private fun buildProtectedSynchronization(
        spec: DataLoomProviderProtectionSpec,
        resolver: SynchronizationProviderResolver,
        bindings: SynchronizationProviderBindings,
        lifecycleCoordinator: ProviderLifecycleCoordinator,
        pipelineRegistry: SynchronizationPipelineRegistry,
        deps: RuntimeDependencies,
        lifecycleEventEmitter: io.dataloom.runtime.execution.lifecycle.SynchronizationLifecycleEventEmitter?,
        connectivityConfiguration: SynchronizationConnectivityConfiguration,
        connectivityPreflight: SynchronizationConnectivityPreflight,
    ): DataLoomProtectedSynchronization {
        val resolved = when (val resolution = resolver.resolve(bindings)) {
            is ProviderResolutionResult.Success -> resolution.providers
            is ProviderResolutionResult.Failure -> throw DataLoomBuildException(
                "DataLoomBuilder providerProtectionConfiguration requires valid default provider bindings.",
            )
        }

        val storageOperations = try {
            StorageCircuitProtectionRuntime.create(
                storageProvider = resolved.storageProvider,
                clock = deps.clock,
                circuitBreakerConfiguration = spec.storage.circuitBreakerConfiguration,
                circuitBreakerStateStore = spec.storage.circuitBreakerStateStore,
                scopes = spec.storage.scopes,
                providerTimeout = spec.storage.providerTimeout,
                failureClassifier = spec.storage.failureClassifier,
            )
        } catch (_: IllegalArgumentException) {
            throw DataLoomBuildException(
                "DataLoomBuilder providerProtectionConfiguration storage scopes must match " +
                    "the default storage provider and exact storage operations.",
            )
        }

        val transportOperations = try {
            TransportCircuitProtectionRuntime.create(
                transportProvider = resolved.transportProvider,
                clock = deps.clock,
                circuitBreakerConfiguration = spec.transport.circuitBreakerConfiguration,
                circuitBreakerStateStore = spec.transport.circuitBreakerStateStore,
                scopes = spec.transport.scopes,
                providerTimeout = spec.transport.providerTimeout,
                failureClassifier = spec.transport.failureClassifier,
            )
        } catch (_: IllegalArgumentException) {
            throw DataLoomBuildException(
                "DataLoomBuilder providerProtectionConfiguration transport scopes must match " +
                    "the default transport provider and exact transport operations.",
            )
        }

        val coordinator = ProviderProtectedSynchronizationCoordinator(
            lifecycleCoordinator = lifecycleCoordinator,
            providerResolver = resolver,
            pipelineRegistry = pipelineRegistry,
            runtimeDependencies = deps,
            storageOperations = storageOperations,
            transportOperations = transportOperations,
            lifecycleEventEmitter = lifecycleEventEmitter,
            connectivityConfiguration = connectivityConfiguration,
            connectivityPreflight = connectivityPreflight,
        )
        return DefaultDataLoomProtectedSynchronization(
            coordinator = coordinator,
            defaultBindings = bindings,
        )
    }

    /**
     * Assembles the queue-worker runtime components.
'''
text = replace_once(text, anchor, helper, "Builder protection helper")

path.write_text(text)
