from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def read(path: str) -> str:
    return (ROOT / path).read_text()


def write(path: str, content: str) -> None:
    (ROOT / path).write_text(content.rstrip() + "\n")


def replace_once_unless_present(
    path: str,
    old: str,
    new: str,
    present: str,
) -> None:
    content = read(path)
    if present in content:
        return
    count = content.count(old)
    if count != 1:
        raise SystemExit(
            f"Expected one semantic-fix match in {path}, found {count}: {old[:180]!r}",
        )
    write(path, content.replace(old, new, 1))


coordinator = (
    "dataloom-runtime/src/commonMain/kotlin/io/dataloom/runtime/strategy/"
    "AcceptedStrategyPlanExecutionCoordinator.kt"
)

replace_once_unless_present(
    coordinator,
    """        val executableProviders = when (
            val preparation = providerBoundary.prepare(evaluation, resolved)
        ) {
            is StrategyProviderExecutionPreparation.Prepared -> preparation.providers
            is StrategyProviderExecutionPreparation.Rejected ->
                return rejected(evaluation, preparation.reason)
        }

        return executor.execute(
""",
    """        val executableProviders = when (
            val preparation = providerBoundary.prepare(evaluation, resolved)
        ) {
            is StrategyProviderExecutionPreparation.Prepared -> preparation.providers
            is StrategyProviderExecutionPreparation.Rejected ->
                return rejected(evaluation, preparation.reason)
        }

        validateReplayPlan(
            acceptedPlan = acceptedPlan,
            continuation = continuation,
            providers = executableProviders,
        )?.let { reason -> return rejected(evaluation, reason) }

        return executor.execute(
""",
    "validateReplayPlan(\n            acceptedPlan = acceptedPlan,",
)

replace_once_unless_present(
    coordinator,
    """    private fun replayEvaluation(
        decision: PersistedStrategyDecision,
        acceptedPlan: StrategyExecutionPlan,
    ): StrategyEvaluationResult {
""",
    """    private fun validateReplayPlan(
        acceptedPlan: StrategyExecutionPlan,
        continuation: StrategyDurableContinuationPlan,
        providers: StrategyProviderSet,
    ): StrategyExecutionRejectionReason? {
        val operations = continuation.operations
        if (operations.size != operations.toSet().size) {
            return StrategyExecutionRejectionReason.UNSUPPORTED_PLAN
        }
        if (
            operations.none {
                it == StrategyOperation.PUSH_REMOTE ||
                    it == StrategyOperation.PULL_REMOTE ||
                    it == StrategyOperation.SERVE_LOCAL
            }
        ) {
            return StrategyExecutionRejectionReason.UNSUPPORTED_PLAN
        }
        if (
            StrategyOperation.READ_CHECKPOINT in operations &&
            StrategyOperation.PULL_REMOTE !in operations
        ) {
            return StrategyExecutionRejectionReason.UNSUPPORTED_PLAN
        }
        if (
            StrategyOperation.PERSIST_REMOTE in operations &&
            StrategyOperation.PULL_REMOTE !in operations
        ) {
            return StrategyExecutionRejectionReason.UNSUPPORTED_PLAN
        }
        if (
            StrategyOperation.READ_CHECKPOINT in operations &&
            operations.indexOf(StrategyOperation.READ_CHECKPOINT) >
            operations.indexOf(StrategyOperation.PULL_REMOTE)
        ) {
            return StrategyExecutionRejectionReason.UNSUPPORTED_PLAN
        }
        if (
            StrategyOperation.PERSIST_REMOTE in operations &&
            operations.indexOf(StrategyOperation.PERSIST_REMOTE) <
            operations.indexOf(StrategyOperation.PULL_REMOTE)
        ) {
            return StrategyExecutionRejectionReason.UNSUPPORTED_PLAN
        }
        if (
            StrategyOperation.PUSH_REMOTE in operations &&
            StrategyOperation.PULL_REMOTE in operations &&
            operations.indexOf(StrategyOperation.PUSH_REMOTE) >
            operations.indexOf(StrategyOperation.PULL_REMOTE)
        ) {
            return StrategyExecutionRejectionReason.UNSUPPORTED_PLAN
        }
        if (
            StrategyOperation.RECONCILE in operations &&
            operations.last() != StrategyOperation.RECONCILE
        ) {
            return StrategyExecutionRejectionReason.UNSUPPORTED_PLAN
        }
        if (
            continuation.fallbackPlan != null &&
            StrategyOperation.SERVE_LOCAL !in continuation.fallbackPlan.operations
        ) {
            return StrategyExecutionRejectionReason.UNSUPPORTED_PLAN
        }
        val requiresFallback =
            continuation.fallbackPlan != null ||
                StrategyOperation.SERVE_LOCAL in operations
        if (
            requiresFallback &&
            providers.storageProvider !is StrategyLocalFallbackProvider
        ) {
            return StrategyExecutionRejectionReason.LOCAL_FALLBACK_PROVIDER_NOT_CONFIGURED
        }
        if (
            StrategyOperation.RECONCILE in operations &&
            providers.storageProvider !is StrategyReconciliationProvider
        ) {
            return StrategyExecutionRejectionReason.RECONCILIATION_PROVIDER_NOT_CONFIGURED
        }
        if (
            acceptedPlan.direction == SynchronizationDirection.PUSH &&
            (
                StrategyOperation.PULL_REMOTE in operations ||
                    StrategyOperation.READ_CHECKPOINT in operations ||
                    StrategyOperation.PERSIST_REMOTE in operations ||
                    StrategyOperation.SERVE_LOCAL in operations
                )
        ) {
            return StrategyExecutionRejectionReason.UNSUPPORTED_PLAN
        }
        if (
            acceptedPlan.direction == SynchronizationDirection.PULL &&
            StrategyOperation.PUSH_REMOTE in operations
        ) {
            return StrategyExecutionRejectionReason.UNSUPPORTED_PLAN
        }
        return null
    }

    private fun replayEvaluation(
        decision: PersistedStrategyDecision,
        acceptedPlan: StrategyExecutionPlan,
    ): StrategyEvaluationResult {
""",
    "private fun validateReplayPlan(\n",
)

