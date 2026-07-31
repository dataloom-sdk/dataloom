from pathlib import Path


def replace_once(path: str, old: str, new: str) -> None:
    file = Path(path)
    text = file.read_text()
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"Expected one match in {path}, found {count}")
    file.write_text(text.replace(old, new, 1))


# ---------------------------------------------------------------------------
# Public facade
# ---------------------------------------------------------------------------

data_loom = "dataloom-runtime/src/commonMain/kotlin/io/dataloom/runtime/facade/DataLoom.kt"
replace_once(
    data_loom,
    """    public val queueWorker: DataLoomQueueWorker?

    /**
     * The optional queue-submission capability.
""",
    """    public val queueWorker: DataLoomQueueWorker?

    /**
     * The optional circuit-aware queue-worker capability.
     *
     * `null` unless
     * [DataLoomBuilder.circuitQueueWorkerConfiguration] was the effective
     * queue-worker configuration at build time. Direct and circuit-aware worker
     * capabilities are mutually exclusive; the most recent builder method wins.
     *
     * A default getter preserves source compatibility for custom pre-V1
     * [DataLoom] implementations.
     */
    public val circuitQueueWorker: DataLoomCircuitQueueWorker?
        get() = null

    /**
     * The optional queue-submission capability.
""",
)
replace_once(
    data_loom,
    """ * [synchronize] or [queueWorker] access. Synchronization before [initialize]
""",
    """ * [synchronize], [queueWorker], or [circuitQueueWorker] access.
 * Synchronization before [initialize]
""",
)

default_data_loom = (
    "dataloom-runtime/src/commonMain/kotlin/io/dataloom/runtime/facade/DefaultDataLoom.kt"
)
replace_once(
    default_data_loom,
    """ * @param queueWorker the optional queue-worker capability; `null` when not
 *   configured.
 * @param queueSubmission the optional queue-submission capability; `null` when
""",
    """ * @param queueWorker the optional direct queue-worker capability; `null`
 *   when not configured.
 * @param circuitQueueWorker the optional circuit-aware queue-worker capability;
 *   `null` when not configured.
 * @param queueSubmission the optional queue-submission capability; `null` when
""",
)
replace_once(
    default_data_loom,
    """    override val queueWorker: DataLoomQueueWorker?,
    override val queueSubmission: DataLoomQueueSubmission?,
""",
    """    override val queueWorker: DataLoomQueueWorker?,
    override val circuitQueueWorker: DataLoomCircuitQueueWorker?,
    override val queueSubmission: DataLoomQueueSubmission?,
""",
)

# ---------------------------------------------------------------------------
# Builder imports, state, setters, build graph, and helpers
# ---------------------------------------------------------------------------

