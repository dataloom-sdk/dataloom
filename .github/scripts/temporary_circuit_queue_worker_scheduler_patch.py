from pathlib import Path


def read(path: str) -> str:
    return Path(path).read_text()


def write(path: str, text: str) -> None:
    Path(path).write_text(text)


def replace_once(path: str, old: str, new: str) -> None:
    text = read(path)
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"Expected one match in {path}, found {count}")
    write(path, text.replace(old, new, 1))


def insert_before_last_brace(path: str, block: str) -> None:
    text = read(path)
    index = text.rfind("\n}")
    if index < 0:
        raise SystemExit(f"Could not find final brace in {path}")
    write(path, text[:index] + "\n" + block.rstrip() + "\n" + text[index:])


def insert_constructor_parameter(
    path: str,
    declaration: str,
    parameter: str,
) -> None:
    text = read(path)
    start = text.find(declaration)
    if start < 0:
        raise SystemExit(f"Could not find {declaration!r} in {path}")
    end = text.find("\n)", start)
    if end < 0:
        raise SystemExit(f"Could not find constructor end in {path}")
    if parameter.strip() in text[start:end]:
        raise SystemExit(f"Parameter already present in {path}")
    write(path, text[:end] + "\n" + parameter.rstrip() + text[end:])


# ---------------------------------------------------------------------------
# Scheduler circuit adapter identity
# ---------------------------------------------------------------------------

