package io.dataloom.runtime.facade

import io.dataloom.api.observation.SynchronizationObserver
import io.dataloom.api.provider.DataLoomProvider
import io.dataloom.api.provider.ProviderInitializationContext
import io.dataloom.api.provider.ProviderType
import io.dataloom.api.provider.QueueProvider
import io.dataloom.api.scheduling.SchedulerProvider
import io.dataloom.core.provider.ProviderBindingFailureReason
import io.dataloom.core.provider.ProviderLifecycleCoordinator
import io.dataloom.core.provider.ProviderRegistry
import io.dataloom.core.provider.ProviderResolutionResult
import io.dataloom.core.provider.SynchronizationProviderBindings
import io.dataloom.core.provider.SynchronizationProviderResolver
import io.dataloom.core.runtime.RuntimeDependencies
import io.dataloom.runtime.connectivity.SynchronizationConnectivityConfiguration
import io.dataloom.runtime.connectivity.SynchronizationConnectivityPreflight
import io.dataloom.runtime.execution.SynchronizationExecutionCoordinator
import io.dataloom.runtime.execution.SynchronizationPipeline
import io.dataloom.runtime.execution.SynchronizationPipelineRegistry
import io.dataloom.runtime.execution.bidirectional.BidirectionalPipelineConfiguration
import io.dataloom.runtime.execution.bidirectional.BidirectionalSynchronizationPipeline
import io.dataloom.runtime.execution.inbound.InboundPullPipelineConfiguration
import io.dataloom.runtime.execution.inbound.InboundPullSynchronizationPipeline
import io.dataloom.runtime.execution.lifecycle.DispatchingSynchronizationLifecycleEventEmitter
import io.dataloom.runtime.execution.outbound.OutboundPushPipelineConfiguration
import io.dataloom.runtime.execution.outbound.OutboundPushSynchronizationPipeline
import io.dataloom.runtime.observation.SynchronizationEventDispatcher
import io.dataloom.runtime.observation.SynchronizationObserverRegistry
import io.dataloom.runtime.queue.DurableQueueExecutionProcessor
import io.dataloom.runtime.queue.QueuedSynchronizationExecutionHandler
import io.dataloom.runtime.retry.SynchronizationRetryEvaluator
import io.dataloom.runtime.worker.QueueWorkerCoordinator

/**
 * Builder that assembles a [DataLoom] runtime instance from explicit
 * application-supplied configuration and provider instances.
 *
 * ## Single-use
 *
 * [DataLoomBuilder] is single-use. Calling [build] a second time throws
 * [DataLoomBuildException]. Construct a new builder instance to build a new
 * runtime.
 *
 * ## Thread safety
 *
 * [DataLoomBuilder] is not thread-safe. Callers must not concurrently
 * configure or build from multiple threads.
 *
 * ## Build-time restrictions
 *
 * [build] performs only bounded structural assembly. It does not:
 *
 * - Initialize, shut down, or health-check any provider.
 * - Execute synchronization.
 * - Read the clock.
 * - Generate identifiers.
 * - Enqueue work.
 * - Launch coroutines.
 * - Start background workers.
 * - Use reflection, ServiceLoader, or a DI framework.
 * - Use Android or JVM-only APIs.
 *
 * ## Required configuration
 *
 * [build] throws [DataLoomBuildException] when:
 *
 * - [runtimeDependencies] has not been called.
 * - No provider has been added.
 * - [defaultProviderBindings] has not been called.
 * - The storage binding references a missing or mistyped provider.
 * - The transport binding references a missing or mistyped provider.
 * - [queueWorkerConfiguration] was called but the queue binding is missing or
 *   invalid.
 *
 * Duplicate provider IDs throw [IllegalArgumentException] from
 * [ProviderRegistry]. Duplicate observer IDs throw [IllegalArgumentException]
 * from [SynchronizationObserverRegistry]. Duplicate pipeline directions throw
 * [IllegalArgumentException] from [SynchronizationPipelineRegistry].
 *
 * ## KMP compatibility
 *
 * Uses Kotlin standard-library and DataLoom API types only. Safe for use in
 * Kotlin Multiplatform common code.
 *
 * ## Example
 *
 * ```kotlin
 * val dataLoom = DataLoomBuilder()
 *     .runtimeDependencies(runtimeDependencies)
 *     .providers(storageProvider, transportProvider)
 *     .defaultProviderBindings(bindings)
 *     .build()
 *
 * dataLoom.initialize()
 * val result = dataLoom.synchronize(request)
 * dataLoom.shutdown()
 * ```
 */
public class DataLoomBuilder {