builder = "dataloom-runtime/src/commonMain/kotlin/io/dataloom/runtime/facade/DataLoomBuilder.kt"
replace_once(
    builder,
    """import io.dataloom.runtime.retry.SynchronizationRetryEvaluator
""",
    """import io.dataloom.runtime.retry.CircuitBreakerCoordinator
import io.dataloom.runtime.retry.CircuitBreakerExecutionGate
import io.dataloom.runtime.retry.QueueCircuitOperation
import io.dataloom.runtime.retry.SynchronizationRetryEvaluator
""",
)
replace_once(
    builder,
    """import io.dataloom.runtime.worker.QueueWorkerCoordinator
import io.dataloom.runtime.worker.QueueWorkerProviderTimeoutRuntime
""",
    """import io.dataloom.runtime.worker.CircuitBreakerQueueWorkerRuntime
import io.dataloom.runtime.worker.QueueWorkerCoordinator
import io.dataloom.runtime.worker.QueueWorkerProviderTimeoutRuntime
import io.dataloom.runtime.worker.assembleQueueWorkerQueueProvider
""",
)
replace_once(
    builder,
    """    private var queueWorkerSpec: DataLoomQueueWorkerSpec? = null
    private var queueSubmissionSpecValue: DataLoomQueueSubmissionSpec? = null
""",
    """    private var queueWorkerSpec: DataLoomQueueWorkerSpec? = null
    private var circuitQueueWorkerSpec: DataLoomCircuitQueueWorkerSpec? = null
    private var queueSubmissionSpecValue: DataLoomQueueSubmissionSpec? = null
""",
)
replace_once(
    builder,
    """    public fun queueWorkerConfiguration(spec: DataLoomQueueWorkerSpec): DataLoomBuilder = apply {
        queueWorkerSpec = spec
    }

    /**
     * Configures the optional queue-submission capability.
""",
    """    public fun queueWorkerConfiguration(spec: DataLoomQueueWorkerSpec): DataLoomBuilder = apply {
        queueWorkerSpec = spec
        circuitQueueWorkerSpec = null
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
     * Configures the optional queue-submission capability.
""",
)
replace_once(
    builder,
    """        // --- 10. Build optional queue submission ---
        val queueSubmission = queueSubmissionSpecValue?.let { spec ->
""",
    """        // --- 10. Build optional circuit-aware queue worker ---
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
            )
        }

        // --- 11. Build optional queue submission ---
        val queueSubmission = queueSubmissionSpecValue?.let { spec ->
""",
)
replace_once(
    builder,
    """            queueWorker = queueWorker,
            queueSubmission = queueSubmission,
""",
    """            queueWorker = queueWorker,
            circuitQueueWorker = circuitQueueWorker,
            queueSubmission = queueSubmission,
""",
)

helper_marker = """    /**
     * Assembles the queue-submission capability.
"""
helper = """    /**
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
        )
        val protectedQueueProvider = assembleQueueWorkerQueueProvider(
            queueProvider = queueProvider,
            clock = deps.clock,
            providerTimeout = workerSpec.queueProviderTimeout,
        )
        val circuitCoordinator = CircuitBreakerCoordinator(
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

"""
replace_once(builder, helper_marker, helper + helper_marker)

# Update the high-level builder documentation.
replace_once(
    builder,
    """ * - [queueWorkerConfiguration] was called but the queue binding is missing or
 *   invalid.
""",
    """ * - [queueWorkerConfiguration] or [circuitQueueWorkerConfiguration] was
 *   called but the queue binding is missing or invalid.
""",
)

# ---------------------------------------------------------------------------
# API documentation
# ---------------------------------------------------------------------------

readme = "docs/api/README.md"
replace_once(
    readme,
    """| [Circuit-aware queue worker](./circuit-queue-worker.md) | Partial V1 subsystem | Circuit-protected recovery, bounded processing, and scheduler isolation with explicit terminal evidence. |
| [Queue submission](./queue-submission.md) | Available foundation | Application-owned work encoding and durable enqueue with optional timeout and additive circuit-aware execution. |
""",
    """| [Circuit-aware queue worker](./circuit-queue-worker.md) | Partial V1 subsystem | Circuit-protected recovery, bounded processing, and scheduler isolation with explicit terminal evidence. |
| [Builder circuit-aware queue worker](./builder-circuit-queue-worker.md) | Partial V1 subsystem | Explicit facade assembly, durable state-store injection, scope validation, and mutually exclusive worker selection. |
| [Queue submission](./queue-submission.md) | Available foundation | Application-owned work encoding and durable enqueue with optional timeout and additive circuit-aware execution. |
""",
)
replace_once(
    readme,
    """circuit-aware recovery/worker coordination now exist. Explicit builder adoption,
scheduler-circuit policy, KMP iOS persistence, and end-to-end qualification
remain open.
""",
    """circuit-aware recovery/worker coordination and explicit builder/facade adoption
now exist. Scheduler-circuit policy, KMP iOS persistence, and end-to-end
qualification remain open.
""",
)
replace_once(
    readme,
    """V1 retry work still requires explicit builder adoption of circuit-aware queue
execution, scheduler-circuit policy, complete transport/storage circuit assembly,
""",
    """V1 retry work still requires scheduler-circuit policy, complete
transport/storage circuit assembly,
""",
)

