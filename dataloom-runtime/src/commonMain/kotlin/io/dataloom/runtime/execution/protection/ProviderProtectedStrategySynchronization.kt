package io.dataloom.runtime.execution.protection

import io.dataloom.api.execution.StrategyProviderSet
import io.dataloom.api.model.SynchronizationRequest
import io.dataloom.api.provider.StrategyProviderBindings
import io.dataloom.api.strategy.PersistedStrategyDecision
import io.dataloom.api.strategy.StrategyEvaluationResult
import io.dataloom.api.strategy.StrategyExecutionPlan
import io.dataloom.api.strategy.StrategyLocalFallbackProvider
import io.dataloom.api.strategy.StrategyOperation
import io.dataloom.api.strategy.StrategyReconciliationProvider
import io.dataloom.api.strategy.StrategySynchronizationRequest
import io.dataloom.api.time.DataLoomClock
import io.dataloom.core.provider.ResolvedStrategyProviders
import io.dataloom.runtime.facade.DataLoomStrategyProviderProtectionSpec
import io.dataloom.runtime.retry.CircuitBreakerCoordinator
import io.dataloom.runtime.retry.CircuitBreakerExecutionGate
import io.dataloom.runtime.retry.CircuitBreakerProviderOperationAdapter
import io.dataloom.runtime.retry.CoroutineRetryTimeoutExecutor
import io.dataloom.runtime.retry.RetryTimeoutConfiguration
import io.dataloom.runtime.retry.RetryTimeoutCoordinator
import io.dataloom.runtime.retry.StorageCircuitProtectionRuntime
import io.dataloom.runtime.retry.TransportCircuitProtectionRuntime
import io.dataloom.runtime.strategy.AcceptedStrategyPlanExecutionCoordinator
import io.dataloom.runtime.strategy.StrategyExecutionRejectionReason
import io.dataloom.runtime.strategy.StrategyProviderExecutionBoundary
import io.dataloom.runtime.strategy.StrategyProviderExecutionPreparation
import io.dataloom.runtime.strategy.StrategySynchronizationExecutionCoordinator
import io.dataloom.runtime.strategy.StrategySynchronizationExecutionResult

/**
 * Exact terminal strategy result plus ordered bounded provider/circuit evidence.
 *
 * [operationEvidence] is defensively copied. It contains no provider return
 * values, local domain payloads, credentials, headers, checkpoint contents, or
 * arbitrary metadata.
 */
public class ProviderProtectedStrategySynchronizationResult(
    public val strategyResult: StrategySynchronizationExecutionResult,
    operationEvidence: List<ProviderProtectionOperationEvidence>,
) {
    public val operationEvidence: List<ProviderProtectionOperationEvidence> =
        operationEvidence.toList()

    override fun equals(other: Any?): Boolean =
        other is ProviderProtectedStrategySynchronizationResult &&
            strategyResult == other.strategyResult &&
            operationEvidence == other.operationEvidence

    override fun hashCode(): Int =
        31 * strategyResult.hashCode() + operationEvidence.hashCode()

    /** Bounded diagnostic representation that excludes provider values. */
    override fun toString(): String =
        "ProviderProtectedStrategySynchronizationResult(" +
            "status=${strategyStatus(strategyResult)}, " +
            "operationEvidenceCount=${operationEvidence.size}" +
            ")"
}

/**
 * Additive coordinator that reuses the existing strategy evaluation,
 * admission, provider resolution, and built-in executors.
 *
 * A fresh provider boundary and evidence collector are created per call, so
 * concurrent protected strategy executions do not share mutable evidence.
 */