replace_once_unless_present(
    coordinator,
    """        val fallbackProvider = if (continuation.fallbackPlan != null) {
            providers.storageProvider as? StrategyLocalFallbackProvider
                ?: return rejected(
                    evaluation,
                    StrategyExecutionRejectionReason.LOCAL_FALLBACK_PROVIDER_NOT_CONFIGURED,
                )
        } else {
            null
        }

        if (StrategyOperation.SERVE_LOCAL in continuation.operations) {
            return executeFallback(
                request = request,
                evaluation = evaluation,
                providers = providers,
                continuation = continuation,
                provider = requireNotNull(fallbackProvider),
                remoteOutcome = StrategyRemoteOutcome.UNAVAILABLE,
                remoteAttempted = false,
                primaryError = null,
                completedOperations = emptyList(),
            )
        }
""",
    """        val requiresFallback =
            continuation.fallbackPlan != null ||
                StrategyOperation.SERVE_LOCAL in continuation.operations
        val fallbackProvider = if (requiresFallback) {
            providers.storageProvider as? StrategyLocalFallbackProvider
                ?: return rejected(
                    evaluation,
                    StrategyExecutionRejectionReason.LOCAL_FALLBACK_PROVIDER_NOT_CONFIGURED,
                )
        } else {
            null
        }

        if (StrategyOperation.SERVE_LOCAL in continuation.operations) {
            val mapped = executeFallback(
                request = request,
                evaluation = evaluation,
                providers = providers,
                continuation = continuation,
                provider = requireNotNull(fallbackProvider),
                remoteOutcome = StrategyRemoteOutcome.UNAVAILABLE,
                remoteAttempted = false,
                primaryError = null,
                completedOperations = emptyList(),
            )
            return finalizeReconciliation(
                request = request,
                evaluation = evaluation,
                providers = providers,
                continuation = continuation,
                result = mapped,
                completedOperations = completedOperationsFor(
                    result = mapped,
                    continuation = continuation,
                    observedOperations = emptyList(),
                ),
            )
        }
""",
    "val requiresFallback =\n            continuation.fallbackPlan != null ||",
)

replace_once_unless_present(
    coordinator,
    """        return finalizeReconciliation(
            request = request,
            evaluation = evaluation,
            providers = providers,
            continuation = continuation,
            result = mapped,
            completedOperations = trackingTransport.completedOperations,
        )
""",
    """        return finalizeReconciliation(
            request = request,
            evaluation = evaluation,
            providers = providers,
            continuation = continuation,
            result = mapped,
            completedOperations = completedOperationsFor(
                result = mapped,
                continuation = continuation,
                observedOperations = trackingTransport.completedOperations,
            ),
        )
""",
    "observedOperations = trackingTransport.completedOperations,\n            ),\n        )\n    }\n\n    private suspend fun executeTransportOnlyPull",
)

