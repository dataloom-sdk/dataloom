package io.dataloom.runtime.facade

import io.dataloom.api.observation.SynchronizationObserver
import io.dataloom.api.provider.DataLoomProvider
import io.dataloom.api.provider.ProviderInitializationContext
import io.dataloom.api.provider.ProviderType
import io.dataloom.api.queue.QueueProvider
import io.dataloom.api.scheduling.SchedulerProvider
import io.dataloom.api.provider.ProviderBindingFailureReason
import io.dataloom.api.provider.StrategyProviderBindings
import io.dataloom.core.provider.ProviderLifecycleCoordinator
import io.dataloom.core.provider.ProviderRegistry
import io.dataloom.core.provider.ProviderResolutionResult
import io.dataloom.api.provider.SynchronizationProviderBindings
import io.dataloom.core.provider.SynchronizationProviderResolver
import io.dataloom.core.provider.StrategyProviderResolver
import io.dataloom.api.runtime.RuntimeDependencies
import io.dataloom.runtime.connectivity.SynchronizationConnectivityConfiguration
import io.dataloom.runtime.connectivity.SynchronizationConnectivityPreflight
import io.dataloom.runtime.execution.SynchronizationExecutionCoordinator
import io.dataloom.runtime.execution.SynchronizationPipeline
import io.dataloom.runtime.execution.SynchronizationPipelineRegistry
import io.dataloom.runtime.execution.bidirectional.BidirectionalPipelineConfiguration
import io.dataloom.runtime.execution.bidirectional.BidirectionalSynchronizationPipeline
import io.dataloom.api.conflict.DurableUnresolvedConflictLog
import io.dataloom.api.operational.DurableOperationalEventOutbox
import io.dataloom.api.strategy.DurableStrategyDecisionEventLog
import io.dataloom.runtime.conflict.ConflictDetectorRegistry
import io.dataloom.runtime.conflict.ConflictResolverRegistry
import io.dataloom.runtime.conflict.DurableConflictDetectionCoordinator
import io.dataloom.runtime.conflict.SynchronizationConflictOrchestrator
import io.dataloom.runtime.execution.inbound.InboundPullConflictDetectionConfiguration
import io.dataloom.runtime.execution.inbound.InboundPullPipelineConfiguration
import io.dataloom.runtime.execution.inbound.InboundPullSynchronizationPipeline
import io.dataloom.runtime.execution.lifecycle.DispatchingSynchronizationLifecycleEventEmitter
import io.dataloom.runtime.execution.lifecycle.SynchronizationRuntimeEventEmitter
import io.dataloom.runtime.execution.protection.ProviderProtectedStrategySynchronizationCoordinator
import io.dataloom.runtime.execution.protection.ProviderProtectedSynchronizationCoordinator
import io.dataloom.runtime.execution.outbound.OutboundPushPipelineConfiguration
import io.dataloom.runtime.execution.outbound.OutboundPushSynchronizationPipeline
import io.dataloom.runtime.observation.SynchronizationEventDispatcher
import io.dataloom.runtime.observation.SynchronizationObserverRegistry
import io.dataloom.runtime.queue.DurableQueueExecutionProcessor
import io.dataloom.runtime.queue.QueuedSynchronizationExecutionHandler
import io.dataloom.runtime.retry.CircuitAdministrationCoordinator
import io.dataloom.runtime.retry.CircuitBreakerCoordinator
import io.dataloom.runtime.retry.CircuitBreakerExecutionGate
import io.dataloom.runtime.retry.QueueCircuitOperation
import io.dataloom.runtime.retry.RetryAdministrationCoordinator
import io.dataloom.runtime.retry.SchedulerCircuitOperation
import io.dataloom.runtime.retry.StorageCircuitProtectionRuntime
import io.dataloom.runtime.retry.SynchronizationRetryEvaluator
import io.dataloom.runtime.retry.WorkflowTimeoutStateExecutor
import io.dataloom.runtime.retry.TransportCircuitProtectionRuntime
import io.dataloom.runtime.strategy.AcceptedStrategyPlanExecutionCoordinator
import io.dataloom.runtime.strategy.BuiltInSynchronizationStrategyEvaluator
import io.dataloom.runtime.strategy.StrategySynchronizationExecutionCoordinator
import io.dataloom.runtime.submission.DataLoomQueueSubmission
import io.dataloom.runtime.submission.DefaultDataLoomQueueSubmission
import io.dataloom.runtime.submission.QueuedSynchronizationWorkEncoder
import io.dataloom.runtime.submission.QueueSubmissionProviderTimeoutRuntime
import io.dataloom.runtime.worker.CircuitBreakerQueueWorkerRuntime
import io.dataloom.runtime.worker.QueueWorkerCoordinator
import io.dataloom.runtime.worker.QueueWorkerProviderTimeoutRuntime
import io.dataloom.runtime.worker.assembleQueueWorkerQueueProvider

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
 * - Authorize or persist retry-administration commands.
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
 * - [queueWorkerConfiguration] or [circuitQueueWorkerConfiguration] was
 *   called but the queue binding is missing or invalid.
 * - [circuitQueueWorkerSchedulerConfiguration] was called without a
 *   circuit-aware worker or valid scheduler binding.
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
    private var defaultStrategyProviderBindings: StrategyProviderBindings? = null
    private var outboundConfiguration: OutboundPushPipelineConfiguration? = null
    private var inboundConfiguration: InboundPullPipelineConfiguration? = null
    private var bidirectionalConfiguration: BidirectionalPipelineConfiguration? = null
    private var connectivityConfiguration: SynchronizationConnectivityConfiguration? = null
    private val observerList: MutableList<SynchronizationObserver> = mutableListOf()
    private val customPipelineList: MutableList<SynchronizationPipeline> = mutableListOf()
    private var queueWorkerSpec: DataLoomQueueWorkerSpec? = null
    private var circuitQueueWorkerSpec: DataLoomCircuitQueueWorkerSpec? = null
    private var circuitQueueWorkerSchedulerSpec: DataLoomCircuitQueueWorkerSchedulerSpec? = null
    private var queueSubmissionSpecValue: DataLoomQueueSubmissionSpec? = null
    private var providerProtectionSpec: DataLoomProviderProtectionSpec? = null
    private var strategyProviderProtectionSpec: DataLoomStrategyProviderProtectionSpec? = null
    private var retryAdministrationSpec: DataLoomRetryAdministrationSpec? = null
    private var circuitAdministrationSpec: DataLoomCircuitAdministrationSpec? = null
    private var conflictDetectionSpec: DataLoomConflictDetectionSpec? = null
    private var strategyDiagnosticsSpec: DataLoomStrategyDiagnosticsSpec? = null
    private var operationalEventOutboxSpec: DataLoomOperationalEventOutboxSpec? = null
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
     * Sets plan-aware default provider bindings for strategy synchronization.
     *
     * Every role is optional at configuration time. The evaluated strategy
     * plan determines which roles must resolve for an individual call.
     */
    public fun defaultStrategyProviderBindings(
        bindings: StrategyProviderBindings,
    ): DataLoomBuilder = apply {
        defaultStrategyProviderBindings = bindings
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
     * Enables real conflict detection during inbound pull for every
     * registered pipeline — the default direct pipelines and the strategy
     * engine's canonical pipelines used by all six built-in strategies.
     *
     * [io.dataloom.runtime.execution.inbound.InboundPullSynchronizationPipeline]
     * has accepted an optional conflict-detection configuration since `#258`,
     * but nothing in [DataLoomBuilder] constructed one before this method
     * existed — every pipeline ran with conflict detection permanently
     * disabled regardless of what an application registered. Calling this
     * method is the only way to turn it on.
     *
     * When this method is not called, behavior is unchanged from before it
     * existed: no local conflict candidate is ever read, and
     * [io.dataloom.api.synchronization.SynchronizationSummary.conflictsDetected]
     * stays `0`.
     *
     * @param spec the detectors, resolvers, binding, and durable
     *   unresolved-conflict store to use. See [DataLoomConflictDetectionSpec]
     *   for the full contract.
     * @return this builder for chaining.
     */
    public fun conflictDetectionConfiguration(spec: DataLoomConflictDetectionSpec): DataLoomBuilder =
        apply {
            conflictDetectionSpec = spec
        }

    /**
     * Enables durable strategy-decision diagnostics: a payload-free audit
     * trail of past strategy admission/execution decisions, recorded by
     * [io.dataloom.runtime.strategy.StrategySynchronizationExecutionCoordinator]
     * after every terminal result -- for operator visibility and debugging,
     * never for replay or continuation. See [DataLoomStrategyDiagnosticsSpec]
     * for the full contract.
     *
     * When this method is not called, behavior is unchanged from before it
     * existed: no strategy decision event is ever recorded.
     *
     * @param spec the durable store (and optional schema/retry tuning) to
     *   use. See [DataLoomStrategyDiagnosticsSpec] for the full contract.
     * @return this builder for chaining.
     */
    public fun strategyDiagnosticsConfiguration(spec: DataLoomStrategyDiagnosticsSpec): DataLoomBuilder =
        apply {
            strategyDiagnosticsSpec = spec
        }

    /**
     * Enables the durable operational-event outbox bridge for synchronization
     * events: every [io.dataloom.api.synchronization.SynchronizationEvent]
     * the runtime constructs and dispatches is translated into an
     * [io.dataloom.api.operational.OperationalEventEnvelope] by
     * [io.dataloom.runtime.observation.operational.SynchronizationOperationalEventBridge]
     * and durably appended -- for operator visibility and debugging, never
     * for replay or continuation. See [DataLoomOperationalEventOutboxSpec]
     * for the full contract.
     *
     * When this method is not called, behavior is unchanged from before it
     * existed: no operational event envelope is ever constructed or
     * appended.
     *
     * @param spec the durable store (and optional scope/schema/retry tuning)
     *   to use. See [DataLoomOperationalEventOutboxSpec] for the full
     *   contract.
     * @return this builder for chaining.
     */
    public fun operationalEventOutboxConfiguration(
        spec: DataLoomOperationalEventOutboxSpec,
    ): DataLoomBuilder = apply {
        operationalEventOutboxSpec = spec
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
     * Configures additive plan-aware protection for built-in strategy execution.
     * Only providers resolved for the evaluated immutable plan are wrapped.
     */
    public fun strategyProviderProtectionConfiguration(
        spec: DataLoomStrategyProviderProtectionSpec,
    ): DataLoomBuilder = apply {
        strategyProviderProtectionSpec = spec
    }

    /**
     * Configures the optional authorized retry-administration capability.
     *
     * The supplied collaborators are retained by immutable reference and are
     * not invoked during [build]. The runtime clock from
     * [runtimeDependencies] is used for authorization, command-state, and
     * terminal execution evidence when a command is executed.
     *
     * @param spec explicit host authorization, durable command-state, queue
     *   execution, and contention-bound configuration.
     * @return this builder for chaining.
     */
    public fun retryAdministrationConfiguration(
        spec: DataLoomRetryAdministrationSpec,
    ): DataLoomBuilder = apply {
        retryAdministrationSpec = spec
    }

    /**
     * Configures the optional authorized circuit-administration capability.
     *
     * Construction retains the collaborators without invoking authorization,
     * persistence, execution, or the runtime clock.
     */
    public fun circuitAdministrationConfiguration(
        spec: DataLoomCircuitAdministrationSpec,
    ): DataLoomBuilder = apply {
        circuitAdministrationSpec = spec
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
        circuitQueueWorkerSpec = null
        circuitQueueWorkerSchedulerSpec = null
    }

    /**
     * Configures the optional circuit-aware queue-worker capability.
     *
     * Direct and circuit-aware queue-worker capabilities are mutually exclusive.
     * Calling this method clears an earlier direct worker configuration; calling
     * [queueWorkerConfiguration] later clears this configuration. The most
     * recent method is effective.
     *
     * Build validates the bound queue provider and every explicit circuit scope
     * before state-store or provider access. No circuit store, broad scope, or
     * scheduler circuit policy is inferred.
     */
    public fun circuitQueueWorkerConfiguration(
        spec: DataLoomCircuitQueueWorkerSpec,
    ): DataLoomBuilder = apply {
        circuitQueueWorkerSpec = spec
        queueWorkerSpec = null
    }

    /**
     * Adds separately governed circuit protection to the circuit-aware queue
     * worker's follow-up scheduler call.
     *
     * This method may be called before or after
     * [circuitQueueWorkerConfiguration], but [build] requires both
     * configurations and a valid scheduler provider binding. Queue circuit
     * policy is never inferred or reused for scheduling.
     */
    public fun circuitQueueWorkerSchedulerConfiguration(
        spec: DataLoomCircuitQueueWorkerSchedulerSpec,
    ): DataLoomBuilder = apply {
        circuitQueueWorkerSchedulerSpec = spec
    }

    /**
     * Configures the optional queue-submission capability.
     *
     * When supplied with a valid queue provider binding in
     * [defaultProviderBindings], [DataLoom.queueSubmission] will be non-null
     * after [build]. Otherwise [DataLoom.queueSubmission] is `null`.
     *
     * [build] throws [DataLoomBuildException] when this method has been called
     * but the queue provider binding is absent or refers to an invalid
     * provider.
     *
     * Queue submission and queue worker are independently configurable. Either,
     * both, or neither capability may be configured.
     *
     * [build] performs no encoding, no enqueue operation, no clock read, and
     * no identifier generation.
     *
     * @param encoder the application-owned encoder that converts a
     *   [io.dataloom.runtime.submission.QueuedSynchronizationSubmission] into
     *   a [io.dataloom.api.queue.QueueEnqueueRequest]. Required to enable the
     *   queue-submission capability.
     * @return this builder for chaining.
     */
    public fun queueSubmissionEncoder(
        encoder: QueuedSynchronizationWorkEncoder,
    ): DataLoomBuilder = apply {
        queueSubmissionSpecValue = DataLoomQueueSubmissionSpec(encoder)
    }

    /**
     * Configures queue submission with an explicit immutable [spec].
     *
     * The optional queue-provider timeout applies only to the single enqueue
     * operation. It is independent from queue-worker and scheduler timeouts.
     * When both queue-submission setters are called, the most recent call is the
     * effective configuration.
     */
    public fun queueSubmissionConfiguration(
        spec: DataLoomQueueSubmissionSpec,
    ): DataLoomBuilder = apply {
        queueSubmissionSpecValue = spec
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
        if (circuitQueueWorkerSchedulerSpec != null && circuitQueueWorkerSpec == null) {
            throw DataLoomBuildException(
                "DataLoomBuilder circuitQueueWorkerSchedulerConfiguration requires " +
                    "circuitQueueWorkerConfiguration.",
            )
        }
        val strategyBindings = defaultStrategyProviderBindings
            ?: bindings?.toStrategyProviderBindings()
            ?: throw DataLoomBuildException(
                "DataLoomBuilder requires defaultProviderBindings or " +
                    "defaultStrategyProviderBindings before build().",
            )

        // --- 2. Build ProviderRegistry (throws IllegalArgumentException for duplicate IDs) ---
        val registry = ProviderRegistry(providerList.toList())

        // --- 3. Validate default bindings structurally ---
        val resolver = SynchronizationProviderResolver(registry)
        val resolutionResult = bindings?.let(resolver::resolve)
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

        val strategyResolver = StrategyProviderResolver(registry)

        // --- 4. Build observer infrastructure (optional) ---
        // Built when at least one observer is registered, or the operational-event
        // outbox bridge is configured -- DispatchingSynchronizationLifecycleEventEmitter
        // is both the sole constructor and the sole dispatch point of every
        // SynchronizationEvent, so the bridge needs it to run even with zero observers.
        val operationalEventOutbox = operationalEventOutboxSpec?.let { spec ->
            DurableOperationalEventOutbox(
                store = spec.store,
                schemaVersion = spec.schemaVersion,
                maximumStateUpdateAttempts = spec.maximumStateUpdateAttempts,
            )
        }
        val lifecycleEventEmitter = if (observerList.isNotEmpty() || operationalEventOutboxSpec != null) {
            // SynchronizationObserverRegistry throws IllegalArgumentException for duplicate IDs.
            val observerRegistry = SynchronizationObserverRegistry(observerList.toList())
            val dispatcher = SynchronizationEventDispatcher(observerRegistry)
            DispatchingSynchronizationLifecycleEventEmitter(
                dispatcher = dispatcher,
                clock = deps.clock,
                eventIdGenerator = deps.identifiers.synchronizationEventIds,
                operationalEventOutbox = operationalEventOutbox,
                operationalEventOutboxScope = operationalEventOutboxSpec?.scope,
            )
        } else {
            null
        }

        // --- 4b. Build conflict-detection configuration (optional) ---
        val conflictDetectionConfig = conflictDetectionSpec?.let { spec ->
            InboundPullConflictDetectionConfiguration(
                coordinator = DurableConflictDetectionCoordinator(
                    orchestrator = SynchronizationConflictOrchestrator(
                        detectorRegistry = ConflictDetectorRegistry(spec.detectors),
                        resolverRegistry = ConflictResolverRegistry(spec.resolvers),
                        eventEmitter = lifecycleEventEmitter as? SynchronizationRuntimeEventEmitter,
                    ),
                    unresolvedConflictLog = DurableUnresolvedConflictLog(
                        store = spec.unresolvedConflictStore,
                        schemaVersion = spec.unresolvedConflictLogSchemaVersion,
                        maximumStateUpdateAttempts = spec.unresolvedConflictLogMaximumStateUpdateAttempts,
                    ),
                    clock = deps.clock,
                ),
                bindings = spec.bindings,
            )
        }

        // --- 5. Build pipeline registry with defaults and custom overrides ---
        val finalPipelineRegistry = buildPipelineRegistry(conflictDetectionConfig)

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
        val strategyPipelineRegistry = buildStrategyPipelineRegistry(conflictDetectionConfig)
        // --- 8b. Build strategy-decision diagnostics log (optional) ---
        val strategyDecisionEventLog = strategyDiagnosticsSpec?.let { spec ->
            DurableStrategyDecisionEventLog(
                store = spec.store,
                schemaVersion = spec.schemaVersion,
                maximumStateUpdateAttempts = spec.maximumStateUpdateAttempts,
            )
        }
        val strategyExecutionCoordinator = StrategySynchronizationExecutionCoordinator(
            lifecycleCoordinator = lifecycleCoordinator,
            evaluator = BuiltInSynchronizationStrategyEvaluator(),
            providerResolver = strategyResolver,
            clock = deps.clock,
            runtimeDependencies = deps,
            pipelineRegistry = strategyPipelineRegistry,
            lifecycleEventEmitter = lifecycleEventEmitter,
            durableQueueWorkEncoder = queueSubmissionSpecValue?.encoder,
            strategyDecisionEventLog = strategyDecisionEventLog,
        )
        val acceptedStrategyPlanCoordinator = AcceptedStrategyPlanExecutionCoordinator(
            lifecycleCoordinator = lifecycleCoordinator,
            providerResolver = strategyResolver,
            clock = deps.clock,
            runtimeDependencies = deps,
            pipelineRegistry = strategyPipelineRegistry,
            lifecycleEventEmitter = lifecycleEventEmitter,
        )

        // --- 9. Build optional provider-protected strategy synchronization ---
        val protectedStrategySynchronization = strategyProviderProtectionSpec?.let { spec ->
            DefaultDataLoomProtectedStrategySynchronization(
                coordinator = ProviderProtectedStrategySynchronizationCoordinator(
                    strategyCoordinator = strategyExecutionCoordinator,
                    acceptedPlanCoordinator = acceptedStrategyPlanCoordinator,
                    protectionSpec = spec,
                    clock = deps.clock,
                ),
                defaultBindings = strategyBindings,
            )
        }

        // --- 10. Build optional provider-protected direct synchronization ---
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
        val queueWorker = queueWorkerSpec?.let { spec ->
            val legacyBindings = bindings
                ?: throw DataLoomBuildException(
                    "DataLoomBuilder queueWorkerConfiguration currently requires " +
                        "defaultProviderBindings.",
                )
            buildQueueWorker(
                spec = spec,
                registry = registry,
                bindings = legacyBindings,
                deps = deps,
                executionCoordinator = executionCoordinator,
                acceptedStrategyPlanCoordinator = acceptedStrategyPlanCoordinator,
            )
        }

        // --- 11. Build optional circuit-aware queue worker ---
        val circuitQueueWorker = circuitQueueWorkerSpec?.let { spec ->
            val legacyBindings = bindings
                ?: throw DataLoomBuildException(
                    "DataLoomBuilder circuitQueueWorkerConfiguration currently requires " +
                        "defaultProviderBindings.",
                )
            buildCircuitQueueWorker(
                spec = spec,
                registry = registry,
                bindings = legacyBindings,
                deps = deps,
                executionCoordinator = executionCoordinator,
                acceptedStrategyPlanCoordinator = acceptedStrategyPlanCoordinator,
                schedulerCircuitSpec = circuitQueueWorkerSchedulerSpec,
            )
        }

        // --- 12. Build optional queue submission ---
        val queueSubmission = queueSubmissionSpecValue?.let { spec ->
            val legacyBindings = bindings
                ?: throw DataLoomBuildException(
                    "DataLoomBuilder queue submission currently requires " +
                        "defaultProviderBindings.",
                )
            buildQueueSubmission(
                spec = spec,
                registry = registry,
                bindings = legacyBindings,
                deps = deps,
            )
        }

        // --- 13. Build optional retry-administration operations capability ---
        val retryAdministration = retryAdministrationSpec?.let { spec ->
            DefaultDataLoomRetryAdministration(
                coordinator = RetryAdministrationCoordinator(
                    clock = deps.clock,
                    authorizer = spec.authorizer,
                    stateStore = spec.stateStore,
                    executor = spec.executor,
                    maximumStateUpdateAttempts = spec.maximumStateUpdateAttempts,
                ),
            )
        }

        // --- 14. Build optional circuit-administration operations capability ---
        val circuitAdministration = circuitAdministrationSpec?.let { spec ->
            DefaultDataLoomCircuitAdministration(
                coordinator = CircuitAdministrationCoordinator(
                    clock = deps.clock,
                    authorizer = spec.authorizer,
                    stateStore = spec.stateStore,
                    executor = spec.executor,
                    maximumStateUpdateAttempts = spec.maximumStateUpdateAttempts,
                ),
            )
        }

        return DefaultDataLoom(
            lifecycleCoordinator = lifecycleCoordinator,
            executionCoordinator = executionCoordinator,
            strategyExecutionCoordinator = strategyExecutionCoordinator,
            acceptedStrategyPlanCoordinator = acceptedStrategyPlanCoordinator,
            defaultBindings = bindings,
            defaultStrategyBindings = strategyBindings,
            queueWorker = queueWorker,
            circuitQueueWorker = circuitQueueWorker,
            protectedSynchronization = protectedSynchronization,
            protectedStrategySynchronization = protectedStrategySynchronization,
            queueSubmission = queueSubmission,
            retryAdministration = retryAdministration,
            circuitAdministration = circuitAdministration,
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

    private fun SynchronizationProviderBindings.toStrategyProviderBindings():
        StrategyProviderBindings =
        StrategyProviderBindings(
            storageProviderId = storageProviderId,
            transportProviderId = transportProviderId,
            schedulerProviderId = schedulerProviderId,
            connectivityProviderId = connectivityProviderId,
            queueProviderId = queueProviderId,
        )

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
    private fun buildPipelineRegistry(
        conflictDetectionConfig: InboundPullConflictDetectionConfiguration?,
    ): SynchronizationPipelineRegistry {
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
                    conflictDetectionConfig,
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
     * Builds canonical strategy pipelines without custom direction overrides.
     *
     * Built-in strategy semantics must not silently change because an
     * application registered a legacy custom pipeline.
     */
    private fun buildStrategyPipelineRegistry(
        conflictDetectionConfig: InboundPullConflictDetectionConfiguration?,
    ): SynchronizationPipelineRegistry {
        val outbound = OutboundPushSynchronizationPipeline(
            outboundConfiguration ?: OutboundPushPipelineConfiguration(),
        )
        val inbound = InboundPullSynchronizationPipeline(
            inboundConfiguration ?: InboundPullPipelineConfiguration(),
            conflictDetectionConfig,
        )
        val bidirectional = BidirectionalSynchronizationPipeline(
            outboundPipeline = outbound,
            inboundPipeline = inbound,
            configuration = BidirectionalPipelineConfiguration(),
        )
        return SynchronizationPipelineRegistry(
            listOf(outbound, inbound, bidirectional),
        )
    }

    /**
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
     *
     * Validates that the default bindings contain a valid QueueProvider ID
     * that refers to a registered [QueueProvider]. Throws
     * [DataLoomBuildException] when this requirement is not satisfied.
     *
     * The optional [SchedulerProvider] for the coordinator is resolved from
     * the default bindings when a scheduler ID is configured. When
     * [DataLoomQueueWorkerSpec.queueProviderTimeout] is configured, the same
     * timeout-protected queue-provider instance is used for recovery,
     * acquisition, and every durable transition.
     */
    private fun buildQueueWorker(
        spec: DataLoomQueueWorkerSpec,
        registry: ProviderRegistry,
        bindings: SynchronizationProviderBindings,
        deps: RuntimeDependencies,
        executionCoordinator: SynchronizationExecutionCoordinator,
        acceptedStrategyPlanCoordinator: AcceptedStrategyPlanExecutionCoordinator,
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
            workflowTimeoutExecutor = WorkflowTimeoutStateExecutor(deps.clock),
            acceptedStrategyPlanCoordinator = acceptedStrategyPlanCoordinator,
        )

        val queueProviderTimeout = spec.queueProviderTimeout
        val coordinator = if (queueProviderTimeout != null) {
            QueueWorkerProviderTimeoutRuntime.create(
                queueProvider = queueProvider,
                executionHandler = executionHandler,
                schedulerProvider = schedulerProvider,
                clock = deps.clock,
                configuration = spec.configuration,
                queueProviderTimeout = queueProviderTimeout,
            )
        } else {
            val queueProcessor = DurableQueueExecutionProcessor(
                queueProvider = queueProvider,
                executionHandler = executionHandler,
            )
            QueueWorkerCoordinator(
                queueProvider = queueProvider,
                queueProcessor = queueProcessor,
                schedulerProvider = schedulerProvider,
                clock = deps.clock,
                configuration = spec.configuration,
            )
        }

        return DefaultDataLoomQueueWorker(coordinator)
    }

    /**
     * Assembles the explicit circuit-aware queue-worker capability.
     *
     * The queue-provider timeout is applied before circuit adaptation so
     * recovery, acquisition, and every transition share one protected provider
     * and one circuit gate. Build performs structural validation only.
     */
    private fun buildCircuitQueueWorker(
        spec: DataLoomCircuitQueueWorkerSpec,
        registry: ProviderRegistry,
        bindings: SynchronizationProviderBindings,
        deps: RuntimeDependencies,
        executionCoordinator: SynchronizationExecutionCoordinator,
        acceptedStrategyPlanCoordinator: AcceptedStrategyPlanExecutionCoordinator,
        schedulerCircuitSpec: DataLoomCircuitQueueWorkerSchedulerSpec?,
    ): DataLoomCircuitQueueWorker {
        val queueProviderId = bindings.queueProviderId
            ?: throw DataLoomBuildException(
                "DataLoomBuilder circuitQueueWorkerConfiguration requires a queue provider " +
                    "binding. Set queueProviderId on defaultProviderBindings before build().",
            )

        val queueProviderCandidate = registry.findById(queueProviderId)
            ?: throw DataLoomBuildException(
                "DataLoomBuilder circuitQueueWorkerConfiguration: queue provider " +
                    "'${queueProviderId.value}' not found in registry.",
            )

        if (queueProviderCandidate.descriptor.type != ProviderType.QUEUE) {
            throw DataLoomBuildException(
                "DataLoomBuilder circuitQueueWorkerConfiguration: provider " +
                    "'${queueProviderId.value}' has type " +
                    "${queueProviderCandidate.descriptor.type} but expected ${ProviderType.QUEUE}.",
            )
        }

        val queueProvider = queueProviderCandidate as? QueueProvider
            ?: throw DataLoomBuildException(
                "DataLoomBuilder circuitQueueWorkerConfiguration: provider " +
                    "'${queueProviderId.value}' does not implement the QueueProvider contract.",
            )

        validateCircuitQueueWorkerScope(
            scope = spec.recoveryScope,
            operation = QueueCircuitOperation.RECOVER_EXPIRED_LEASES,
            queueProviderId = queueProviderId,
            label = "recovery",
        )
        validateCircuitQueueWorkerScope(
            scope = spec.processingScopes.acquisition,
            operation = QueueCircuitOperation.ACQUIRE,
            queueProviderId = queueProviderId,
            label = "acquisition",
        )
        validateCircuitQueueWorkerScope(
            scope = spec.processingScopes.completion,
            operation = QueueCircuitOperation.COMPLETE,
            queueProviderId = queueProviderId,
            label = "completion",
        )
        validateCircuitQueueWorkerScope(
            scope = spec.processingScopes.reschedule,
            operation = QueueCircuitOperation.RESCHEDULE,
            queueProviderId = queueProviderId,
            label = "reschedule",
        )
        validateCircuitQueueWorkerScope(
            scope = spec.processingScopes.deferral,
            operation = QueueCircuitOperation.DEFER,
            queueProviderId = queueProviderId,
            label = "deferral",
        )
        validateCircuitQueueWorkerScope(
            scope = spec.processingScopes.failure,
            operation = QueueCircuitOperation.FAIL,
            queueProviderId = queueProviderId,
            label = "failure",
        )
        validateCircuitQueueWorkerScope(
            scope = spec.processingScopes.cancellation,
            operation = QueueCircuitOperation.CANCEL,
            queueProviderId = queueProviderId,
            label = "cancellation",
        )

        val schedulerProvider: SchedulerProvider? = bindings.schedulerProviderId?.let { schedulerId ->
            val candidate = registry.findById(schedulerId)
            if (
                candidate != null &&
                candidate.descriptor.type == ProviderType.SCHEDULER &&
                candidate is SchedulerProvider
            ) {
                candidate
            } else {
                null
            }
        }
        if (schedulerCircuitSpec != null && schedulerProvider == null) {
            throw DataLoomBuildException(
                "DataLoomBuilder circuitQueueWorkerSchedulerConfiguration requires a valid " +
                    "scheduler provider binding.",
            )
        }
        schedulerCircuitSpec?.let { schedulerSpec ->
            validateCircuitQueueWorkerSchedulerScope(
                scope = schedulerSpec.scope,
                schedulerProviderId = checkNotNull(schedulerProvider).descriptor.id,
            )
        }

        val workerSpec = spec.workerSpec
        val retryEvaluator = SynchronizationRetryEvaluator(
            retryPolicy = workerSpec.retryPolicy,
            clock = deps.clock,
        )
        val executionHandler = QueuedSynchronizationExecutionHandler(
            workResolver = workerSpec.workResolver,
            executionCoordinator = executionCoordinator,
            retryEvaluator = retryEvaluator,
            retryOperation = workerSpec.retryOperation,
            connectivityConfiguration = connectivityConfiguration,
            clock = if (connectivityConfiguration != null) deps.clock else null,
            workflowTimeoutExecutor = WorkflowTimeoutStateExecutor(deps.clock),
            acceptedStrategyPlanCoordinator = acceptedStrategyPlanCoordinator,
        )
        val protectedQueueProvider = assembleQueueWorkerQueueProvider(
            queueProvider = queueProvider,
            clock = deps.clock,
            providerTimeout = workerSpec.queueProviderTimeout,
        )
        val queueCircuitCoordinator = CircuitBreakerCoordinator(
            configuration = spec.circuitBreakerConfiguration,
            clock = deps.clock,
            stateStore = spec.circuitBreakerStateStore,
        )
        val workerCoordinator = if (schedulerCircuitSpec == null) {
            CircuitBreakerQueueWorkerRuntime.create(
                queueProvider = protectedQueueProvider,
                executionGate = CircuitBreakerExecutionGate(queueCircuitCoordinator),
                recoveryScope = spec.recoveryScope,
                processingScopes = spec.processingScopes,
                executionHandler = executionHandler,
                schedulerProvider = schedulerProvider,
                clock = deps.clock,
                configuration = workerSpec.configuration,
                failureClassifier = spec.failureClassifier,
            )
        } else {
            val schedulerCircuitCoordinator = CircuitBreakerCoordinator(
                configuration = schedulerCircuitSpec.circuitBreakerConfiguration,
                clock = deps.clock,
                stateStore = schedulerCircuitSpec.circuitBreakerStateStore,
            )
            CircuitBreakerQueueWorkerRuntime.createWithSchedulerCircuit(
                queueProvider = protectedQueueProvider,
                queueExecutionGate = CircuitBreakerExecutionGate(queueCircuitCoordinator),
                recoveryScope = spec.recoveryScope,
                processingScopes = spec.processingScopes,
                executionHandler = executionHandler,
                schedulerProvider = checkNotNull(schedulerProvider),
                schedulerExecutionGate = CircuitBreakerExecutionGate(
                    schedulerCircuitCoordinator,
                ),
                schedulerScope = schedulerCircuitSpec.scope,
                clock = deps.clock,
                configuration = workerSpec.configuration,
                queueFailureClassifier = spec.failureClassifier,
                schedulerFailureClassifier = schedulerCircuitSpec.failureClassifier,
            )
        }
        return DefaultDataLoomCircuitQueueWorker(workerCoordinator)
    }

    private fun validateCircuitQueueWorkerScope(
        scope: io.dataloom.api.circuit.CircuitBreakerScope,
        operation: QueueCircuitOperation,
        queueProviderId: io.dataloom.api.provider.ProviderId,
        label: String,
    ) {
        if (scope.providerId != null && scope.providerId != queueProviderId) {
            throw DataLoomBuildException(
                "DataLoomBuilder circuitQueueWorkerConfiguration $label scope provider " +
                    "must match queue provider '${queueProviderId.value}'.",
            )
        }
        if (scope.operation != null && scope.operation != operation.retryOperation) {
            throw DataLoomBuildException(
                "DataLoomBuilder circuitQueueWorkerConfiguration $label scope operation " +
                    "must be '${operation.retryOperation.value}'.",
            )
        }
    }

    private fun validateCircuitQueueWorkerSchedulerScope(
        scope: io.dataloom.api.circuit.CircuitBreakerScope,
        schedulerProviderId: io.dataloom.api.provider.ProviderId,
    ) {
        if (scope.providerId != null && scope.providerId != schedulerProviderId) {
            throw DataLoomBuildException(
                "DataLoomBuilder circuitQueueWorkerSchedulerConfiguration scope provider " +
                    "must match scheduler provider '${schedulerProviderId.value}'.",
            )
        }
        if (
            scope.operation != null &&
            scope.operation != SchedulerCircuitOperation.SCHEDULE.retryOperation
        ) {
            throw DataLoomBuildException(
                "DataLoomBuilder circuitQueueWorkerSchedulerConfiguration scope operation " +
                    "must be '${SchedulerCircuitOperation.SCHEDULE.retryOperation.value}'.",
            )
        }
    }

    /**
     * Assembles the queue-submission capability.
     *
     * Validates that the default bindings contain a valid QueueProvider ID
     * that refers to a registered [QueueProvider]. Throws
     * [DataLoomBuildException] when this requirement is not satisfied.
     *
     * Build performs no encoding, enqueue operation, timeout execution, clock
     * read, or identifier generation. A configured submission timeout is
     * assembled structurally and applies only when `submit` invokes enqueue.
     */
    private fun buildQueueSubmission(
        spec: DataLoomQueueSubmissionSpec,
        registry: ProviderRegistry,
        bindings: SynchronizationProviderBindings,
        deps: RuntimeDependencies,
    ): DataLoomQueueSubmission {
        val queueProviderId = bindings.queueProviderId
            ?: throw DataLoomBuildException(
                "DataLoomBuilder queueSubmissionEncoder requires a queue provider binding. " +
                    "Set queueProviderId on the defaultProviderBindings before build().",
            )

        val queueProviderCandidate = registry.findById(queueProviderId)
            ?: throw DataLoomBuildException(
                "DataLoomBuilder queueSubmissionEncoder: queue provider '${queueProviderId.value}' " +
                    "not found in registry.",
            )

        if (queueProviderCandidate.descriptor.type != ProviderType.QUEUE) {
            throw DataLoomBuildException(
                "DataLoomBuilder queueSubmissionEncoder: provider '${queueProviderId.value}' " +
                    "has type ${queueProviderCandidate.descriptor.type} but expected ${ProviderType.QUEUE}.",
            )
        }

        val queueProvider = queueProviderCandidate as? QueueProvider
            ?: throw DataLoomBuildException(
                "DataLoomBuilder queueSubmissionEncoder: provider '${queueProviderId.value}' " +
                    "does not implement the QueueProvider contract.",
            )

        val queueProviderTimeout = spec.queueProviderTimeout
        return if (queueProviderTimeout != null) {
            QueueSubmissionProviderTimeoutRuntime.create(
                queueProvider = queueProvider,
                encoder = spec.encoder,
                clock = deps.clock,
                queueProviderTimeout = queueProviderTimeout,
            )
        } else {
            DefaultDataLoomQueueSubmission(
                queueProvider = queueProvider,
                encoder = spec.encoder,
            )
        }
    }
}
