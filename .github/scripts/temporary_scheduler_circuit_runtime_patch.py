from pathlib import Path


def replace_once(path: str, old: str, new: str) -> None:
    file = Path(path)
    text = file.read_text()
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"Expected one match in {path}, found {count}")
    file.write_text(text.replace(old, new, 1))


def write_new(path: str, content: str) -> None:
    file = Path(path)
    if file.exists():
        raise SystemExit(f"Refusing to replace existing file {path}")
    file.parent.mkdir(parents=True, exist_ok=True)
    file.write_text(content)


# ---------------------------------------------------------------------------
# Stable scheduler circuit operation identity.
# ---------------------------------------------------------------------------

write_new(
    "dataloom-runtime/src/commonMain/kotlin/io/dataloom/runtime/retry/"
    "SchedulerCircuitOperation.kt",
    """package io.dataloom.runtime.retry

import io.dataloom.api.retry.RetryOperation

/** Stable scheduler operations that may own an explicit circuit scope. */
public enum class SchedulerCircuitOperation(
    /** Stable operation identity used by provider-operation circuit scopes. */
    public val retryOperation: RetryOperation,
) {
    SCHEDULE(RetryOperation("scheduler.schedule")),
}
""",
)


# ---------------------------------------------------------------------------
# Strengthen the scheduler operation adapter's scope validation.
# ---------------------------------------------------------------------------

adapter_path = (
    "dataloom-runtime/src/commonMain/kotlin/io/dataloom/runtime/retry/"
    "CircuitBreakerRetrySchedulingAdapter.kt"
)
replace_once(
    adapter_path,
    """    init {
        require(scope.providerId == null || scope.providerId == schedulerProvider.descriptor.id) {
            "CircuitBreakerRetrySchedulingAdapter scope provider must match scheduler provider."
        }
    }
""",
    """    init {
        require(scope.providerId == null || scope.providerId == schedulerProvider.descriptor.id) {
            "CircuitBreakerRetrySchedulingAdapter scope provider must match scheduler provider."
        }
        require(
            scope.operation == null ||
                scope.operation == SchedulerCircuitOperation.SCHEDULE.retryOperation,
        ) {
            "CircuitBreakerRetrySchedulingAdapter scope operation must be scheduler.schedule."
        }
    }
""",
)
replace_once(
    adapter_path,
    """ * The selected [scope] must either be global/workflow scoped or identify the
 * exact scheduler provider. No implicit scope derivation or fallback is applied.
""",
    """ * The selected [scope] must either be global/workflow scoped or identify the
 * exact scheduler provider. An operation-bearing scope must use
 * [SchedulerCircuitOperation.SCHEDULE]. No implicit scope derivation or fallback
 * is applied.
""",
)


# ---------------------------------------------------------------------------
# Preserve full circuit scheduling evidence in the worker result.
# ---------------------------------------------------------------------------