    private var runtimeDependencies: RuntimeDependencies? = null
    private val providerList: MutableList<DataLoomProvider> = mutableListOf()
    private var defaultProviderBindings: SynchronizationProviderBindings? = null
    private var outboundConfiguration: OutboundPushPipelineConfiguration? = null
    private var inboundConfiguration: InboundPullPipelineConfiguration? = null
    private var bidirectionalConfiguration: BidirectionalPipelineConfiguration? = null
    private var connectivityConfiguration: SynchronizationConnectivityConfiguration? = null
    private val observerList: MutableList<SynchronizationObserver> = mutableListOf()
    private val customPipelineList: MutableList<SynchronizationPipeline> = mutableListOf()
    private var queueWorkerSpec: DataLoomQueueWorkerSpec? = null
    private var built: Boolean = false

    // =========================================================================
    // Builder configuration methods
    // =========================================================================

    /**
     * Sets the mandatory [RuntimeDependencies] used by the runtime.
     *
     * The supplied [deps] provide the wall-clock source and identifier
     * generators required by the runtime components. Construction of the
     * builder does not read the clock or generate any identifier.
     *
     * @param deps the runtime dependencies. Required.
     * @return this builder for chaining.
     */
    public fun runtimeDependencies(deps: RuntimeDependencies): DataLoomBuilder = apply {
        runtimeDependencies = deps
    }

    /**
     * Adds the supplied [providers] to the provider collection.
     *
     * Providers are registered in the order they are supplied. Lifecycle
     * operations are performed in registration order. Duplicate
     * [io.dataloom.api.provider.ProviderId] values are detected and rejected
     * during [build] by [ProviderRegistry].
     *
     * The supplied collection is defensively copied; subsequent mutations to
     * the original have no effect on the builder.
     *
     * @param providers the providers to register. At least one provider must
     *   be added before calling [build].
     * @return this builder for chaining.
     */
    public fun providers(vararg providers: DataLoomProvider): DataLoomBuilder = apply {
        providerList.addAll(providers)
    }

    /**
     * Adds a single [provider] to the provider collection.
     *
     * @param provider the provider to register.
     * @return this builder for chaining.
     * @see [providers]
     */
    public fun provider(provider: DataLoomProvider): DataLoomBuilder = apply {
        providerList.add(provider)
    }

    /**
     * Sets the mandatory default [SynchronizationProviderBindings].
     *
     * Default bindings are used by [DataLoom.synchronize] when no explicit
     * bindings are supplied. They are structurally validated during [build]
     * to confirm that every bound provider ID refers to a registered provider
     * of the correct type and interface. Validation performs no provider
     * lifecycle or operation call.
     *
     * @param bindings the default provider bindings. Required.
     * @return this builder for chaining.
     */
    public fun defaultProviderBindings(bindings: SynchronizationProviderBindings): DataLoomBuilder =
        apply {
            defaultProviderBindings = bindings
        }

    /**
     * Overrides the outbound push pipeline configuration used when no custom
     * [io.dataloom.api.model.SynchronizationDirection.PUSH] pipeline is
     * supplied via [pipeline].
     *
     * When this method is not called, [OutboundPushPipelineConfiguration]
     * default values are used.
     *
     * @param config the outbound pipeline configuration.
     * @return this builder for chaining.
     */
    public fun outboundConfiguration(config: OutboundPushPipelineConfiguration): DataLoomBuilder =
        apply {
            outboundConfiguration = config
        }

    /**
     * Overrides the inbound pull pipeline configuration used when no custom
     * [io.dataloom.api.model.SynchronizationDirection.PULL] pipeline is
     * supplied via [pipeline].
     *
     * When this method is not called, [InboundPullPipelineConfiguration]
     * default values are used.
     *
     * @param config the inbound pipeline configuration.
     * @return this builder for chaining.
     */
    public fun inboundConfiguration(config: InboundPullPipelineConfiguration): DataLoomBuilder =
        apply {
            inboundConfiguration = config
        }

    /**
     * Overrides the bidirectional pipeline configuration used when no custom
     * [io.dataloom.api.model.SynchronizationDirection.BIDIRECTIONAL] pipeline
     * is supplied via [pipeline].
     *
     * When this method is not called, [BidirectionalPipelineConfiguration]
     * default values are used.
     *
     * @param config the bidirectional pipeline configuration.
     * @return this builder for chaining.
     */
    public fun bidirectionalConfiguration(config: BidirectionalPipelineConfiguration): DataLoomBuilder =
        apply {
            bidirectionalConfiguration = config
        }

