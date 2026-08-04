from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def read(path: str) -> str:
    return (ROOT / path).read_text()


def write(path: str, content: str) -> None:
    (ROOT / path).write_text(content.rstrip() + "\n")


def replace_once(path: str, old: str, new: str) -> None:
    content = read(path)
    count = content.count(old)
    if count != 1:
        raise SystemExit(f"Expected one queued execution match in {path}, found {count}: {old[:150]!r}")
    write(path, content.replace(old, new, 1))


direct = (
    "dataloom-runtime/src/commonMain/kotlin/io/dataloom/runtime/queue/"
    "QueuedSynchronizationExecutionHandler.kt"
)
replace_once(
    direct,
    "import io.dataloom.api.queue.QueueFailureDisposition\n",
    """import io.dataloom.api.provider.StrategyProviderBindings
import io.dataloom.api.queue.QueueFailureDisposition
""",
)
replace_once(
    direct,
    "import io.dataloom.runtime.retry.WorkflowTimeoutStateExecutor\n",
    """import io.dataloom.runtime.retry.WorkflowTimeoutStateExecutor
import io.dataloom.runtime.strategy.AcceptedStrategyPlanExecutionCoordinator
""",
)
replace_once(
    direct,
    """    private val clock: DataLoomClock? = null,
    private val workflowTimeoutExecutor: WorkflowTimeoutStateExecutor? = null,
) : QueueEntryExecutionHandler {
""",
    """    private val clock: DataLoomClock? = null,
    private val workflowTimeoutExecutor: WorkflowTimeoutStateExecutor? = null,
    private val acceptedStrategyPlanCoordinator: AcceptedStrategyPlanExecutionCoordinator? = null,
) : QueueEntryExecutionHandler {
    private val strategyOutcomeMapper = StrategyQueueExecutionOutcomeMapper(
        retryEvaluator = retryEvaluator,
        retryOperation = retryOperation,
    )
""",
)
replace_once(
    direct,
    """        QueuedStrategyDecisionCorrespondence.validate(entry, work)?.let { error ->
            return QueueEntryExecutionOutcome.Failed(
                error = error,
                disposition = QueueFailureDisposition.FAILED,
            )
        }

        // Step 4–5: Execute synchronization; map coordinator rejections.
""",
    """        QueuedStrategyDecisionCorrespondence.validate(entry, work)?.let { error ->
            return QueueEntryExecutionOutcome.Failed(
                error = error,
                disposition = QueueFailureDisposition.FAILED,
            )
        }

        val acceptedPlan = work.strategyPlan
        if (acceptedPlan != null) {
            val coordinator = acceptedStrategyPlanCoordinator
                ?: return QueueEntryExecutionOutcome.Failed(
                    error = AcceptedStrategyPlanCoordinatorMissingError(),
                    disposition = QueueFailureDisposition.FAILED,
                )
            val decision = work.strategyDecision
                ?: return QueueEntryExecutionOutcome.Failed(
                    error = AcceptedStrategyDecisionMissingError(),
                    disposition = QueueFailureDisposition.FAILED,
                )
            val strategyResult = when (val timedExecution = executeQueuedWorkflowWithTimeout(
                entry = entry,
                timeoutExecutor = workflowTimeoutExecutor,
            ) {
                coordinator.execute(
                    request = work.request,
                    decision = decision,
                    acceptedPlan = acceptedPlan,
                    bindings = work.bindings.toStrategyProviderBindings(),
                )
            }) {
                is QueuedWorkflowTimeoutExecution.Completed -> timedExecution.value
                is QueuedWorkflowTimeoutExecution.Failed -> {
                    return QueueEntryExecutionOutcome.Failed(
                        error = timedExecution.error,
                        disposition = QueueFailureDisposition.FAILED,
                    )
                }
            }
            return strategyOutcomeMapper.map(strategyResult, entry)
        }

        // Step 4–5: Execute synchronization; map coordinator rejections.
""",
)
replace_once(
    direct,
    """    private class StructuralRejectionError(
""",
    """    private data class AcceptedStrategyPlanCoordinatorMissingError(
        override val code: ErrorCode =
            ErrorCode("DL-Q-ACCEPTED-PLAN-COORDINATOR-NOT-CONFIGURED"),
        override val category: ErrorCategory = ErrorCategory.CONFIGURATION,
        override val severity: ErrorSeverity = ErrorSeverity.ERROR,
        override val recoverability: Recoverability = Recoverability.NON_RECOVERABLE,
        override val message: String =
            "Queued accepted-plan execution requires the accepted-plan coordinator.",
        override val cause: Throwable? = null,
    ) : DataLoomError

    private data class AcceptedStrategyDecisionMissingError(
        override val code: ErrorCode =
            ErrorCode("DL-Q-ACCEPTED-PLAN-DECISION-MISSING"),
        override val category: ErrorCategory = ErrorCategory.CONFIGURATION,
        override val severity: ErrorSeverity = ErrorSeverity.ERROR,
        override val recoverability: Recoverability = Recoverability.NON_RECOVERABLE,
        override val message: String =
            "Queued accepted-plan execution requires the durable strategy decision.",
        override val cause: Throwable? = null,
    ) : DataLoomError

    private class StructuralRejectionError(
""",
)
content = read(direct)
content += """

internal fun io.dataloom.api.provider.SynchronizationProviderBindings.toStrategyProviderBindings():
    StrategyProviderBindings = StrategyProviderBindings(
        storageProviderId = storageProviderId,
        transportProviderId = transportProviderId,
        schedulerProviderId = schedulerProviderId,
        connectivityProviderId = connectivityProviderId,
        queueProviderId = queueProviderId,
    )
"""
write(direct, content)