scheduling_result_path = (
    "dataloom-runtime/src/commonMain/kotlin/io/dataloom/runtime/worker/"
    "QueueWorkerSchedulingResult.kt"
)
replace_once(
    scheduling_result_path,
    """import io.dataloom.api.scheduling.ScheduleReceipt
""",
    """import io.dataloom.api.scheduling.ScheduleReceipt
import io.dataloom.runtime.retry.CircuitBreakerExecutionResult
""",
)
replace_once(
    scheduling_result_path,
    """ * - [Scheduled] — [io.dataloom.api.scheduling.SchedulerProvider.schedule] was
 *   called once and the provider accepted the request.
 * - [SchedulerNotConfigured] — a wake-up was required but no
""",
    """ * - [Scheduled] — [io.dataloom.api.scheduling.SchedulerProvider.schedule] was
 *   called once and the provider accepted the request on the direct path.
 * - [CircuitProtected] — scheduling used an explicit circuit and preserves the
 *   complete pre-execution, provider, and post-execution recording evidence.
 * - [SchedulerNotConfigured] — a wake-up was required but no
""",
)
replace_once(
    scheduling_result_path,
    """    public data class Scheduled(
        /** Exact [ScheduleReceipt] returned by the provider. */
        public val receipt: ScheduleReceipt,

        /** Exact [QueueWorkerWakeUpPlan.Schedule] that was executed. */
        public val plan: QueueWorkerWakeUpPlan.Schedule,
    ) : QueueWorkerSchedulingResult

    /**
     * A wake-up was required but no
""",
    """    public data class Scheduled(
        /** Exact [ScheduleReceipt] returned by the provider. */
        public val receipt: ScheduleReceipt,

        /** Exact [QueueWorkerWakeUpPlan.Schedule] that was executed. */
        public val plan: QueueWorkerWakeUpPlan.Schedule,
    ) : QueueWorkerSchedulingResult

    /**
     * Scheduling was protected by an explicitly configured scheduler circuit.
     *
     * [executionResult] preserves whether the provider was rejected before
     * invocation, returned a canonical failure, accepted the schedule, or
     * accepted it before the later circuit-state recording became unconfirmed.
     * An accepted schedule is never collapsed into a generic failure merely
     * because post-execution circuit persistence failed.
     */
    public data class CircuitProtected(
        /** Complete circuit permission, provider, and recording evidence. */
        public val executionResult: CircuitBreakerExecutionResult<ScheduleReceipt>,

        /** Exact wake-up plan submitted or rejected by the scheduler boundary. */
        public val plan: QueueWorkerWakeUpPlan.Schedule,
    ) : QueueWorkerSchedulingResult

    /**
     * A wake-up was required but no
""",
)


# ---------------------------------------------------------------------------
# Add the optional scheduler circuit path to the worker coordinator.
# ---------------------------------------------------------------------------

