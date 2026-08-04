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
        raise SystemExit(f"Expected one protected coordinator match in {path}, found {count}: {old[:140]!r}")
    write(path, content.replace(old, new, 1))


path = (
    "dataloom-runtime/src/commonMain/kotlin/io/dataloom/runtime/execution/protection/"
    "ProviderProtectedStrategySynchronization.kt"
)
replace_once(
    path,
    """import io.dataloom.api.execution.StrategyProviderSet
import io.dataloom.api.provider.StrategyProviderBindings
import io.dataloom.api.strategy.StrategyEvaluationResult
""",
    """import io.dataloom.api.execution.StrategyProviderSet
import io.dataloom.api.model.SynchronizationRequest
import io.dataloom.api.provider.StrategyProviderBindings
import io.dataloom.api.strategy.PersistedStrategyDecision
import io.dataloom.api.strategy.StrategyEvaluationResult
import io.dataloom.api.strategy.StrategyExecutionPlan
""",
)
replace_once(
    path,
    """import io.dataloom.api.strategy.StrategyLocalFallbackProvider
import io.dataloom.api.strategy.StrategyOperation
""",
    """import io.dataloom.api.strategy.StrategyLocalFallbackProvider
import io.dataloom.api.strategy.StrategyOperation
import io.dataloom.api.strategy.StrategyReconciliationProvider
""",
)
replace_once(
    path,
    """import io.dataloom.runtime.retry.StrategyLocalFallbackTimeoutErrors
import io.dataloom.runtime.retry.StorageCircuitOperation
""",
    """import io.dataloom.runtime.retry.StrategyLocalFallbackTimeoutErrors
import io.dataloom.runtime.retry.StrategyReconciliationCircuitOperation
import io.dataloom.runtime.retry.StrategyReconciliationTimeoutErrors
import io.dataloom.runtime.retry.StorageCircuitOperation
""",
)
# The imports above are in a different file; ensure coordinator imports accepted runtime.
replace_once(
    path,
    """import io.dataloom.runtime.strategy.StrategyExecutionRejectionReason
import io.dataloom.runtime.strategy.StrategyProviderExecutionBoundary
""",
    """import io.dataloom.runtime.strategy.AcceptedStrategyPlanExecutionCoordinator
import io.dataloom.runtime.strategy.StrategyExecutionRejectionReason
import io.dataloom.runtime.strategy.StrategyProviderExecutionBoundary
""",
)
replace_once(
    path,
    """internal class ProviderProtectedStrategySynchronizationCoordinator(
    private val strategyCoordinator: StrategySynchronizationExecutionCoordinator,
    private val protectionSpec: DataLoomStrategyProviderProtectionSpec,
    private val clock: DataLoomClock,
) {
""",
    """internal class ProviderProtectedStrategySynchronizationCoordinator(
    private val strategyCoordinator: StrategySynchronizationExecutionCoordinator,
    private val acceptedPlanCoordinator: AcceptedStrategyPlanExecutionCoordinator,
    private val protectionSpec: DataLoomStrategyProviderProtectionSpec,
    private val clock: DataLoomClock,
) {
""",
)
replace_once(
    path,
    """        return ProviderProtectedStrategySynchronizationResult(
            strategyResult = result,
            operationEvidence = collector.snapshot(),
        )
    }
}
""",
    """        return ProviderProtectedStrategySynchronizationResult(
            strategyResult = result,
            operationEvidence = collector.snapshot(),
        )
    }

    suspend fun executeAcceptedPlan(
        request: SynchronizationRequest,
        decision: PersistedStrategyDecision,
        plan: StrategyExecutionPlan,
        bindings: StrategyProviderBindings,
    ): ProviderProtectedStrategySynchronizationResult {
        val collector = ProviderProtectionEvidenceCollector()
        val result = acceptedPlanCoordinator.execute(
            request = request,
            decision = decision,
            acceptedPlan = plan,
            bindings = bindings,
            providerBoundary = ProviderProtectedStrategyExecutionBoundary(
                protectionSpec = protectionSpec,
                clock = clock,
                evidenceCollector = collector,
            ),
        )
        return ProviderProtectedStrategySynchronizationResult(
            strategyResult = result,
            operationEvidence = collector.snapshot(),
        )
    }
}
""",
)
replace_once(
    path,
    "private class ProviderProtectedStrategyExecutionBoundary(\n",
    "internal class ProviderProtectedStrategyExecutionBoundary(\n",
)
old_storage = '''                if (
                    requiresLocalFallback(evaluation) &&
                    provider is StrategyLocalFallbackProvider
                ) {
                    val fallbackSpec = protectionSpec.localFallback
                        ?: return missingProtection()
                    val fallbackOperationAdapter = CircuitBreakerProviderOperationAdapter(
                        executionGate = CircuitBreakerExecutionGate(
                            CircuitBreakerCoordinator(
                                configuration =
                                    fallbackSpec.circuitBreakerConfiguration,
                                clock = clock,
                                stateStore = fallbackSpec.circuitBreakerStateStore,
                            ),
                        ),
                        failureClassifier = fallbackSpec.failureClassifier,
                    )
                    val fallbackTimeoutCoordinator =
                        fallbackSpec.providerTimeout?.let { timeout ->
                            RetryTimeoutCoordinator(
                                configuration = RetryTimeoutConfiguration(
                                    providerTimeout = timeout,
                                ),
                                clock = clock,
                                executor = CoroutineRetryTimeoutExecutor(),
                            )
                        }
                    ProviderProtectionStrategyFallbackBridge(
                        storageBridge = storageBridge,
                        delegate = provider,
                        providerOperationAdapter = fallbackOperationAdapter,
                        scope = fallbackSpec.scope,
                        evidenceCollector = evidenceCollector,
                        timeoutCoordinator = fallbackTimeoutCoordinator,
                    )
                } else {
                    storageBridge
                }
'''
new_storage = '''                val fallbackBridge = if (
                    requiresLocalFallback(evaluation) &&
                    provider is StrategyLocalFallbackProvider
                ) {
                    val fallbackSpec = protectionSpec.localFallback
                        ?: return missingProtection()
                    ProviderProtectionStrategyFallbackBridge(
                        storageBridge = storageBridge,
                        delegate = provider,
                        providerOperationAdapter = providerOperationAdapter(
                            configuration = fallbackSpec.circuitBreakerConfiguration,
                            stateStore = fallbackSpec.circuitBreakerStateStore,
                            failureClassifier = fallbackSpec.failureClassifier,
                        ),
                        scope = fallbackSpec.scope,
                        evidenceCollector = evidenceCollector,
                        timeoutCoordinator = timeoutCoordinator(
                            fallbackSpec.providerTimeout,
                        ),
                    )
                } else {
                    null
                }

                val reconciliationBridge = if (
                    requiresReconciliation(evaluation) &&
                    provider is StrategyReconciliationProvider
                ) {
                    val reconciliationSpec = protectionSpec.reconciliation
                        ?: return missingProtection()
                    ProviderProtectionStrategyReconciliationBridge(
                        storageBridge = storageBridge,
                        delegate = provider,
                        providerOperationAdapter = providerOperationAdapter(
                            configuration =
                                reconciliationSpec.circuitBreakerConfiguration,
                            stateStore = reconciliationSpec.circuitBreakerStateStore,
                            failureClassifier = reconciliationSpec.failureClassifier,
                        ),
                        scope = reconciliationSpec.scope,
                        evidenceCollector = evidenceCollector,
                        timeoutCoordinator = timeoutCoordinator(
                            reconciliationSpec.providerTimeout,
                        ),
                    )
                } else {
                    null
                }

                when {
                    fallbackBridge != null && reconciliationBridge != null ->
                        ProviderProtectionStrategyFallbackAndReconciliationBridge(
                            fallbackBridge = fallbackBridge,
                            reconciliationBridge = reconciliationBridge,
                        )
                    fallbackBridge != null -> fallbackBridge
                    reconciliationBridge != null -> reconciliationBridge
                    else -> storageBridge
                }
'''
replace_once(path, old_storage, new_storage)
replace_once(
    path,
    """    private fun missingProtection(): StrategyProviderExecutionPreparation =
        StrategyProviderExecutionPreparation.Rejected(
            StrategyExecutionRejectionReason.PROVIDER_PROTECTION_NOT_CONFIGURED,
        )
}
""",
    """    private fun providerOperationAdapter(
        configuration: io.dataloom.runtime.retry.CircuitBreakerConfiguration,
        stateStore: io.dataloom.api.circuit.CircuitBreakerStateStore,
        failureClassifier: io.dataloom.runtime.retry.CircuitBreakerFailureClassifier,
    ): CircuitBreakerProviderOperationAdapter =
        CircuitBreakerProviderOperationAdapter(
            executionGate = CircuitBreakerExecutionGate(
                CircuitBreakerCoordinator(
                    configuration = configuration,
                    clock = clock,
                    stateStore = stateStore,
                ),
            ),
            failureClassifier = failureClassifier,
        )

    private fun timeoutCoordinator(
        providerTimeout: io.dataloom.api.scheduling.SchedulingDelay?,
    ): RetryTimeoutCoordinator? = providerTimeout?.let { timeout ->
        RetryTimeoutCoordinator(
            configuration = RetryTimeoutConfiguration(providerTimeout = timeout),
            clock = clock,
            executor = CoroutineRetryTimeoutExecutor(),
        )
    }

    private fun missingProtection(): StrategyProviderExecutionPreparation =
        StrategyProviderExecutionPreparation.Rejected(
            StrategyExecutionRejectionReason.PROVIDER_PROTECTION_NOT_CONFIGURED,
        )
}
""",
)
replace_once(
    path,
    """private fun requiresLocalFallback(
    evaluation: StrategyEvaluationResult,
): Boolean =
    evaluation.plan.fallbackPlan != null ||
        StrategyOperation.SERVE_LOCAL in evaluation.plan.operations

private fun strategyStatus(result: StrategySynchronizationExecutionResult): String =
""",
    """private fun requiresLocalFallback(
    evaluation: StrategyEvaluationResult,
): Boolean =
    evaluation.plan.fallbackPlan != null ||
        StrategyOperation.SERVE_LOCAL in evaluation.plan.operations

private fun requiresReconciliation(
    evaluation: StrategyEvaluationResult,
): Boolean = StrategyOperation.RECONCILE in evaluation.plan.operations

private fun strategyStatus(result: StrategySynchronizationExecutionResult): String =
""",
)

print("Assembled protected accepted-plan execution and reconciliation.")