internal class ProviderProtectedStrategySynchronizationCoordinator(
    private val strategyCoordinator: StrategySynchronizationExecutionCoordinator,
    private val acceptedPlanCoordinator: AcceptedStrategyPlanExecutionCoordinator,
    private val protectionSpec: DataLoomStrategyProviderProtectionSpec,
    private val clock: DataLoomClock,
) {
    suspend fun execute(
        request: StrategySynchronizationRequest,
        bindings: StrategyProviderBindings,
    ): ProviderProtectedStrategySynchronizationResult {
        val collector = ProviderProtectionEvidenceCollector()
        val result = strategyCoordinator.execute(
            request = request,
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

/**
 * Plan-aware transformation from resolved raw providers to protected provider
 * bridges. Only providers actually resolved for the immutable plan are touched.
 */
internal class ProviderProtectedStrategyExecutionBoundary(
    private val protectionSpec: DataLoomStrategyProviderProtectionSpec,
    private val clock: DataLoomClock,
    private val evidenceCollector: ProviderProtectionEvidenceCollector,
) : StrategyProviderExecutionBoundary {

    override fun prepare(
        evaluation: StrategyEvaluationResult,
        providers: StrategyProviderSet,
    ): StrategyProviderExecutionPreparation {
        return try {
            val transportProvider = providers.transportProvider?.let { provider ->
                val spec = protectionSpec.transport
                    ?: return missingProtection()
                val operations = TransportCircuitProtectionRuntime.create(
                    transportProvider = provider,
                    clock = clock,
                    circuitBreakerConfiguration = spec.circuitBreakerConfiguration,
                    circuitBreakerStateStore = spec.circuitBreakerStateStore,
                    scopes = spec.scopes,
                    providerTimeout = spec.providerTimeout,
                    failureClassifier = spec.failureClassifier,
                )
                ProviderProtectionTransportBridge(
                    protectedOperations = operations,
                    evidenceCollector = evidenceCollector,
                )
            }

            val storageProvider = providers.storageProvider?.let { provider ->
                val spec = protectionSpec.storage
                    ?: return missingProtection()
                val operations = StorageCircuitProtectionRuntime.create(
                    storageProvider = provider,
                    clock = clock,
                    circuitBreakerConfiguration = spec.circuitBreakerConfiguration,
                    circuitBreakerStateStore = spec.circuitBreakerStateStore,
                    scopes = spec.scopes,
                    providerTimeout = spec.providerTimeout,
                    failureClassifier = spec.failureClassifier,
                )
                val storageBridge = ProviderProtectionStorageBridge(
                    protectedOperations = operations,
                    evidenceCollector = evidenceCollector,
                )

                val fallbackBridge = if (
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
            }

            StrategyProviderExecutionPreparation.Prepared(
                ResolvedStrategyProviders(
                    storageProvider = storageProvider,
                    transportProvider = transportProvider,
                    schedulerProvider = providers.schedulerProvider,
                    connectivityProvider = providers.connectivityProvider,
                    queueProvider = providers.queueProvider,
                ),
            )
        } catch (_: IllegalArgumentException) {
            StrategyProviderExecutionPreparation.Rejected(
                StrategyExecutionRejectionReason.PROVIDER_PROTECTION_SCOPE_MISMATCH,
            )
        }
    }

    private fun providerOperationAdapter(
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

private fun requiresLocalFallback(
    evaluation: StrategyEvaluationResult,
): Boolean =
    evaluation.plan.fallbackPlan != null ||
        StrategyOperation.SERVE_LOCAL in evaluation.plan.operations

private fun requiresReconciliation(
    evaluation: StrategyEvaluationResult,
): Boolean = StrategyOperation.RECONCILE in evaluation.plan.operations

private fun strategyStatus(result: StrategySynchronizationExecutionResult): String =
    when (result) {
        is StrategySynchronizationExecutionResult.Executed -> "EXECUTED"
        is StrategySynchronizationExecutionResult.ServedFromCache -> "SERVED_FROM_CACHE"
        is StrategySynchronizationExecutionResult.Failed -> "FAILED"
        is StrategySynchronizationExecutionResult.FallbackActivated -> "FALLBACK_ACTIVATED"
        is StrategySynchronizationExecutionResult.FallbackUnavailable -> "FALLBACK_UNAVAILABLE"
        is StrategySynchronizationExecutionResult.Cancelled -> "CANCELLED"
        is StrategySynchronizationExecutionResult.Deferred -> "DEFERRED"
        is StrategySynchronizationExecutionResult.Rejected -> "REJECTED"
    }