coordinator_path = (
    "dataloom-runtime/src/commonMain/kotlin/io/dataloom/runtime/worker/"
    "CircuitBreakerQueueWorkerCoordinator.kt"
)
replace_once(
    coordinator_path,
    """import io.dataloom.runtime.retry.CircuitBreakerRecordResult
""",
    """import io.dataloom.runtime.retry.CircuitBreakerRecordResult
import io.dataloom.runtime.retry.CircuitBreakerRetrySchedulingAdapter
""",
)
replace_once(
    coordinator_path,
    """    private val queueProcessor: CircuitBreakerQueueProcessingEngine,
    schedulerProvider: SchedulerProvider?,
    private val clock: DataLoomClock,
    private val configuration: QueueWorkerConfiguration,
) {

    private val schedulerProvider: SchedulerProvider? = assembleQueueWorkerSchedulerProvider(
        provider = schedulerProvider,
        timeout = configuration.schedulerProviderTimeout,
        clock = clock,
    )
""",
    """    private val queueProcessor: CircuitBreakerQueueProcessingEngine,
    directSchedulerProvider: SchedulerProvider?,
    private val schedulerCircuitAdapter: CircuitBreakerRetrySchedulingAdapter?,
    private val clock: DataLoomClock,
    private val configuration: QueueWorkerConfiguration,
) {

    private val schedulerProvider: SchedulerProvider? =
        if (schedulerCircuitAdapter == null) {
            assembleQueueWorkerSchedulerProvider(
                provider = directSchedulerProvider,
                timeout = configuration.schedulerProviderTimeout,
                clock = clock,
            )
        } else {
            null
        }
""",
)
replace_once(
    coordinator_path,
    """    init {
        require(
            recoveryScope.providerId == null ||
""",
    """    init {
        require(directSchedulerProvider == null || schedulerCircuitAdapter == null) {
            "Circuit-aware queue worker must use either direct or circuit-protected scheduling."
        }
        require(
            recoveryScope.providerId == null ||
""",
)
replace_once(
    coordinator_path,
    """        queueProcessor = CircuitBreakerQueueProcessingEngine { request ->
            queueProcessor.process(request)
        },
        schedulerProvider = schedulerProvider,
        clock = clock,
        configuration = configuration,
    )
""",
    """        queueProcessor = CircuitBreakerQueueProcessingEngine { request ->
            queueProcessor.process(request)
        },
        directSchedulerProvider = schedulerProvider,
        schedulerCircuitAdapter = null,
        clock = clock,
        configuration = configuration,
    )
""",
)
replace_once(
    coordinator_path,
    """        val request = ScheduleRequest(
            id = schedulePlan.scheduleId,
            synchronizationRequest = null,
            delay = schedulePlan.delay,
            constraints = schedulePlan.constraints,
            existingPolicy = schedulePlan.existingSchedulePolicy,
        )
        return when (val result = scheduler.schedule(request)) {
""",
    """        val request = ScheduleRequest(
            id = schedulePlan.scheduleId,
            synchronizationRequest = null,
            delay = schedulePlan.delay,
            constraints = schedulePlan.constraints,
            existingPolicy = schedulePlan.existingSchedulePolicy,
        )
        val circuitAdapter = schedulerCircuitAdapter
        if (circuitAdapter != null) {
            return QueueWorkerSchedulingResult.CircuitProtected(
                executionResult = circuitAdapter.schedule(request),
                plan = schedulePlan,
            )
        }
        return when (val result = scheduler.schedule(request)) {
""",
)
# The direct scheduler lookup must happen after the optional circuit path.
replace_once(
    coordinator_path,
    """        val scheduler = schedulerProvider
            ?: return QueueWorkerSchedulingResult.SchedulerNotConfigured(schedulePlan)
        val request = ScheduleRequest(
""",
    """        val request = ScheduleRequest(
""",
)
replace_once(
    coordinator_path,
    """        val circuitAdapter = schedulerCircuitAdapter
        if (circuitAdapter != null) {
            return QueueWorkerSchedulingResult.CircuitProtected(
                executionResult = circuitAdapter.schedule(request),
                plan = schedulePlan,
            )
        }
        return when (val result = scheduler.schedule(request)) {
""",
    """        val circuitAdapter = schedulerCircuitAdapter
        if (circuitAdapter != null) {
            return QueueWorkerSchedulingResult.CircuitProtected(
                executionResult = circuitAdapter.schedule(request),
                plan = schedulePlan,
            )
        }
        val scheduler = schedulerProvider
            ?: return QueueWorkerSchedulingResult.SchedulerNotConfigured(schedulePlan)
        return when (val result = scheduler.schedule(request)) {
""",
)
replace_once(
    coordinator_path,
    """ * Recovery, processing, and scheduling remain separate evidence boundaries.
 * A provider success followed by an unconfirmed circuit write is never
 * collapsed into a provider failure or replayed.
""",
    """ * Recovery, processing, and scheduling remain separate evidence boundaries.
 * A provider success followed by an unconfirmed circuit write is never
 * collapsed into a provider failure or replayed. When scheduler circuit policy
 * is configured, scheduler acceptance and its later circuit-recording result
 * are preserved independently.
""",
)


# ---------------------------------------------------------------------------
# Add production runtime assembly for timeout-before-circuit scheduling.
# ---------------------------------------------------------------------------

