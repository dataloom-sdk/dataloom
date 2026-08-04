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
        raise SystemExit(f"Expected one protection match in {path}, found {count}: {old[:140]!r}")
    write(path, content.replace(old, new, 1))


bridge = (
    "dataloom-runtime/src/commonMain/kotlin/io/dataloom/runtime/execution/protection/"
    "ProviderProtectionProviderBridge.kt"
)
replace_once(
    bridge,
    """import io.dataloom.api.strategy.StrategyLocalFallbackResult
import io.dataloom.api.transport.PullChangesRequest
""",
    """import io.dataloom.api.strategy.StrategyLocalFallbackResult
import io.dataloom.api.strategy.StrategyReconciliationProvider
import io.dataloom.api.strategy.StrategyReconciliationRequest
import io.dataloom.api.strategy.StrategyReconciliationResult
import io.dataloom.api.transport.PullChangesRequest
""",
)
replace_once(
    bridge,
    """import io.dataloom.runtime.retry.StrategyLocalFallbackTimeoutErrors
import io.dataloom.runtime.retry.StorageCircuitOperation
""",
    """import io.dataloom.runtime.retry.StrategyLocalFallbackTimeoutErrors
import io.dataloom.runtime.retry.StrategyReconciliationCircuitOperation
import io.dataloom.runtime.retry.StrategyReconciliationTimeoutErrors
import io.dataloom.runtime.retry.StorageCircuitOperation
""",
)
replace_once(
    bridge,
    """}

private fun <T> adaptProviderProtectionResult(
""",
    r'''}

/**
 * Strategy-reconciliation bridge that preserves the protected storage surface
 * and adds one independently governed reconciliation operation.
 */
internal class ProviderProtectionStrategyReconciliationBridge(
    private val storageBridge: ProviderProtectionStorageBridge,
    private val delegate: StrategyReconciliationProvider,
    private val providerOperationAdapter: CircuitBreakerProviderOperationAdapter,
    private val scope: CircuitBreakerScope,
    private val evidenceCollector: ProviderProtectionEvidenceCollector,
    private val timeoutCoordinator: RetryTimeoutCoordinator?,
) : StrategyReconciliationProvider {
    init {
        require(storageBridge.descriptor.id == delegate.descriptor.id) {
            "Strategy reconciliation bridge storage provider must match the delegate."
        }
        require(scope.providerId == null || scope.providerId == delegate.descriptor.id) {
            "Strategy reconciliation circuit scope provider must match storage."
        }
        require(
            scope.operation == null ||
                scope.operation ==
                StrategyReconciliationCircuitOperation.RECONCILE.retryOperation,
        ) {
            "Strategy reconciliation scope operation must match " +
                StrategyReconciliationCircuitOperation.RECONCILE.retryOperation.value + "."
        }
    }

    override val descriptor: ProviderDescriptor
        get() = storageBridge.descriptor

    override suspend fun initialize(
        context: ProviderInitializationContext,
    ): ProviderOperationResult<Unit> = storageBridge.initialize(context)

    override suspend fun health(): ProviderOperationResult<ProviderHealth> =
        storageBridge.health()

    override suspend fun close(): ProviderOperationResult<Unit> =
        storageBridge.close()

    override suspend fun readOutboundChanges(
        request: OutboundChangeReadRequest,
    ): ProviderOperationResult<OutboundChangeReadResult> =
        storageBridge.readOutboundChanges(request)

    override suspend fun applyInboundChanges(
        request: InboundChangeApplyRequest,
    ): ProviderOperationResult<Unit> = storageBridge.applyInboundChanges(request)

    override suspend fun acknowledgeOutboundChanges(
        request: OutboundChangeAcknowledgementRequest,
    ): ProviderOperationResult<Unit> = storageBridge.acknowledgeOutboundChanges(request)

    override suspend fun readCheckpoint(
        request: CheckpointReadRequest,
    ): ProviderOperationResult<SynchronizationCheckpoint?> =
        storageBridge.readCheckpoint(request)

    override suspend fun writeCheckpoint(
        request: CheckpointWriteRequest,
    ): ProviderOperationResult<Unit> = storageBridge.writeCheckpoint(request)

    override suspend fun reconcileStrategy(
        request: StrategyReconciliationRequest,
    ): ProviderOperationResult<StrategyReconciliationResult> =
        adaptProviderProtectionResult(
            providerId = descriptor.id,
            operation = StrategyReconciliationCircuitOperation.RECONCILE.retryOperation,
            result = providerOperationAdapter.execute(scope) {
                executeWithOptionalTimeout(request)
            },
            evidenceCollector = evidenceCollector,
        )

    private suspend fun executeWithOptionalTimeout(
        request: StrategyReconciliationRequest,
    ): ProviderOperationResult<StrategyReconciliationResult> {
        val coordinator = timeoutCoordinator
            ?: return delegate.reconcileStrategy(request)
        return when (
            val result = coordinator.execute(
                kind = RetryTimeoutKind.PROVIDER,
                operation = { delegate.reconcileStrategy(request) },
            )
        ) {
            is RetryTimeoutExecutionResult.Completed -> result.value
            is RetryTimeoutExecutionResult.TimedOut -> ProviderOperationResult.Failure(
                StrategyReconciliationTimeoutErrors.providerTimedOut(),
            )
            is RetryTimeoutExecutionResult.WorkflowDeadlineExceeded ->
                ProviderOperationResult.Failure(
                    StrategyReconciliationTimeoutErrors.workflowDeadlineExceeded(),
                )
            is RetryTimeoutExecutionResult.ClockRegression ->
                ProviderOperationResult.Failure(
                    StrategyReconciliationTimeoutErrors.clockRegression(),
                )
        }
    }
}

/** Storage bridge preserving both optional strategy storage capabilities. */
internal class ProviderProtectionStrategyFallbackAndReconciliationBridge(
    private val fallbackBridge: ProviderProtectionStrategyFallbackBridge,
    private val reconciliationBridge: ProviderProtectionStrategyReconciliationBridge,
) : StrategyLocalFallbackProvider, StrategyReconciliationProvider {
    init {
        require(fallbackBridge.descriptor.id == reconciliationBridge.descriptor.id) {
            "Combined strategy storage bridges must wrap the same provider."
        }
    }

    override val descriptor: ProviderDescriptor
        get() = fallbackBridge.descriptor

    override suspend fun initialize(
        context: ProviderInitializationContext,
    ): ProviderOperationResult<Unit> = fallbackBridge.initialize(context)

    override suspend fun health(): ProviderOperationResult<ProviderHealth> =
        fallbackBridge.health()

    override suspend fun close(): ProviderOperationResult<Unit> = fallbackBridge.close()

    override suspend fun readOutboundChanges(
        request: OutboundChangeReadRequest,
    ): ProviderOperationResult<OutboundChangeReadResult> =
        fallbackBridge.readOutboundChanges(request)

    override suspend fun applyInboundChanges(
        request: InboundChangeApplyRequest,
    ): ProviderOperationResult<Unit> = fallbackBridge.applyInboundChanges(request)

    override suspend fun acknowledgeOutboundChanges(
        request: OutboundChangeAcknowledgementRequest,
    ): ProviderOperationResult<Unit> = fallbackBridge.acknowledgeOutboundChanges(request)

    override suspend fun readCheckpoint(
        request: CheckpointReadRequest,
    ): ProviderOperationResult<SynchronizationCheckpoint?> =
        fallbackBridge.readCheckpoint(request)

    override suspend fun writeCheckpoint(
        request: CheckpointWriteRequest,
    ): ProviderOperationResult<Unit> = fallbackBridge.writeCheckpoint(request)

    override suspend fun evaluateLocalFallback(
        request: StrategyLocalFallbackRequest,
    ): ProviderOperationResult<StrategyLocalFallbackResult> =
        fallbackBridge.evaluateLocalFallback(request)

    override suspend fun reconcileStrategy(
        request: StrategyReconciliationRequest,
    ): ProviderOperationResult<StrategyReconciliationResult> =
        reconciliationBridge.reconcileStrategy(request)
}

private fun <T> adaptProviderProtectionResult(
''',
)

print("Added protected strategy reconciliation bridges.")