replace_once_unless_present(
    coordinator,
    """    private suspend fun executeTransportOnlyPull(
        request: SynchronizationRequest,
        evaluation: StrategyEvaluationResult,
        providers: StrategyProviderSet,
        continuation: StrategyDurableContinuationPlan,
        fallbackProvider: StrategyLocalFallbackProvider?,
    ): StrategySynchronizationExecutionResult {
        val transport = requireNotNull(providers.transportProvider)
        val result = when (
            val pulled = transport.pullChanges(PullChangesRequest(request = request))
        ) {
            is ProviderOperationResult.Success ->
                StrategySynchronizationExecutionResult.Executed(
                    evaluation = evaluation,
                    completedAt = clock.now(),
                    output = StrategyTransportOutput.Pulled(pulled.value),
                )
            is ProviderOperationResult.Failure ->
                handleRemoteFailure(
                    request = request,
                    evaluation = evaluation,
                    providers = providers,
                    continuation = continuation,
                    fallbackProvider = fallbackProvider,
                    error = pulled.error,
                    operation = StrategyOperation.PULL_REMOTE,
                    completedOperations = emptyList(),
                )
        }
        return finalizeReconciliation(
            request,
            evaluation,
            providers,
            continuation,
            result,
            completedOperations = listOf(StrategyOperation.PULL_REMOTE),
        )
    }
""",
    """    private suspend fun executeTransportOnlyPull(
        request: SynchronizationRequest,
        evaluation: StrategyEvaluationResult,
        providers: StrategyProviderSet,
        continuation: StrategyDurableContinuationPlan,
        fallbackProvider: StrategyLocalFallbackProvider?,
    ): StrategySynchronizationExecutionResult {
        val transport = requireNotNull(providers.transportProvider)
        val execution: Pair<
            StrategySynchronizationExecutionResult,
            List<StrategyOperation>,
        > = when (
            val pulled = transport.pullChanges(PullChangesRequest(request = request))
        ) {
            is ProviderOperationResult.Success -> Pair(
                StrategySynchronizationExecutionResult.Executed(
                    evaluation = evaluation,
                    completedAt = clock.now(),
                    output = StrategyTransportOutput.Pulled(pulled.value),
                ),
                listOf(StrategyOperation.PULL_REMOTE),
            )
            is ProviderOperationResult.Failure -> {
                val mapped = handleRemoteFailure(
                    request = request,
                    evaluation = evaluation,
                    providers = providers,
                    continuation = continuation,
                    fallbackProvider = fallbackProvider,
                    error = pulled.error,
                    operation = StrategyOperation.PULL_REMOTE,
                    completedOperations = emptyList(),
                )
                Pair(
                    mapped,
                    completedOperationsFor(
                        result = mapped,
                        continuation = continuation,
                        observedOperations = emptyList(),
                    ),
                )
            }
        }
        return finalizeReconciliation(
            request = request,
            evaluation = evaluation,
            providers = providers,
            continuation = continuation,
            result = execution.first,
            completedOperations = execution.second,
        )
    }
""",
    "val execution: Pair<\n            StrategySynchronizationExecutionResult,",
)

replace_once_unless_present(
    coordinator,
    """        return finalizeReconciliation(
            request,
            evaluation,
            providers,
            continuation,
            mapped,
            trackingTransport.completedOperations,
        )
""",
    """        return finalizeReconciliation(
            request = request,
            evaluation = evaluation,
            providers = providers,
            continuation = continuation,
            result = mapped,
            completedOperations = completedOperationsFor(
                result = mapped,
                continuation = continuation,
                observedOperations = trackingTransport.completedOperations,
            ),
        )
""",
    "observedOperations = trackingTransport.completedOperations,\n            ),\n        )\n    }\n\n    private suspend fun mapPipelineResult",
)

replace_once_unless_present(
    coordinator,
    """        return finalizeReconciliation(
            request,
            evaluation,
            providers,
            continuation,
            mapped,
            completedOperations + StrategyOperation.SERVE_LOCAL,
        )
    }

    private suspend fun finalizeReconciliation(
""",
    """        return mapped
    }

    private fun completedOperationsFor(
        result: StrategySynchronizationExecutionResult,
        continuation: StrategyDurableContinuationPlan,
        observedOperations: List<StrategyOperation>,
    ): List<StrategyOperation> = when (result) {
        is StrategySynchronizationExecutionResult.FallbackActivated ->
            result.completedOperations
        is StrategySynchronizationExecutionResult.Failed ->
            result.completedOperations
        is StrategySynchronizationExecutionResult.Executed -> {
            val providerBacked = result.output as? StrategyTransportOutput.ProviderBacked
            if (providerBacked?.result is SynchronizationResult.PartiallySucceeded) {
                observedOperations
            } else {
                continuation.operations.filterNot { it == StrategyOperation.RECONCILE }
            }
        }
        else -> observedOperations
    }

    private suspend fun finalizeReconciliation(
""",
    "private fun completedOperationsFor(\n",
)


test = (
    "dataloom-runtime/src/commonTest/kotlin/io/dataloom/runtime/strategy/"
    "AcceptedStrategyPlanExecutionCoordinatorTest.kt"
)

