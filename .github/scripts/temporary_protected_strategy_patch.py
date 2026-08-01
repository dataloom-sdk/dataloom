from pathlib import Path


def replace_once(path: str, old: str, new: str) -> None:
    target = Path(path)
    text = target.read_text()
    if old not in text:
        raise SystemExit(f"Anchor not found in {path}: {old[:120]!r}")
    target.write_text(text.replace(old, new, 1))


# ---------------------------------------------------------------------------
# Provider bridge: add strategy-local-fallback support while reusing the same
# bounded evidence adapter as storage and transport.
# ---------------------------------------------------------------------------
bridge_path = "dataloom-runtime/src/commonMain/kotlin/io/dataloom/runtime/execution/protection/ProviderProtectionProviderBridge.kt"
replace_once(
    bridge_path,
    "import io.dataloom.api.error.DataLoomError\n",
    "import io.dataloom.api.circuit.CircuitBreakerScope\n"
    "import io.dataloom.api.error.DataLoomError\n",
)
replace_once(
    bridge_path,
    "import io.dataloom.api.synchronization.SynchronizationCheckpoint\n",
    "import io.dataloom.api.synchronization.SynchronizationCheckpoint\n"
    "import io.dataloom.api.strategy.StrategyLocalFallbackProvider\n"
    "import io.dataloom.api.strategy.StrategyLocalFallbackRequest\n"
    "import io.dataloom.api.strategy.StrategyLocalFallbackResult\n",
)
replace_once(
    bridge_path,
    "import io.dataloom.runtime.retry.CircuitBreakerExecutionResult\n",
    "import io.dataloom.runtime.retry.CircuitBreakerExecutionResult\n"
    "import io.dataloom.runtime.retry.CircuitBreakerProviderOperationAdapter\n",
)
replace_once(
    bridge_path,
    "import io.dataloom.runtime.retry.ProtectedTransportOperations\n",
    "import io.dataloom.runtime.retry.ProtectedTransportOperations\n"
    "import io.dataloom.runtime.retry.RetryTimeoutCoordinator\n"
    "import io.dataloom.runtime.retry.RetryTimeoutExecutionResult\n"
    "import io.dataloom.runtime.retry.RetryTimeoutKind\n"
    "import io.dataloom.runtime.retry.StrategyLocalFallbackCircuitOperation\n"
    "import io.dataloom.runtime.retry.StrategyLocalFallbackTimeoutErrors\n",
)

fallback_bridge = r'''
/**
 * Strategy-local-fallback bridge that preserves the protected storage surface
 * and adds one independently governed fallback operation.
 */
internal class ProviderProtectionStrategyFallbackBridge(
    private val storageBridge: ProviderProtectionStorageBridge,
    private val delegate: StrategyLocalFallbackProvider,
    private val providerOperationAdapter: CircuitBreakerProviderOperationAdapter,
    private val scope: CircuitBreakerScope,
    private val evidenceCollector: ProviderProtectionEvidenceCollector,
    private val timeoutCoordinator: RetryTimeoutCoordinator?,
) : StrategyLocalFallbackProvider {
    init {
        require(storageBridge.descriptor.id == delegate.descriptor.id) {
            "Strategy fallback bridge storage provider must match the fallback provider."
        }
        require(scope.providerId == null || scope.providerId == delegate.descriptor.id) {
            "Strategy fallback circuit scope provider must match the storage provider."
        }
        require(
            scope.operation == null ||
                scope.operation ==
                StrategyLocalFallbackCircuitOperation.EVALUATE_LOCAL_FALLBACK.retryOperation,
        ) {
            "Strategy fallback circuit scope operation must match " +
                "${StrategyLocalFallbackCircuitOperation.EVALUATE_LOCAL_FALLBACK.retryOperation.value}."
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

    override suspend fun evaluateLocalFallback(
        request: StrategyLocalFallbackRequest,
    ): ProviderOperationResult<StrategyLocalFallbackResult> =
        adaptProviderProtectionResult(
            providerId = descriptor.id,
            operation =
                StrategyLocalFallbackCircuitOperation.EVALUATE_LOCAL_FALLBACK.retryOperation,
            result = providerOperationAdapter.execute(scope) {
                executeWithOptionalTimeout(request)
            },
            evidenceCollector = evidenceCollector,
        )

    private suspend fun executeWithOptionalTimeout(
        request: StrategyLocalFallbackRequest,
    ): ProviderOperationResult<StrategyLocalFallbackResult> {
        val coordinator = timeoutCoordinator
            ?: return delegate.evaluateLocalFallback(request)
        return when (
            val result = coordinator.execute(
                kind = RetryTimeoutKind.PROVIDER,
                operation = { delegate.evaluateLocalFallback(request) },
            )
        ) {
            is RetryTimeoutExecutionResult.Completed -> result.value
            is RetryTimeoutExecutionResult.TimedOut -> ProviderOperationResult.Failure(
                StrategyLocalFallbackTimeoutErrors.providerTimedOut(),
            )
            is RetryTimeoutExecutionResult.WorkflowDeadlineExceeded ->
                ProviderOperationResult.Failure(
                    StrategyLocalFallbackTimeoutErrors.workflowDeadlineExceeded(),
                )
            is RetryTimeoutExecutionResult.ClockRegression ->
                ProviderOperationResult.Failure(
                    StrategyLocalFallbackTimeoutErrors.clockRegression(),
                )
        }
    }
}

'''
replace_once(
    bridge_path,
    "private fun <T> adaptProviderProtectionResult(\n",
    fallback_bridge + "private fun <T> adaptProviderProtectionResult(\n",
)