runtime_path = (
    "dataloom-runtime/src/commonMain/kotlin/io/dataloom/runtime/worker/"
    "CircuitBreakerQueueWorkerRuntime.kt"
)
replace_once(
    runtime_path,
    """import io.dataloom.runtime.queue.CircuitBreakerDurableQueueExecutionProcessor
""",
    """import io.dataloom.runtime.queue.CircuitBreakerDurableQueueExecutionProcessor
import io.dataloom.runtime.queue.CircuitBreakerQueueProcessingEngine
""",
)
replace_once(
    runtime_path,
    """import io.dataloom.runtime.retry.CircuitBreakerQueueOperationAdapter
""",
    """import io.dataloom.runtime.retry.CircuitBreakerProviderOperationAdapter
import io.dataloom.runtime.retry.CircuitBreakerQueueOperationAdapter
import io.dataloom.runtime.retry.CircuitBreakerRetrySchedulingAdapter
import io.dataloom.runtime.retry.DefaultCircuitBreakerFailureClassifier
""",
)
replace_once(
    runtime_path,
    """    public fun create(
        queueProvider: QueueProvider,
        executionGate: CircuitBreakerExecutionGate,
        recoveryScope: CircuitBreakerScope,
        processingScopes: QueueProcessingCircuitScopes,
        executionHandler: QueueEntryExecutionHandler,
        schedulerProvider: SchedulerProvider?,
        clock: DataLoomClock,
        configuration: QueueWorkerConfiguration,
        failureClassifier: CircuitBreakerFailureClassifier =
            QueueCircuitBreakerFailureClassifier,
    ): CircuitBreakerQueueWorkerCoordinator {
""",
    """    public fun create(
        queueProvider: QueueProvider,
        executionGate: CircuitBreakerExecutionGate,
        recoveryScope: CircuitBreakerScope,
        processingScopes: QueueProcessingCircuitScopes,
        executionHandler: QueueEntryExecutionHandler,
        schedulerProvider: SchedulerProvider?,
        clock: DataLoomClock,
        configuration: QueueWorkerConfiguration,
        failureClassifier: CircuitBreakerFailureClassifier =
            QueueCircuitBreakerFailureClassifier,
    ): CircuitBreakerQueueWorkerCoordinator {
""",
)
replace_once(
    runtime_path,
    """        return CircuitBreakerQueueWorkerCoordinator(
            queueOperationAdapter = adapter,
            recoveryScope = recoveryScope,
            queueProcessor = processor,
            schedulerProvider = schedulerProvider,
            clock = clock,
            configuration = configuration,
        )
    }
}
""",
    """        return CircuitBreakerQueueWorkerCoordinator(
            queueOperationAdapter = adapter,
            recoveryScope = recoveryScope,
            queueProcessor = processor,
            schedulerProvider = schedulerProvider,
            clock = clock,
            configuration = configuration,
        )
    }

    /**
     * Creates a circuit-aware worker with separately governed queue and
     * scheduler circuit boundaries.
     *
     * The optional scheduler timeout from [configuration] is applied before
     * scheduler circuit adaptation. A timeout is therefore classified as a
     * scheduler dependency failure, while an accepted schedule remains visible
     * even when its later circuit-state update is not accepted.
     */
    public fun createWithSchedulerCircuit(
        queueProvider: QueueProvider,
        queueExecutionGate: CircuitBreakerExecutionGate,
        recoveryScope: CircuitBreakerScope,
        processingScopes: QueueProcessingCircuitScopes,
        executionHandler: QueueEntryExecutionHandler,
        schedulerProvider: SchedulerProvider,
        schedulerExecutionGate: CircuitBreakerExecutionGate,
        schedulerScope: CircuitBreakerScope,
        clock: DataLoomClock,
        configuration: QueueWorkerConfiguration,
        queueFailureClassifier: CircuitBreakerFailureClassifier =
            QueueCircuitBreakerFailureClassifier,
        schedulerFailureClassifier: CircuitBreakerFailureClassifier =
            DefaultCircuitBreakerFailureClassifier,
    ): CircuitBreakerQueueWorkerCoordinator {
        val queueAdapter = CircuitBreakerQueueOperationAdapter(
            queueProvider = queueProvider,
            executionGate = queueExecutionGate,
            failureClassifier = queueFailureClassifier,
        )
        val processor = CircuitBreakerDurableQueueExecutionProcessor(
            queueOperationAdapter = queueAdapter,
            executionHandler = executionHandler,
            scopes = processingScopes,
        )
        val protectedScheduler = checkNotNull(
            assembleQueueWorkerSchedulerProvider(
                provider = schedulerProvider,
                timeout = configuration.schedulerProviderTimeout,
                clock = clock,
            ),
        )
        val schedulerAdapter = CircuitBreakerRetrySchedulingAdapter(
            schedulerProvider = protectedScheduler,
            providerOperationAdapter = CircuitBreakerProviderOperationAdapter(
                executionGate = schedulerExecutionGate,
                failureClassifier = schedulerFailureClassifier,
            ),
            scope = schedulerScope,
        )
        return CircuitBreakerQueueWorkerCoordinator(
            queueOperationAdapter = queueAdapter,
            recoveryScope = recoveryScope,
            queueProcessor = CircuitBreakerQueueProcessingEngine { request ->
                processor.process(request)
            },
            directSchedulerProvider = null,
            schedulerCircuitAdapter = schedulerAdapter,
            clock = clock,
            configuration = configuration,
        )
    }
}
""",
)