    /**
     * Sets the connectivity configuration that controls the preflight check
     * performed before each synchronization execution.
     *
     * When not set, [SynchronizationConnectivityConfiguration.NONE] is used,
     * meaning no connectivity check is performed.
     *
     * @param config the connectivity configuration.
     * @return this builder for chaining.
     */
    public fun connectivityConfiguration(config: SynchronizationConnectivityConfiguration): DataLoomBuilder =
        apply {
            connectivityConfiguration = config
        }

    /**
     * Adds the supplied [observers] to the observer collection.
     *
     * Observers receive synchronization lifecycle events in registration order.
     * Duplicate [io.dataloom.api.identifier.SynchronizationObserverId] values
     * are detected and rejected during [build] by [SynchronizationObserverRegistry].
     *
     * The supplied collection is defensively copied; subsequent mutations to
     * the original have no effect on the builder.
     *
     * @param observers the observers to register.
     * @return this builder for chaining.
     */
    public fun observers(vararg observers: SynchronizationObserver): DataLoomBuilder = apply {
        observerList.addAll(observers)
    }

    /**
     * Adds a single [observer] to the observer collection.
     *
     * @param observer the observer to register.
     * @return this builder for chaining.
     * @see [observers]
     */
    public fun observer(observer: SynchronizationObserver): DataLoomBuilder = apply {
        observerList.add(observer)
    }

    /**
     * Registers a custom [pipeline] for its declared direction.
     *
     * A custom pipeline replaces the default built-in pipeline for that
     * direction only. Other directions continue to use their defaults.
     * Duplicate directions are detected and rejected during [build] by
     * [SynchronizationPipelineRegistry].
     *
     * @param pipeline the custom pipeline to register.
     * @return this builder for chaining.
     */
    public fun pipeline(pipeline: SynchronizationPipeline): DataLoomBuilder = apply {
        customPipelineList.add(pipeline)
    }

    /**
     * Configures the optional queue-worker capability.
     *
     * When supplied with valid configuration and a valid queue provider
     * binding in [defaultProviderBindings], [DataLoom.queueWorker] will be
     * non-null after [build]. Otherwise [DataLoom.queueWorker] is `null`.
     *
     * [build] throws [DataLoomBuildException] when this method has been called
     * but the queue provider binding is absent or refers to an invalid
     * provider.
     *
     * @param spec the queue-worker specification. Required to enable the queue
     *   worker.
     * @return this builder for chaining.
     */
    public fun queueWorkerConfiguration(spec: DataLoomQueueWorkerSpec): DataLoomBuilder = apply {
        queueWorkerSpec = spec
    }

    // =========================================================================
    // Build
    // =========================================================================