# ---------------------------------------------------------------------------
# Strategy coordinator: keep direct behavior through the identity boundary and
# add one internal overload for per-call provider protection.
# ---------------------------------------------------------------------------
coordinator_path = "dataloom-runtime/src/commonMain/kotlin/io/dataloom/runtime/strategy/StrategySynchronizationExecutionCoordinator.kt"
replace_once(
    coordinator_path,
    "    public suspend fun execute(\n"
    "        request: StrategySynchronizationRequest,\n"
    "        bindings: StrategyProviderBindings,\n"
    "    ): StrategySynchronizationExecutionResult {\n"
    "        val evaluation = evaluator.evaluate(request.evaluationRequest())\n",
    "    public suspend fun execute(\n"
    "        request: StrategySynchronizationRequest,\n"
    "        bindings: StrategyProviderBindings,\n"
    "    ): StrategySynchronizationExecutionResult = execute(\n"
    "        request = request,\n"
    "        bindings = bindings,\n"
    "        providerBoundary = StrategyProviderExecutionBoundary.Identity,\n"
    "    )\n\n"
    "    internal suspend fun execute(\n"
    "        request: StrategySynchronizationRequest,\n"
    "        bindings: StrategyProviderBindings,\n"
    "        providerBoundary: StrategyProviderExecutionBoundary,\n"
    "    ): StrategySynchronizationExecutionResult {\n"
    "        val evaluation = evaluator.evaluate(request.evaluationRequest())\n",
)
replace_once(
    coordinator_path,
    "        if (\n"
    "            evaluation.plan.effectiveStrategy ==\n"
    "            BuiltInSynchronizationStrategy.NETWORK_ONLY\n"
    "        ) {\n",
    "        val executionProviders = when (\n"
    "            val preparation = providerBoundary.prepare(evaluation, providers)\n"
    "        ) {\n"
    "            is StrategyProviderExecutionPreparation.Prepared -> preparation.providers\n"
    "            is StrategyProviderExecutionPreparation.Rejected -> return rejected(\n"
    "                evaluation = evaluation,\n"
    "                reason = preparation.reason,\n"
    "            )\n"
    "        }\n\n"
    "        if (\n"
    "            evaluation.plan.effectiveStrategy ==\n"
    "            BuiltInSynchronizationStrategy.NETWORK_ONLY\n"
    "        ) {\n",
)
coordinator = Path(coordinator_path)
text = coordinator.read_text()
text = text.replace("                providers = providers,\n", "                providers = executionProviders,\n")
if text.count("providers = executionProviders") != 2:
    raise SystemExit("Expected exactly two protected strategy provider substitutions")
coordinator.write_text(text)

# Add typed protection rejections to the existing strategy result model.
replace_once(
    "dataloom-runtime/src/commonMain/kotlin/io/dataloom/runtime/strategy/StrategySynchronizationExecutionResult.kt",
    "    PROVIDER_RESOLUTION_FAILED,\n"
    "    LOCAL_FALLBACK_PROVIDER_NOT_CONFIGURED,\n",
    "    PROVIDER_RESOLUTION_FAILED,\n"
    "    PROVIDER_PROTECTION_NOT_CONFIGURED,\n"
    "    PROVIDER_PROTECTION_SCOPE_MISMATCH,\n"
    "    LOCAL_FALLBACK_PROVIDER_NOT_CONFIGURED,\n",
)