# ---------------------------------------------------------------------------
# Public builder specification for a separately governed scheduler circuit.
# ---------------------------------------------------------------------------

write_new(
    "dataloom-runtime/src/commonMain/kotlin/io/dataloom/runtime/facade/"
    "DataLoomCircuitQueueWorkerSchedulerSpec.kt",
    """package io.dataloom.runtime.facade

import io.dataloom.api.circuit.CircuitBreakerScope
import io.dataloom.api.circuit.CircuitBreakerStateStore
import io.dataloom.runtime.retry.CircuitBreakerConfiguration
import io.dataloom.runtime.retry.CircuitBreakerFailureClassifier
import io.dataloom.runtime.retry.DefaultCircuitBreakerFailureClassifier

/**
 * Explicit scheduler-circuit policy for [DataLoomCircuitQueueWorker].
 *
 * Queue-provider circuit configuration is deliberately not reused. Applications
 * supply the scheduler's own durable state store, deterministic circuit
 * configuration, exact scope, and optional classifier. Construction performs no
 * store access, provider call, timeout execution, clock read, or scheduling.
 */
public class DataLoomCircuitQueueWorkerSchedulerSpec(
    /** Deterministic thresholds, windows, and half-open probe lease. */
    public val circuitBreakerConfiguration: CircuitBreakerConfiguration,

    /** Application-supplied durable state store for the scheduler circuit. */
    public val circuitBreakerStateStore: CircuitBreakerStateStore,

    /** Exact global, workflow, provider, or scheduler.schedule scope. */
    public val scope: CircuitBreakerScope,

    /** Scheduler failure classification used after provider invocation. */
    public val failureClassifier: CircuitBreakerFailureClassifier =
        DefaultCircuitBreakerFailureClassifier,
)
""",
)


# ---------------------------------------------------------------------------
# DataLoomBuilder assembly.
# ---------------------------------------------------------------------------