protected = (
    "dataloom-runtime/src/commonMain/kotlin/io/dataloom/runtime/queue/"
    "ProviderProtectedQueuedSynchronizationExecutionHandler.kt"
)
replace_once(
    protected,
    "import io.dataloom.runtime.facade.DataLoomProtectedSynchronization\n",
    """import io.dataloom.runtime.facade.DataLoomProtectedStrategySynchronization
import io.dataloom.runtime.facade.DataLoomProtectedSynchronization
""",
)
replace_once(
    protected,
    """    private val clock: DataLoomClock? = null,
    private val workflowTimeoutExecutor: WorkflowTimeoutStateExecutor? = null,
) {
""",
    """    private val clock: DataLoomClock? = null,
    private val workflowTimeoutExecutor: WorkflowTimeoutStateExecutor? = null,
    private val protectedStrategySynchronization: DataLoomProtectedStrategySynchronization? = null,
) {
    private val strategyOutcomeMapper = StrategyQueueExecutionOutcomeMapper(
        retryEvaluator = retryEvaluator,
        retryOperation = retryOperation,
    )
""",
)
replace_once(
    protected,
    """        QueuedStrategyDecisionCorrespondence.validate(entry, work)?.let { error ->
            return localFailure(entry, error)
        }

        val protectedExecution = when (val timedExecution = executeQueuedWorkflowWithTimeout(
""",
    """        QueuedStrategyDecisionCorrespondence.validate(entry, work)?.let { error ->
            return localFailure(entry, error)
        }

        val acceptedPlan = work.strategyPlan
        if (acceptedPlan != null) {
            val protectedStrategy = protectedStrategySynchronization
                ?: return localFailure(entry, AcceptedStrategyProtectionMissingError())
            val decision = work.strategyDecision
                ?: return localFailure(entry, AcceptedStrategyDecisionMissingError())
            val protectedResult = when (val timedExecution = executeQueuedWorkflowWithTimeout(
                entry = entry,
                timeoutExecutor = workflowTimeoutExecutor,
            ) {
                protectedStrategy.synchronizeAcceptedPlan(
                    request = work.request,
                    decision = decision,
                    plan = acceptedPlan,
                    bindings = work.bindings.toStrategyProviderBindings(),
                )
            }) {
                is QueuedWorkflowTimeoutExecution.Completed -> timedExecution.value
                is QueuedWorkflowTimeoutExecution.Failed -> {
                    return localFailure(entry, timedExecution.error)
                }
            }
            return ProviderProtectedQueueEntryExecutionResult(
                entryId = entry.id,
                outcome = strategyOutcomeMapper.map(
                    protectedResult.strategyResult,
                    entry,
                ),
                strategyExecutionResult = protectedResult,
            )
        }

        val protectedExecution = when (val timedExecution = executeQueuedWorkflowWithTimeout(
""",
)
replace_once(
    protected,
    """    private data class StructuralRejectionError(
""",
    """    private data class AcceptedStrategyProtectionMissingError(
        override val code: ErrorCode =
            ErrorCode("DL-PROTECTED-QUEUE-ACCEPTED-PLAN-NOT-CONFIGURED"),
        override val category: ErrorCategory = ErrorCategory.CONFIGURATION,
        override val severity: ErrorSeverity = ErrorSeverity.ERROR,
        override val recoverability: Recoverability = Recoverability.NON_RECOVERABLE,
        override val message: String =
            "Protected queued accepted-plan execution is not configured.",
        override val cause: Throwable? = null,
    ) : DataLoomError

    private data class AcceptedStrategyDecisionMissingError(
        override val code: ErrorCode =
            ErrorCode("DL-PROTECTED-QUEUE-ACCEPTED-PLAN-DECISION-MISSING"),
        override val category: ErrorCategory = ErrorCategory.CONFIGURATION,
        override val severity: ErrorSeverity = ErrorSeverity.ERROR,
        override val recoverability: Recoverability = Recoverability.NON_RECOVERABLE,
        override val message: String =
            "Protected queued accepted-plan execution requires a durable decision.",
        override val cause: Throwable? = null,
    ) : DataLoomError

    private data class StructuralRejectionError(
""",
)