# ---------------------------------------------------------------------------
# Public facade and builder assembly.
# ---------------------------------------------------------------------------
replace_once(
    "dataloom-runtime/src/commonMain/kotlin/io/dataloom/runtime/facade/DataLoom.kt",
    "    public val protectedSynchronization: DataLoomProtectedSynchronization?\n"
    "        get() = null\n\n",
    "    public val protectedSynchronization: DataLoomProtectedSynchronization?\n"
    "        get() = null\n\n"
    "    /**\n"
    "     * Optional plan-aware provider protection for built-in strategy execution.\n"
    "     *\n"
    "     * `null` unless [DataLoomBuilder.strategyProviderProtectionConfiguration]\n"
    "     * was supplied. Historical strategy synchronization remains unchanged.\n"
    "     */\n"
    "    public val protectedStrategySynchronization: DataLoomProtectedStrategySynchronization?\n"
    "        get() = null\n\n",
)

replace_once(
    "dataloom-runtime/src/commonMain/kotlin/io/dataloom/runtime/facade/DefaultDataLoom.kt",
    "    override val protectedSynchronization: DataLoomProtectedSynchronization?,\n"
    "    override val queueSubmission: DataLoomQueueSubmission?,\n",
    "    override val protectedSynchronization: DataLoomProtectedSynchronization?,\n"
    "    override val protectedStrategySynchronization: DataLoomProtectedStrategySynchronization?,\n"
    "    override val queueSubmission: DataLoomQueueSubmission?,\n",
)

builder_path = "dataloom-runtime/src/commonMain/kotlin/io/dataloom/runtime/facade/DataLoomBuilder.kt"
replace_once(
    builder_path,
    "import io.dataloom.runtime.execution.protection.ProviderProtectedSynchronizationCoordinator\n",
    "import io.dataloom.runtime.execution.protection.ProviderProtectedStrategySynchronizationCoordinator\n"
    "import io.dataloom.runtime.execution.protection.ProviderProtectedSynchronizationCoordinator\n",
)
replace_once(
    builder_path,
    "    private var providerProtectionSpec: DataLoomProviderProtectionSpec? = null\n"
    "    private var built: Boolean = false\n",
    "    private var providerProtectionSpec: DataLoomProviderProtectionSpec? = null\n"
    "    private var strategyProviderProtectionSpec: DataLoomStrategyProviderProtectionSpec? = null\n"
    "    private var built: Boolean = false\n",
)
replace_once(
    builder_path,
    "    public fun providerProtectionConfiguration(\n"
    "        spec: DataLoomProviderProtectionSpec,\n"
    "    ): DataLoomBuilder = apply {\n"
    "        providerProtectionSpec = spec\n"
    "    }\n\n",
    "    public fun providerProtectionConfiguration(\n"
    "        spec: DataLoomProviderProtectionSpec,\n"
    "    ): DataLoomBuilder = apply {\n"
    "        providerProtectionSpec = spec\n"
    "    }\n\n"
    "    /**\n"
    "     * Configures additive plan-aware protection for built-in strategy execution.\n"
    "     * Only providers resolved for the evaluated immutable plan are wrapped.\n"
    "     */\n"
    "    public fun strategyProviderProtectionConfiguration(\n"
    "        spec: DataLoomStrategyProviderProtectionSpec,\n"
    "    ): DataLoomBuilder = apply {\n"
    "        strategyProviderProtectionSpec = spec\n"
    "    }\n\n",
)
replace_once(
    builder_path,
    "        // --- 9. Build optional provider-protected direct synchronization ---\n"
    "        val protectedSynchronization = providerProtectionSpec?.let { spec ->\n",
    "        // --- 9. Build optional provider-protected strategy synchronization ---\n"
    "        val protectedStrategySynchronization = strategyProviderProtectionSpec?.let { spec ->\n"
    "            DefaultDataLoomProtectedStrategySynchronization(\n"
    "                coordinator = ProviderProtectedStrategySynchronizationCoordinator(\n"
    "                    strategyCoordinator = strategyExecutionCoordinator,\n"
    "                    protectionSpec = spec,\n"
    "                    clock = deps.clock,\n"
    "                ),\n"
    "                defaultBindings = strategyBindings,\n"
    "            )\n"
    "        }\n\n"
    "        // --- 10. Build optional provider-protected direct synchronization ---\n"
    "        val protectedSynchronization = providerProtectionSpec?.let { spec ->\n",
)
replace_once(
    builder_path,
    "            protectedSynchronization = protectedSynchronization,\n"
    "            queueSubmission = queueSubmission,\n",
    "            protectedSynchronization = protectedSynchronization,\n"
    "            protectedStrategySynchronization = protectedStrategySynchronization,\n"
    "            queueSubmission = queueSubmission,\n",
)

print("Protected strategy patch applied successfully.")