builder_path = (
    "dataloom-runtime/src/commonMain/kotlin/io/dataloom/runtime/facade/"
    "DataLoomBuilder.kt"
)
replace_once(
    builder_path,
    """import io.dataloom.runtime.retry.QueueCircuitOperation
import io.dataloom.runtime.retry.SynchronizationRetryEvaluator
""",
    """import io.dataloom.runtime.retry.QueueCircuitOperation
import io.dataloom.runtime.retry.SchedulerCircuitOperation
import io.dataloom.runtime.retry.SynchronizationRetryEvaluator
""",
)
replace_once(
    builder_path,
    """    private var queueWorkerSpec: DataLoomQueueWorkerSpec? = null
    private var circuitQueueWorkerSpec: DataLoomCircuitQueueWorkerSpec? = null
    private var queueSubmissionSpecValue: DataLoomQueueSubmissionSpec? = null
""",
    """    private var queueWorkerSpec: DataLoomQueueWorkerSpec? = null
    private var circuitQueueWorkerSpec: DataLoomCircuitQueueWorkerSpec? = null
    private var circuitQueueWorkerSchedulerSpec: DataLoomCircuitQueueWorkerSchedulerSpec? = null
    private var queueSubmissionSpecValue: DataLoomQueueSubmissionSpec? = null
""",
)
replace_once(
    builder_path,
    """    public fun queueWorkerConfiguration(spec: DataLoomQueueWorkerSpec): DataLoomBuilder = apply {
        queueWorkerSpec = spec
        circuitQueueWorkerSpec = null
    }
""",
    """    public fun queueWorkerConfiguration(spec: DataLoomQueueWorkerSpec): DataLoomBuilder = apply {
        queueWorkerSpec = spec
        circuitQueueWorkerSpec = null
        circuitQueueWorkerSchedulerSpec = null
    }
""",
)
replace_once(
    builder_path,
    """    public fun circuitQueueWorkerConfiguration(
        spec: DataLoomCircuitQueueWorkerSpec,
    ): DataLoomBuilder = apply {
        circuitQueueWorkerSpec = spec
        queueWorkerSpec = null
    }

    /**
     * Configures the optional queue-submission capability.
""",
    """    public fun circuitQueueWorkerConfiguration(
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
""",
)
replace_once(
    builder_path,
    """        val bindings = defaultProviderBindings
        val strategyBindings = defaultStrategyProviderBindings
""",
    """        val bindings = defaultProviderBindings
        if (circuitQueueWorkerSchedulerSpec != null && circuitQueueWorkerSpec == null) {
            throw DataLoomBuildException(
                "DataLoomBuilder circuitQueueWorkerSchedulerConfiguration requires " +
                    "circuitQueueWorkerConfiguration.",
            )
        }
        val strategyBindings = defaultStrategyProviderBindings
""",
)
replace_once(
    builder_path,
    """            buildCircuitQueueWorker(
                spec = spec,
                registry = registry,
                bindings = legacyBindings,
                deps = deps,
                executionCoordinator = executionCoordinator,
            )
""",
    """            buildCircuitQueueWorker(
                spec = spec,
                registry = registry,
                bindings = legacyBindings,
                deps = deps,
                executionCoordinator = executionCoordinator,
                schedulerCircuitSpec = circuitQueueWorkerSchedulerSpec,
            )
""",
)
replace_once(
    builder_path,
    """    private fun buildCircuitQueueWorker(
        spec: DataLoomCircuitQueueWorkerSpec,
        registry: ProviderRegistry,
        bindings: SynchronizationProviderBindings,
        deps: RuntimeDependencies,
        executionCoordinator: SynchronizationExecutionCoordinator,
    ): DataLoomCircuitQueueWorker {
""",
    """    private fun buildCircuitQueueWorker(
        spec: DataLoomCircuitQueueWorkerSpec,
        registry: ProviderRegistry,
        bindings: SynchronizationProviderBindings,
        deps: RuntimeDependencies,
        executionCoordinator: SynchronizationExecutionCoordinator,
        schedulerCircuitSpec: DataLoomCircuitQueueWorkerSchedulerSpec?,
    ): DataLoomCircuitQueueWorker {
""",
)
replace_once(
    builder_path,
    """        val schedulerProvider: SchedulerProvider? = bindings.schedulerProviderId?.let { schedulerId ->
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

        val workerSpec = spec.workerSpec
""",
    """        val schedulerProvider: SchedulerProvider? = bindings.schedulerProviderId?.let { schedulerId ->
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
""",
)
replace_once(
    builder_path,
    """        val circuitCoordinator = CircuitBreakerCoordinator(
            configuration = spec.circuitBreakerConfiguration,
            clock = deps.clock,
            stateStore = spec.circuitBreakerStateStore,
        )
        val workerCoordinator = CircuitBreakerQueueWorkerRuntime.create(
            queueProvider = protectedQueueProvider,
            executionGate = CircuitBreakerExecutionGate(circuitCoordinator),
            recoveryScope = spec.recoveryScope,
            processingScopes = spec.processingScopes,
            executionHandler = executionHandler,
            schedulerProvider = schedulerProvider,
            clock = deps.clock,
            configuration = workerSpec.configuration,
            failureClassifier = spec.failureClassifier,
        )
        return DefaultDataLoomCircuitQueueWorker(workerCoordinator)
    }

    private fun validateCircuitQueueWorkerScope(
""",
    """        val queueCircuitCoordinator = CircuitBreakerCoordinator(
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
""",
)
replace_once(
    builder_path,
    """    /**
     * Assembles the queue-submission capability.
""",
    """    private fun validateCircuitQueueWorkerSchedulerScope(
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
""",
)
replace_once(
    builder_path,
    """ * - [queueWorkerConfiguration] or [circuitQueueWorkerConfiguration] was
 *   called but the queue binding is missing or invalid.
""",
    """ * - [queueWorkerConfiguration] or [circuitQueueWorkerConfiguration] was
 *   called but the queue binding is missing or invalid.
 * - [circuitQueueWorkerSchedulerConfiguration] was called without a
 *   circuit-aware worker or valid scheduler binding.
""",
)