builder = "dataloom-runtime/src/commonMain/kotlin/io/dataloom/runtime/facade/DataLoomBuilder.kt"
replace_once(
    builder,
    """            buildQueueWorker(
                spec = spec,
                registry = registry,
                bindings = legacyBindings,
                deps = deps,
                executionCoordinator = executionCoordinator,
            )
""",
    """            buildQueueWorker(
                spec = spec,
                registry = registry,
                bindings = legacyBindings,
                deps = deps,
                executionCoordinator = executionCoordinator,
                acceptedStrategyPlanCoordinator = acceptedStrategyPlanCoordinator,
            )
""",
)
replace_once(
    builder,
    """            buildCircuitQueueWorker(
                spec = spec,
                registry = registry,
                bindings = legacyBindings,
                deps = deps,
                executionCoordinator = executionCoordinator,
                schedulerCircuitSpec = circuitQueueWorkerSchedulerSpec,
            )
""",
    """            buildCircuitQueueWorker(
                spec = spec,
                registry = registry,
                bindings = legacyBindings,
                deps = deps,
                executionCoordinator = executionCoordinator,
                acceptedStrategyPlanCoordinator = acceptedStrategyPlanCoordinator,
                schedulerCircuitSpec = circuitQueueWorkerSchedulerSpec,
            )
""",
)
replace_once(
    builder,
    """        deps: RuntimeDependencies,
        executionCoordinator: SynchronizationExecutionCoordinator,
    ): DataLoomQueueWorker {
""",
    """        deps: RuntimeDependencies,
        executionCoordinator: SynchronizationExecutionCoordinator,
        acceptedStrategyPlanCoordinator: AcceptedStrategyPlanExecutionCoordinator,
    ): DataLoomQueueWorker {
""",
)
replace_once(
    builder,
    """            workflowTimeoutExecutor = WorkflowTimeoutStateExecutor(deps.clock),
        )

        val queueProviderTimeout = spec.queueProviderTimeout
""",
    """            workflowTimeoutExecutor = WorkflowTimeoutStateExecutor(deps.clock),
            acceptedStrategyPlanCoordinator = acceptedStrategyPlanCoordinator,
        )

        val queueProviderTimeout = spec.queueProviderTimeout
""",
)
replace_once(
    builder,
    """        deps: RuntimeDependencies,
        executionCoordinator: SynchronizationExecutionCoordinator,
        schedulerCircuitSpec: DataLoomCircuitQueueWorkerSchedulerSpec?,
    ): DataLoomCircuitQueueWorker {
""",
    """        deps: RuntimeDependencies,
        executionCoordinator: SynchronizationExecutionCoordinator,
        acceptedStrategyPlanCoordinator: AcceptedStrategyPlanExecutionCoordinator,
        schedulerCircuitSpec: DataLoomCircuitQueueWorkerSchedulerSpec?,
    ): DataLoomCircuitQueueWorker {
""",
)
# Replace the second handler occurrence only by anchoring around workerSpec.
replace_once(
    builder,
    """            retryOperation = workerSpec.retryOperation,
            connectivityConfiguration = connectivityConfiguration,
            clock = if (connectivityConfiguration != null) deps.clock else null,
            workflowTimeoutExecutor = WorkflowTimeoutStateExecutor(deps.clock),
        )
        val protectedQueueProvider = assembleQueueWorkerQueueProvider(
""",
    """            retryOperation = workerSpec.retryOperation,
            connectivityConfiguration = connectivityConfiguration,
            clock = if (connectivityConfiguration != null) deps.clock else null,
            workflowTimeoutExecutor = WorkflowTimeoutStateExecutor(deps.clock),
            acceptedStrategyPlanCoordinator = acceptedStrategyPlanCoordinator,
        )
        val protectedQueueProvider = assembleQueueWorkerQueueProvider(
""",
)

print("Routed direct and protected queued work through accepted strategy plans.")