    /**
     * Assembles and returns a fully configured [DataLoom] instance.
     *
     * This method performs only bounded structural assembly. It does not
     * initialize providers, execute synchronization, read the clock, generate
     * identifiers, enqueue work, launch coroutines, or start background workers.
     *
     * Validation failures throw [DataLoomBuildException]. Duplicate provider
     * IDs throw [IllegalArgumentException] from [ProviderRegistry]. Duplicate
     * observer IDs throw [IllegalArgumentException] from
     * [SynchronizationObserverRegistry]. Duplicate pipeline directions throw
     * [IllegalArgumentException] from [SynchronizationPipelineRegistry].
     *
     * This builder is single-use. Calling [build] a second time throws
     * [DataLoomBuildException].
     *
     * @return a configured [DataLoom] ready for [DataLoom.initialize].
     * @throws DataLoomBuildException when mandatory configuration is missing or
     *   structurally invalid.
     * @throws IllegalArgumentException when duplicate provider IDs, observer
     *   IDs, or pipeline directions are detected.
     */
    public fun build(): DataLoom {
        checkNotBuilt()
        built = true

        // --- 1. Validate mandatory fields ---
        val deps = runtimeDependencies
            ?: throw DataLoomBuildException(
                "DataLoomBuilder requires runtimeDependencies. Call runtimeDependencies(...) before build().",
            )

        if (providerList.isEmpty()) {
            throw DataLoomBuildException(
                "DataLoomBuilder requires at least one provider. Call providers(...) or provider(...) before build().",
            )
        }

        val bindings = defaultProviderBindings
            ?: throw DataLoomBuildException(
                "DataLoomBuilder requires defaultProviderBindings. Call defaultProviderBindings(...) before build().",
            )

        // --- 2. Build ProviderRegistry (throws IllegalArgumentException for duplicate IDs) ---
        val registry = ProviderRegistry(providerList.toList())

        // --- 3. Validate default bindings structurally ---
        val resolver = SynchronizationProviderResolver(registry)
        val resolutionResult = resolver.resolve(bindings)
        if (resolutionResult is ProviderResolutionResult.Failure) {
            val failureDescription = resolutionResult.bindingFailures.joinToString("; ") { failure ->
                val reason = failure.reason
                val id = failure.requestedId.value
                val expected = failure.expectedType
                val actual = failure.actualType
                when (reason) {
                    ProviderBindingFailureReason.PROVIDER_NOT_FOUND ->
                        "provider '$id' (expected $expected) not found in registry"
                    ProviderBindingFailureReason.PROVIDER_TYPE_MISMATCH ->
                        "provider '$id' has type $actual but expected $expected"
                    ProviderBindingFailureReason.PROVIDER_CONTRACT_MISMATCH ->
                        "provider '$id' does not implement the required contract for $expected"
                }
            }
            throw DataLoomBuildException(
                "DataLoomBuilder default provider binding validation failed: $failureDescription",
            )
        }

        // --- 4. Build observer infrastructure (optional) ---
        val lifecycleEventEmitter = if (observerList.isNotEmpty()) {
            // SynchronizationObserverRegistry throws IllegalArgumentException for duplicate IDs.
            val observerRegistry = SynchronizationObserverRegistry(observerList.toList())
            val dispatcher = SynchronizationEventDispatcher(observerRegistry)
            DispatchingSynchronizationLifecycleEventEmitter(
                dispatcher = dispatcher,
                clock = deps.clock,
                eventIdGenerator = deps.identifiers.synchronizationEventIds,
            )
        } else {
            null
        }

        // --- 5. Build pipeline registry with defaults and custom overrides ---
        val finalPipelineRegistry = buildPipelineRegistry()

        // --- 6. Build connectivity (use NONE by default) ---
        val effectiveConnectivityConfiguration =
            connectivityConfiguration ?: SynchronizationConnectivityConfiguration.NONE
        val connectivityPreflight = SynchronizationConnectivityPreflight()

        // --- 7. Build ProviderLifecycleCoordinator ---
        val lifecycleCoordinator = ProviderLifecycleCoordinator(
            registry = registry,
            context = ProviderInitializationContext(),
        )

        // --- 8. Build SynchronizationExecutionCoordinator ---
        val executionCoordinator = SynchronizationExecutionCoordinator(
            lifecycleCoordinator = lifecycleCoordinator,
            providerResolver = resolver,
            pipelineRegistry = finalPipelineRegistry,
            runtimeDependencies = deps,
            lifecycleEventEmitter = lifecycleEventEmitter,
            connectivityConfiguration = effectiveConnectivityConfiguration,
            connectivityPreflight = connectivityPreflight,
        )

        // --- 9. Build optional queue worker ---
        val queueWorker = queueWorkerSpec?.let { spec ->
            buildQueueWorker(
                spec = spec,
                registry = registry,
                bindings = bindings,
                deps = deps,
                executionCoordinator = executionCoordinator,
            )
        }

        return DefaultDataLoom(
            lifecycleCoordinator = lifecycleCoordinator,
            executionCoordinator = executionCoordinator,
            defaultBindings = bindings,
            queueWorker = queueWorker,
        )
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

    private fun checkNotBuilt() {
        if (built) {
            throw DataLoomBuildException(
                "DataLoomBuilder is single-use. build() has already been called. " +
                    "Create a new DataLoomBuilder instance to build a new runtime.",
            )
        }
    }

    /**
     * Builds the [SynchronizationPipelineRegistry] from custom pipelines and
     * default pipelines for any uncovered direction.
     *
     * Custom pipelines take precedence over defaults for the directions they
     * cover. Defaults are assembled for every direction not covered by a custom
     * pipeline. The bidirectional default is composed from the final outbound
     * and inbound pipelines (custom or default).
     *
     * [SynchronizationPipelineRegistry] throws [IllegalArgumentException] for
     * duplicate directions.
     */
    private fun buildPipelineRegistry(): SynchronizationPipelineRegistry {
        val customCopy = customPipelineList.toList()

        val customDirections = customCopy.map { it.direction }.toSet()

        // Determine which default pipelines need to be assembled.
        val effectiveOutbound: SynchronizationPipeline =
            customCopy.firstOrNull { it.direction == io.dataloom.api.model.SynchronizationDirection.PUSH }
                ?: OutboundPushSynchronizationPipeline(
                    outboundConfiguration ?: OutboundPushPipelineConfiguration(),
                )

        val effectiveInbound: SynchronizationPipeline =
            customCopy.firstOrNull { it.direction == io.dataloom.api.model.SynchronizationDirection.PULL }
                ?: InboundPullSynchronizationPipeline(
                    inboundConfiguration ?: InboundPullPipelineConfiguration(),
                )

        val effectiveBidirectional: SynchronizationPipeline =
            customCopy.firstOrNull { it.direction == io.dataloom.api.model.SynchronizationDirection.BIDIRECTIONAL }
                ?: BidirectionalSynchronizationPipeline(
                    outboundPipeline = effectiveOutbound,
                    inboundPipeline = effectiveInbound,
                    configuration = bidirectionalConfiguration ?: BidirectionalPipelineConfiguration(),
                )

        // Collect all pipelines. Custom pipelines are listed first; then any
        // defaults for directions not covered by a custom pipeline.
        val pipelines = mutableListOf<SynchronizationPipeline>()
        pipelines.addAll(customCopy)
        if (io.dataloom.api.model.SynchronizationDirection.PUSH !in customDirections) {
            pipelines.add(effectiveOutbound)
        }
        if (io.dataloom.api.model.SynchronizationDirection.PULL !in customDirections) {
            pipelines.add(effectiveInbound)
        }
        if (io.dataloom.api.model.SynchronizationDirection.BIDIRECTIONAL !in customDirections) {
            pipelines.add(effectiveBidirectional)
        }

        // SynchronizationPipelineRegistry throws IllegalArgumentException for duplicate directions.
        return SynchronizationPipelineRegistry(pipelines)
    }

    /**
     * Assembles the queue-worker runtime components.
     *
     * Validates that the default bindings contain a valid QueueProvider ID
     * that refers to a registered [QueueProvider]. Throws
     * [DataLoomBuildException] when this requirement is not satisfied.
     *
     * The optional [SchedulerProvider] for the coordinator is resolved from
     * the default bindings when a scheduler ID is configured.
     */
    private fun buildQueueWorker(
        spec: DataLoomQueueWorkerSpec,
        registry: ProviderRegistry,
        bindings: SynchronizationProviderBindings,
        deps: RuntimeDependencies,
        executionCoordinator: SynchronizationExecutionCoordinator,
    ): DataLoomQueueWorker {
        // Validate queue provider binding.
        val queueProviderId = bindings.queueProviderId
            ?: throw DataLoomBuildException(
                "DataLoomBuilder queueWorkerConfiguration requires a queue provider binding. " +
                    "Set queueProviderId on the defaultProviderBindings before build().",
            )

        val queueProviderCandidate = registry.findById(queueProviderId)
            ?: throw DataLoomBuildException(
                "DataLoomBuilder queueWorkerConfiguration: queue provider '${queueProviderId.value}' " +
                    "not found in registry.",
            )

        if (queueProviderCandidate.descriptor.type != ProviderType.QUEUE) {
            throw DataLoomBuildException(
                "DataLoomBuilder queueWorkerConfiguration: provider '${queueProviderId.value}' " +
                    "has type ${queueProviderCandidate.descriptor.type} but expected ${ProviderType.QUEUE}.",
            )
        }

        val queueProvider = queueProviderCandidate as? QueueProvider
            ?: throw DataLoomBuildException(
                "DataLoomBuilder queueWorkerConfiguration: provider '${queueProviderId.value}' " +
                    "does not implement the QueueProvider contract.",
            )

        // Optionally resolve scheduler provider from default bindings.
        val schedulerProvider: SchedulerProvider? = bindings.schedulerProviderId?.let { schedulerId ->
            val candidate = registry.findById(schedulerId)
            if (candidate != null && candidate.descriptor.type == ProviderType.SCHEDULER && candidate is SchedulerProvider) {
                candidate
            } else {
                null
            }
        }

        // Assemble queue-worker components.
        val retryEvaluator = SynchronizationRetryEvaluator(
            retryPolicy = spec.retryPolicy,
            clock = deps.clock,
        )

        val executionHandler = QueuedSynchronizationExecutionHandler(
            workResolver = spec.workResolver,
            executionCoordinator = executionCoordinator,
            retryEvaluator = retryEvaluator,
            retryOperation = spec.retryOperation,
            connectivityConfiguration = connectivityConfiguration,
            clock = if (connectivityConfiguration != null) deps.clock else null,
        )

        val queueProcessor = DurableQueueExecutionProcessor(
            queueProvider = queueProvider,
            executionHandler = executionHandler,
        )

        val coordinator = QueueWorkerCoordinator(
            queueProvider = queueProvider,
            queueProcessor = queueProcessor,
            schedulerProvider = schedulerProvider,
            clock = deps.clock,
            configuration = spec.configuration,
        )

        return DefaultDataLoomQueueWorker(coordinator)
    }
}