adapter = (
    "dataloom-runtime/src/commonMain/kotlin/io/dataloom/runtime/retry/"
    "CircuitBreakerRetrySchedulingAdapter.kt"
)
replace_once(
    adapter,
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


# ---------------------------------------------------------------------------
# Exact scheduler evidence result
# ---------------------------------------------------------------------------

scheduling_result = (
    "dataloom-runtime/src/commonMain/kotlin/io/dataloom/runtime/worker/"
    "QueueWorkerSchedulingResult.kt"
)
replace_once(
    scheduling_result,
    "import io.dataloom.api.scheduling.ScheduleReceipt\n",
    """import io.dataloom.api.scheduling.ScheduleReceipt
import io.dataloom.runtime.retry.CircuitBreakerExecutionResult
""",
)
insert_before_last_brace(
    scheduling_result,
    """    /**
     * The follow-up scheduler call was protected by an explicitly configured
     * durable circuit.
     *
     * [executionResult] preserves pre-execution rejection, provider outcome,
     * and the exact later circuit-recording result. An executed successful
     * schedule receipt must not be hidden or automatically submitted again when
     * circuit recording is unconfirmed.
     */
    public data class CircuitProtected(
        public val executionResult: CircuitBreakerExecutionResult<ScheduleReceipt>,
        public val plan: QueueWorkerWakeUpPlan.Schedule,
    ) : QueueWorkerSchedulingResult
""",
)
replace_once(
    scheduling_result,
    """ * - [SchedulerFailed] — the provider returned a canonical
 *   [io.dataloom.api.error.DataLoomError]. Durable queue transitions have
 *   already been persisted and must not be rolled back. Another host trigger
 *   may be required to wake the queue worker.
""",
    """ * - [SchedulerFailed] — the direct provider returned a canonical
 *   [io.dataloom.api.error.DataLoomError]. Durable queue transitions have
 *   already been persisted and must not be rolled back. Another host trigger
 *   may be required to wake the queue worker.
 * - [CircuitProtected] — an explicitly configured scheduler circuit preserved
 *   the exact permission, provider, and post-execution recording evidence.
""",
)


# ---------------------------------------------------------------------------
# Circuit-aware worker coordinator
# ---------------------------------------------------------------------------

coordinator = (
    "dataloom-runtime/src/commonMain/kotlin/io/dataloom/runtime/worker/"
    "CircuitBreakerQueueWorkerCoordinator.kt"
)
replace_once(
    coordinator,
    """import io.dataloom.runtime.retry.CircuitBreakerExecutionResult
import io.dataloom.runtime.retry.CircuitBreakerQueueOperationAdapter
""",
    """import io.dataloom.runtime.retry.CircuitBreakerExecutionResult
import io.dataloom.runtime.retry.CircuitBreakerProviderOperationAdapter
import io.dataloom.runtime.retry.CircuitBreakerQueueOperationAdapter
import io.dataloom.runtime.retry.CircuitBreakerRetrySchedulingAdapter
""",
)
replace_once(
    coordinator,
    """    schedulerProvider: SchedulerProvider?,
    private val clock: DataLoomClock,
""",
    """    schedulerProvider: SchedulerProvider?,
    private val schedulerCircuit: CircuitBreakerQueueWorkerSchedulerCircuit? = null,
    private val clock: DataLoomClock,
""",
)
replace_once(
    coordinator,
    """    private val schedulerProvider: SchedulerProvider? = assembleQueueWorkerSchedulerProvider(
        provider = schedulerProvider,
        timeout = configuration.schedulerProviderTimeout,
        clock = clock,
    )

    init {
""",
    """    private val schedulerProvider: SchedulerProvider? = assembleQueueWorkerSchedulerProvider(
        provider = schedulerProvider,
        timeout = configuration.schedulerProviderTimeout,
        clock = clock,
    )

    private val schedulerCircuitAdapter: CircuitBreakerRetrySchedulingAdapter? =
        schedulerCircuit?.let { circuit ->
            val protectedScheduler = requireNotNull(this.schedulerProvider) {
                "A queue-worker scheduler circuit requires a SchedulerProvider."
            }
            CircuitBreakerRetrySchedulingAdapter(
                schedulerProvider = protectedScheduler,
                providerOperationAdapter = CircuitBreakerProviderOperationAdapter(
                    executionGate = circuit.executionGate,
                    failureClassifier = circuit.failureClassifier,
                ),
                scope = circuit.scope,
            )
        }

    init {
""",
)
replace_once(
    coordinator,
    """        schedulerProvider = schedulerProvider,
        clock = clock,
""",
    """        schedulerProvider = schedulerProvider,
        schedulerCircuit = null,
        clock = clock,
""",
)
replace_once(
    coordinator,
    """        return when (val result = scheduler.schedule(request)) {
            is ProviderOperationResult.Success -> QueueWorkerSchedulingResult.Scheduled(
""",
    """        val circuitAdapter = schedulerCircuitAdapter
        if (circuitAdapter != null) {
            return QueueWorkerSchedulingResult.CircuitProtected(
                executionResult = circuitAdapter.schedule(request),
                plan = schedulePlan,
            )
        }
        return when (val result = scheduler.schedule(request)) {
            is ProviderOperationResult.Success -> QueueWorkerSchedulingResult.Scheduled(
""",
)


# ---------------------------------------------------------------------------
# Runtime factory
# ---------------------------------------------------------------------------

runtime = (
    "dataloom-runtime/src/commonMain/kotlin/io/dataloom/runtime/worker/"
    "CircuitBreakerQueueWorkerRuntime.kt"
)
replace_once(
    runtime,
    """        failureClassifier: CircuitBreakerFailureClassifier =
            QueueCircuitBreakerFailureClassifier,
    ): CircuitBreakerQueueWorkerCoordinator {
""",
    """        failureClassifier: CircuitBreakerFailureClassifier =
            QueueCircuitBreakerFailureClassifier,
        schedulerCircuit: CircuitBreakerQueueWorkerSchedulerCircuit? = null,
    ): CircuitBreakerQueueWorkerCoordinator {
""",
)
replace_once(
    runtime,
    """            schedulerProvider = schedulerProvider,
            clock = clock,
            configuration = configuration,
""",
    """            schedulerProvider = schedulerProvider,
            schedulerCircuit = schedulerCircuit,
            clock = clock,
            configuration = configuration,
""",
)


# ---------------------------------------------------------------------------
# Builder-facing configuration
# ---------------------------------------------------------------------------

worker_spec = (
    "dataloom-runtime/src/commonMain/kotlin/io/dataloom/runtime/facade/"
    "DataLoomCircuitQueueWorkerSpec.kt"
)
insert_constructor_parameter(
    worker_spec,
    "public class DataLoomCircuitQueueWorkerSpec(",
    """    /** Optional independently governed circuit for the follow-up scheduler call. */
    public val schedulerCircuit: DataLoomCircuitQueueWorkerSchedulerSpec? = null,
""",
)

builder = (
    "dataloom-runtime/src/commonMain/kotlin/io/dataloom/runtime/facade/"
    "DataLoomBuilder.kt"
)
replace_once(
    builder,
    "import io.dataloom.runtime.retry.QueueCircuitOperation\n",
    """import io.dataloom.runtime.retry.QueueCircuitOperation
import io.dataloom.runtime.retry.SchedulerCircuitOperation
""",
)
replace_once(
    builder,
    "import io.dataloom.runtime.worker.CircuitBreakerQueueWorkerRuntime\n",
    """import io.dataloom.runtime.worker.CircuitBreakerQueueWorkerRuntime
import io.dataloom.runtime.worker.CircuitBreakerQueueWorkerSchedulerCircuit
""",
)
replace_once(
    builder,
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
    """        val schedulerCircuitSpec = spec.schedulerCircuit
        val schedulerProvider: SchedulerProvider? = if (schedulerCircuitSpec == null) {
            bindings.schedulerProviderId?.let { schedulerId ->
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
        } else {
            val schedulerId = bindings.schedulerProviderId
                ?: throw DataLoomBuildException(
                    "DataLoomBuilder circuitQueueWorkerConfiguration scheduler circuit " +
                        "requires a scheduler provider binding.",
                )
            val candidate = registry.findById(schedulerId)
                ?: throw DataLoomBuildException(
                    "DataLoomBuilder circuitQueueWorkerConfiguration: scheduler provider " +
                        "'${schedulerId.value}' not found in registry.",
                )
            if (candidate.descriptor.type != ProviderType.SCHEDULER) {
                throw DataLoomBuildException(
                    "DataLoomBuilder circuitQueueWorkerConfiguration: provider " +
                        "'${schedulerId.value}' has type ${candidate.descriptor.type} " +
                        "but expected ${ProviderType.SCHEDULER}.",
                )
            }
            val provider = candidate as? SchedulerProvider
                ?: throw DataLoomBuildException(
                    "DataLoomBuilder circuitQueueWorkerConfiguration: provider " +
                        "'${schedulerId.value}' does not implement the SchedulerProvider contract.",
                )
            validateCircuitQueueWorkerSchedulerScope(
                scope = schedulerCircuitSpec.scope,
                schedulerProviderId = schedulerId,
            )
            provider
        }

        val workerSpec = spec.workerSpec
""",
)
replace_once(
    builder,
    """        val workerCoordinator = CircuitBreakerQueueWorkerRuntime.create(
            queueProvider = protectedQueueProvider,
""",
    """        val schedulerCircuit = schedulerCircuitSpec?.let { schedulerSpec ->
            CircuitBreakerQueueWorkerSchedulerCircuit(
                executionGate = CircuitBreakerExecutionGate(
                    CircuitBreakerCoordinator(
                        configuration = schedulerSpec.circuitBreakerConfiguration,
                        clock = deps.clock,
                        stateStore = schedulerSpec.circuitBreakerStateStore,
                    ),
                ),
                scope = schedulerSpec.scope,
                failureClassifier = schedulerSpec.failureClassifier,
            )
        }
        val workerCoordinator = CircuitBreakerQueueWorkerRuntime.create(
            queueProvider = protectedQueueProvider,
""",
)
replace_once(
    builder,
    """            configuration = workerSpec.configuration,
            failureClassifier = spec.failureClassifier,
        )
""",
    """            configuration = workerSpec.configuration,
            failureClassifier = spec.failureClassifier,
            schedulerCircuit = schedulerCircuit,
        )
""",
)
replace_once(
    builder,
    """    /**
     * Assembles the queue-submission capability.
""",
    """    private fun validateCircuitQueueWorkerSchedulerScope(
        scope: io.dataloom.api.circuit.CircuitBreakerScope,
        schedulerProviderId: io.dataloom.api.provider.ProviderId,
    ) {
        if (scope.providerId != null && scope.providerId != schedulerProviderId) {
            throw DataLoomBuildException(
                "DataLoomBuilder circuitQueueWorkerConfiguration scheduler scope provider " +
                    "must match scheduler provider '${schedulerProviderId.value}'.",
            )
        }
        if (
            scope.operation != null &&
            scope.operation != SchedulerCircuitOperation.SCHEDULE.retryOperation
        ) {
            throw DataLoomBuildException(
                "DataLoomBuilder circuitQueueWorkerConfiguration scheduler scope operation " +
                    "must be '${SchedulerCircuitOperation.SCHEDULE.retryOperation.value}'.",
            )
        }
    }

    /**
     * Assembles the queue-submission capability.
""",
)


# ---------------------------------------------------------------------------
# Documentation index and integration notes
# ---------------------------------------------------------------------------

readme = "docs/api/README.md"
replace_once(
    readme,
    """| [Circuit-aware queue worker](./circuit-queue-worker.md) | Partial V1 subsystem | Circuit-protected recovery, bounded processing, and scheduler isolation with explicit terminal evidence. |
| [Builder circuit-aware queue worker](./builder-circuit-queue-worker.md) | Partial V1 subsystem | Explicit facade assembly, durable state-store injection, scope validation, and mutually exclusive worker selection. |
""",
    """| [Circuit-aware queue worker](./circuit-queue-worker.md) | Partial V1 subsystem | Circuit-protected recovery, bounded processing, and scheduler isolation with explicit terminal evidence. |
| [Circuit-aware queue-worker scheduling](./circuit-queue-worker-scheduler.md) | Partial V1 subsystem | Independent scheduler circuit policy, timeout composition, and uncollapsed accepted-schedule evidence. |
| [Builder circuit-aware queue worker](./builder-circuit-queue-worker.md) | Partial V1 subsystem | Explicit facade assembly, durable state-store injection, scope validation, and mutually exclusive worker selection. |
""",
)
replace_once(
    readme,
    """circuit-aware recovery/worker coordination now exist. Explicit builder adoption,
scheduler-circuit policy, KMP iOS persistence, and end-to-end qualification
remain open.
""",
    """circuit-aware recovery/worker coordination, explicit builder adoption, and
independently configured scheduler-circuit evidence now exist. Transport/storage
assembly, KMP iOS persistence, and end-to-end qualification remain open.
""",
)
replace_once(
    readme,
    """V1 retry work still requires explicit builder adoption of circuit-aware queue
execution, scheduler-circuit policy, complete transport/storage circuit assembly,
protocol-specific timeout
""",
    """V1 retry work still requires complete transport/storage circuit assembly,
protocol-specific timeout
""",
)

circuit_worker_doc = "docs/api/circuit-queue-worker.md"
replace_once(
    circuit_worker_doc,
    """> processing, and follow-up scheduling now have an additive circuit-aware
> coordination path. Builder adoption, scheduler-circuit policy, production
> KMP iOS persistence, observability, administration, and end-to-end
> qualification remain open.
""",
    """> processing, and follow-up scheduling now have an additive circuit-aware
> coordination path. Builder adoption and an independently configured scheduler
> circuit are available; transport/storage assembly, production KMP iOS
> persistence, observability, administration, and end-to-end qualification
> remain open.
""",
)
replace_once(
    circuit_worker_doc,
    """- DataLoomBuilder adoption for explicit queue circuit policy;
- circuit protection for queue-worker scheduling where configured;
- transport and storage circuit/timeout assembly;
""",
    """- transport and storage circuit/timeout assembly;
""",
)

builder_worker_doc = "docs/api/builder-circuit-queue-worker.md"
replace_once(
    builder_worker_doc,
    """## Current limitations
""",
    """## Optional scheduler circuit

`DataLoomCircuitQueueWorkerSpec.schedulerCircuit` may supply a separate
`DataLoomCircuitQueueWorkerSchedulerSpec`. The scheduler policy has its own
configuration, durable state store, scope, and classifier. The builder requires
a valid bound scheduler, validates `scheduler.schedule`, and composes the
existing scheduler timeout inside the circuit. Queue circuit policy is never
reused implicitly.

The resulting worker exposes exact scheduler permission, provider, receipt, and
post-execution record evidence through
`QueueWorkerSchedulingResult.CircuitProtected`.

## Current limitations
""",
)

circuit_gate_doc = "docs/api/circuit-execution-gate.md"
replace_once(
    circuit_gate_doc,
    """`CircuitBreakerRetrySchedulingAdapter` applies the provider adapter to
`SchedulerProvider.schedule`. A provider-scoped circuit must identify the same
scheduler provider; global and workflow scopes remain valid explicit choices.
""",
    """`CircuitBreakerRetrySchedulingAdapter` applies the provider adapter to
`SchedulerProvider.schedule`. A provider-scoped circuit must identify the same
scheduler provider, and an operation-scoped circuit must identify the stable
`scheduler.schedule` operation. Global and workflow scopes remain valid explicit
choices.
""",
)

facade_doc = "docs/api/dataloom-facade.md"
replace_once(
    facade_doc,
    """| `queueWorkerConfiguration(spec)` | Configures the optional queue-worker capability. |
""",
    """| `queueWorkerConfiguration(spec)` | Configures the optional direct queue-worker capability. |
| `circuitQueueWorkerConfiguration(spec)` | Configures the optional circuit-aware worker, including an optional independent scheduler circuit. |
""",
)