facade_doc = "docs/api/dataloom-facade.md"
replace_once(
    facade_doc,
    """    Facade --> Submission[Optional queue submission]
    Facade --> Worker[Optional queue worker]
""",
    """    Facade --> Submission[Optional queue submission]
    Facade --> Worker[Optional direct queue worker]
    Facade --> CircuitWorker[Optional circuit-aware queue worker]
""",
)
replace_once(
    facade_doc,
    """- `DataLoomQueueWorker` — optional narrow queue-worker capability
- `DataLoomBuildException` — thrown when `DataLoomBuilder.build()` fails
""",
    """- `DataLoomQueueWorker` — optional narrow direct queue-worker capability
- `DataLoomCircuitQueueWorker` — optional circuit-aware queue-worker capability
- `DataLoomCircuitQueueWorkerSpec` — explicit circuit-worker builder specification
- `DataLoomBuildException` — thrown when `DataLoomBuilder.build()` fails
""",
)
replace_once(
    facade_doc,
    """    public val queueWorker: DataLoomQueueWorker?
    public val queueSubmission: DataLoomQueueSubmission?
""",
    """    public val queueWorker: DataLoomQueueWorker?
    public val circuitQueueWorker: DataLoomCircuitQueueWorker?
    public val queueSubmission: DataLoomQueueSubmission?
""",
)
replace_once(
    facade_doc,
    """### queueSubmission
""",
    """### circuitQueueWorker

`null` unless `circuitQueueWorkerConfiguration(...)` was the effective worker
configuration. Direct and circuit-aware workers are mutually exclusive; the
most recent worker configuration method wins. The capability returns the full
`CircuitBreakerQueueWorkerRunResult` without mapping it into the direct worker
result model.

See [DataLoomBuilder circuit-aware queue worker](./builder-circuit-queue-worker.md).

### queueSubmission
""",
)
replace_once(
    facade_doc,
    """| `queueWorkerConfiguration(spec)` | Configures the optional queue-worker capability. |
| `queueSubmissionEncoder(e)` | Configures direct queue submission with no provider timeout. |
""",
    """| `queueWorkerConfiguration(spec)` | Configures the direct queue-worker capability and clears circuit-worker configuration. |
| `circuitQueueWorkerConfiguration(spec)` | Configures the circuit-aware queue worker and clears direct-worker configuration. |
| `queueSubmissionEncoder(e)` | Configures direct queue submission with no provider timeout. |
""",
)

circuit_worker_doc = "docs/api/circuit-queue-worker.md"
replace_once(
    circuit_worker_doc,
    """> coordination path. Builder adoption, scheduler-circuit policy, production
> KMP iOS persistence, observability, administration, and end-to-end
""",
    """> coordination path with explicit DataLoomBuilder/facade adoption.
> Scheduler-circuit policy, production KMP iOS persistence, observability,
> administration, and end-to-end
""",
)
replace_once(
    circuit_worker_doc,
    """## Current limitations
""",
    """## DataLoomBuilder adoption

Applications can expose this capability through
`DataLoomBuilder.circuitQueueWorkerConfiguration(...)` and
`DataLoom.circuitQueueWorker`. The builder requires an explicit durable circuit
state store and exact recovery/acquisition/transition scopes. See
[DataLoomBuilder circuit-aware queue worker](./builder-circuit-queue-worker.md).

## Current limitations
""",
)
replace_once(
    circuit_worker_doc,
    """- DataLoomBuilder adoption for explicit queue circuit policy;
- circuit protection for queue-worker scheduling where configured;
""",
    """- circuit protection for queue-worker scheduling where configured;
""",
)