replace_once_unless_present(
    test,
    """    fun missingReconciliationCapabilityFailsBeforeClaimingSuccess() = runTest {
""",
    """    fun missingReconciliationCapabilityFailsBeforeProviderExecution() = runTest {
""",
    "fun missingReconciliationCapabilityFailsBeforeProviderExecution()",
)
replace_once_unless_present(
    test,
    """        assertEquals(1, fixture.pushPipeline.calls)
    }

    private suspend fun fixture(
""",
    """        assertEquals(0, fixture.pushPipeline.calls)
    }

    private suspend fun fixture(
""",
    "assertEquals(0, fixture.pushPipeline.calls)\n    }\n\n    private suspend fun fixture(",
)

replace_once_unless_present(
    test,
    """        assertSame(unavailable, fallback.primaryError)
    }

    @Test
    fun missingReconciliationCapabilityFailsBeforeProviderExecution() = runTest {
""",
    """        assertSame(unavailable, fallback.primaryError)
    }

    @Test
    fun fallbackReconciliationRunsOnceWithoutClaimingFailedPullCompleted() = runTest {
        val storage = RecordingStrategyStorage(
            fallbackResult = ProviderOperationResult.Success(
                StrategyLocalFallbackResult.Available(StrategyCacheState.STALE),
            ),
        )
        val unavailable = ClassifiedError(StrategyRemoteOutcome.UNAVAILABLE)
        val transport = RecordingTransport(
            pullResult = ProviderOperationResult.Failure(unavailable),
        )
        val fixture = fixture(storage, transport)
        val fallbackPlan = StrategyFallbackPlan(
            remoteOutcomes = setOf(StrategyRemoteOutcome.UNAVAILABLE),
            operations = listOf(StrategyOperation.SERVE_LOCAL),
            dataOrigin = StrategyDataOrigin.LOCAL,
        )

        val result = fixture.coordinator.execute(
            request = request(SynchronizationDirection.PULL),
            decision = decision(
                requested = BuiltInSynchronizationStrategy.REMOTE_FIRST,
                effective = BuiltInSynchronizationStrategy.REMOTE_FIRST,
                profileId = "remote-profile",
                disposition = StrategyDisposition.DEFER,
            ),
            acceptedPlan = remotePullPlan(
                fallback = fallbackPlan,
                reconcile = true,
            ),
            bindings = bindings(storage, transport),
        )

        val fallback = assertIs<StrategySynchronizationExecutionResult.FallbackActivated>(result)
        assertEquals(1, transport.pullCalls)
        assertEquals(1, storage.reconcileCalls)
        assertEquals(
            listOf(StrategyOperation.SERVE_LOCAL),
            storage.lastReconciliation?.completedOperations,
        )
        assertEquals(listOf(StrategyOperation.SERVE_LOCAL), fallback.completedOperations)
    }

    @Test
    fun missingReconciliationCapabilityFailsBeforeProviderExecution() = runTest {
""",
    "fun fallbackReconciliationRunsOnceWithoutClaimingFailedPullCompleted()",
)

replace_once_unless_present(
    test,
    """    private fun remotePullPlan(
        fallback: StrategyFallbackPlan?,
    ): StrategyExecutionPlan = StrategyExecutionPlan(
""",
    """    private fun remotePullPlan(
        fallback: StrategyFallbackPlan?,
        reconcile: Boolean = false,
    ): StrategyExecutionPlan = StrategyExecutionPlan(
""",
    "reconcile: Boolean = false,\n    ): StrategyExecutionPlan",
)
replace_once_unless_present(
    test,
    """            operations = listOf(StrategyOperation.PULL_REMOTE),
            requiredCapabilities = if (fallback == null) {
                setOf(StrategyProviderCapability.TRANSPORT)
            } else {
                setOf(
                    StrategyProviderCapability.STORAGE,
                    StrategyProviderCapability.TRANSPORT,
                )
            },
""",
    """            operations = listOf(StrategyOperation.PULL_REMOTE) +
                if (reconcile) listOf(StrategyOperation.RECONCILE) else emptyList(),
            requiredCapabilities =
                setOf(StrategyProviderCapability.TRANSPORT) +
                    if (fallback != null || reconcile) {
                        setOf(StrategyProviderCapability.STORAGE)
                    } else {
                        emptySet()
                    } +
                    if (reconcile) {
                        setOf(StrategyProviderCapability.CONFLICT_STATE)
                    } else {
                        emptySet()
                    },
""",
    "if (reconcile) listOf(StrategyOperation.RECONCILE)",
)

print("Corrected accepted-plan capability preflight and single reconciliation semantics.")
