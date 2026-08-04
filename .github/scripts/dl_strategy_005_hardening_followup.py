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
        raise SystemExit(
            f"Expected one follow-up hardening match in {path}, found {count}: "
            f"{old[:180]!r}",
        )
    write(path, content.replace(old, new, 1))


# ---------------------------------------------------------------------------
# Further constrain public immutable strategy contracts.
# ---------------------------------------------------------------------------
plan_path = "dataloom-api/src/commonMain/kotlin/io/dataloom/api/strategy/StrategyExecutionPlan.kt"
replace_once(
    plan_path,
    """        require(remoteOutcomeSnapshot.none { it in protectedFallbackOutcomes }) {
            "A fallback branch must not hide cancellation, protected failures, or conflict."
        }
        require(dataOrigin != StrategyDataOrigin.REMOTE) {
""",
    """        require(remoteOutcomeSnapshot.none { it in protectedFallbackOutcomes }) {
            "A fallback branch must not hide cancellation, protected failures, or conflict."
        }
        require(
            operationSnapshot.all {
                it == StrategyOperation.READ_LOCAL ||
                    it == StrategyOperation.SERVE_LOCAL
            },
        ) {
            "A local fallback branch may only read and serve local state."
        }
        require(dataOrigin != StrategyDataOrigin.REMOTE) {
""",
)
replace_once(
    plan_path,
    """        require(orderedOperations.size == orderedOperations.distinct().size) {
            "StrategyDurableContinuationPlan operations must be unique and ordered."
        }
        require(
            StrategyOperation.ENQUEUE_DURABLE_WORK !in orderedOperations &&
""",
    """        require(orderedOperations.size == orderedOperations.distinct().size) {
            "StrategyDurableContinuationPlan operations must be unique and ordered."
        }
        require(StrategyOperation.ACCEPT_LOCAL !in orderedOperations) {
            "A durable continuation must not repeat original local admission."
        }
        require(
            StrategyOperation.ENQUEUE_DURABLE_WORK !in orderedOperations &&
""",
)
replace_once(
    plan_path,
    """        require(
            disposition == StrategyDisposition.REJECT || orderedOperations.isNotEmpty(),
        ) {
            "Non-rejected strategy plans require at least one operation."
        }
        require(
            fallbackPlan == null || disposition == StrategyDisposition.EXECUTE,
""",
    """        require(
            disposition == StrategyDisposition.REJECT || orderedOperations.isNotEmpty(),
        ) {
            "Non-rejected strategy plans require at least one operation."
        }
        require(orderedOperations.size == orderedOperations.distinct().size) {
            "StrategyExecutionPlan operations must be unique and ordered."
        }
        require(
            fallbackPlan == null || disposition == StrategyDisposition.EXECUTE,
""",
)

# Make the collection hardening test safe on all KMP collection implementations.
hardening_test_path = (
    "dataloom-api/src/commonTest/kotlin/io/dataloom/api/strategy/"
    "StrategyExecutionPlanHardeningTest.kt"
)
replace_once(
    hardening_test_path,
    """        (plan.operations as? MutableList<StrategyOperation>)?.clear()
        (plan.requiredCapabilities as? MutableSet<StrategyProviderCapability>)?.clear()
        assertEquals(listOf(StrategyOperation.ACCEPT_LOCAL), plan.operations)
        assertEquals(setOf(StrategyProviderCapability.STORAGE), plan.requiredCapabilities)
""",
    """        runCatching {
            (plan.operations as? MutableList<StrategyOperation>)?.clear()
        }
        runCatching {
            (plan.requiredCapabilities as? MutableSet<StrategyProviderCapability>)?.clear()
        }
        assertEquals(listOf(StrategyOperation.ACCEPT_LOCAL), plan.operations)
        assertEquals(setOf(StrategyProviderCapability.STORAGE), plan.requiredCapabilities)
""",
)
replace_once(
    hardening_test_path,
    """        (profile.fallbackOn as? MutableSet<StrategyRemoteOutcome>)?.clear()
        assertEquals(setOf(StrategyRemoteOutcome.UNAVAILABLE), profile.fallbackOn)
""",
    """        runCatching {
            (profile.fallbackOn as? MutableSet<StrategyRemoteOutcome>)?.clear()
        }
        assertEquals(setOf(StrategyRemoteOutcome.UNAVAILABLE), profile.fallbackOn)
""",
)
replace_once(
    hardening_test_path,
    """        (adaptive.candidates as? MutableList<SynchronizationStrategyProfile>)?.clear()
        assertEquals(listOf(candidate), adaptive.candidates)
""",
    """        runCatching {
            (adaptive.candidates as? MutableList<SynchronizationStrategyProfile>)?.clear()
        }
        assertEquals(listOf(candidate), adaptive.candidates)
""",
)

# ---------------------------------------------------------------------------
# Require exact finite replay sequences and reconcile local-only continuations.
# ---------------------------------------------------------------------------
coordinator_path = (
    "dataloom-runtime/src/commonMain/kotlin/io/dataloom/runtime/strategy/"
    "AcceptedStrategyPlanExecutionCoordinator.kt"
)
replace_once(
    coordinator_path,
    """        if (continuation.requiredCapabilities != expectedCapabilities) {
            return StrategyExecutionRejectionReason.UNSUPPORTED_PLAN
        }
        if (operations.size != operations.toSet().size) {
""",
    """        if (continuation.requiredCapabilities != expectedCapabilities) {
            return StrategyExecutionRejectionReason.UNSUPPORTED_PLAN
        }
        val executableOperations = operations.filterNot {
            it == StrategyOperation.RECONCILE
        }
        val supportedSequence = when (acceptedPlan.direction) {
            SynchronizationDirection.PUSH ->
                executableOperations == listOf(
                    StrategyOperation.READ_LOCAL,
                    StrategyOperation.PUSH_REMOTE,
                )
            SynchronizationDirection.PULL ->
                executableOperations == listOf(StrategyOperation.PULL_REMOTE) ||
                    executableOperations == listOf(
                        StrategyOperation.READ_CHECKPOINT,
                        StrategyOperation.PULL_REMOTE,
                        StrategyOperation.PERSIST_REMOTE,
                    ) ||
                    executableOperations == listOf(StrategyOperation.SERVE_LOCAL)
            SynchronizationDirection.BIDIRECTIONAL ->
                executableOperations == listOf(
                    StrategyOperation.READ_LOCAL,
                    StrategyOperation.PUSH_REMOTE,
                    StrategyOperation.PULL_REMOTE,
                ) ||
                    executableOperations == listOf(
                        StrategyOperation.READ_LOCAL,
                        StrategyOperation.PUSH_REMOTE,
                        StrategyOperation.READ_CHECKPOINT,
                        StrategyOperation.PULL_REMOTE,
                        StrategyOperation.PERSIST_REMOTE,
                    ) ||
                    executableOperations == listOf(
                        StrategyOperation.READ_LOCAL,
                        StrategyOperation.SERVE_LOCAL,
                    )
        }
        if (!supportedSequence) {
            return StrategyExecutionRejectionReason.UNSUPPORTED_PLAN
        }
        if (operations.size != operations.toSet().size) {
""",
)
replace_once(
    coordinator_path,
    """        if (StrategyOperation.SERVE_LOCAL in continuation.operations) {
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
    """        if (StrategyOperation.SERVE_LOCAL in continuation.operations) {
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
)
replace_once(
    coordinator_path,
    """                transportAttempted = completedOperations.any {
                    it == StrategyOperation.PUSH_REMOTE ||
                        it == StrategyOperation.PULL_REMOTE
                },
                completedOperations = evidence,
""",
    """                transportAttempted =
                    (
                        result is StrategySynchronizationExecutionResult.FallbackActivated &&
                            result.remoteAttempted
                        ) ||
                        completedOperations.any {
                            it == StrategyOperation.PUSH_REMOTE ||
                                it == StrategyOperation.PULL_REMOTE
                        },
                completedOperations = evidence,
""",
)

# ---------------------------------------------------------------------------
# A known failure must never become queue completion if retry says NotRequired.
# ---------------------------------------------------------------------------
mapper_path = (
    "dataloom-runtime/src/commonMain/kotlin/io/dataloom/runtime/queue/"
    "StrategyQueueExecutionOutcomeMapper.kt"
)
replace_once(
    mapper_path,
    """            SynchronizationRetryEvaluation.NotRequired ->
                QueueEntryExecutionOutcome.Completed(completedAt)
        }
    }
""",
    """            SynchronizationRetryEvaluation.NotRequired ->
                failed(AcceptedPlanRetryEvaluationInconsistentError())
        }
    }
""",
)
replace_once(
    mapper_path,
    """    private data class AcceptedPlanRetryAttemptExhaustedError(
""",
    """    private data class AcceptedPlanRetryEvaluationInconsistentError(
        override val code: ErrorCode =
            ErrorCode("DL-Q-ACCEPTED-PLAN-RETRY-EVALUATION-INCONSISTENT"),
        override val category: ErrorCategory = ErrorCategory.STATE,
        override val severity: ErrorSeverity = ErrorSeverity.ERROR,
        override val recoverability: Recoverability = Recoverability.NON_RECOVERABLE,
        override val message: String =
            "Retry evaluation returned NotRequired for a known accepted-plan failure.",
        override val cause: Throwable? = null,
    ) : DataLoomError

    private data class AcceptedPlanRetryAttemptExhaustedError(
""",
)

# ---------------------------------------------------------------------------
# Adversarial runtime tests for exact sequence and local reconciliation.
# ---------------------------------------------------------------------------
runtime_test_path = (
    "dataloom-runtime/src/commonTest/kotlin/io/dataloom/runtime/strategy/"
    "AcceptedStrategyPlanExecutionCoordinatorTest.kt"
)
runtime_test = read(runtime_test_path)
insert_at = runtime_test.index("    private suspend fun fixture(")
new_tests = """    @Test
    fun unsupportedReplaySequenceRejectsBeforeProviderExecution() = runTest {
        val storage = RecordingStrategyStorage()
        val transport = RecordingTransport()
        val fixture = fixture(storage, transport)
        val plan = StrategyExecutionPlan(
            id = StrategyPlanId("plan-1"),
            requestedStrategy = BuiltInSynchronizationStrategy.REMOTE_FIRST,
            effectiveProfileId = StrategyProfileId("remote-profile"),
            effectiveStrategy = BuiltInSynchronizationStrategy.REMOTE_FIRST,
            configurationVersion = StrategyConfigurationVersion(1L),
            direction = SynchronizationDirection.PULL,
            mode = SynchronizationMode.DELTA,
            disposition = StrategyDisposition.DEFER,
            operations = listOf(StrategyOperation.ENQUEUE_DURABLE_WORK),
            requiredCapabilities = setOf(StrategyProviderCapability.QUEUE),
            dataOrigin = StrategyDataOrigin.NONE,
            consistency = StrategyConsistency.REMOTE_AUTHORITATIVE,
            deferralReason = StrategyDeferralReason.CONNECTIVITY_UNKNOWN,
            durableContinuation = StrategyDurableContinuationPlan(
                operations = listOf(
                    StrategyOperation.READ_LOCAL,
                    StrategyOperation.PULL_REMOTE,
                ),
                requiredCapabilities = setOf(
                    StrategyProviderCapability.STORAGE,
                    StrategyProviderCapability.TRANSPORT,
                ),
                dataOrigin = StrategyDataOrigin.REMOTE,
                consistency = StrategyConsistency.REMOTE_AUTHORITATIVE,
            ),
        )

        val result = fixture.coordinator.execute(
            request = request(SynchronizationDirection.PULL),
            decision = decision(
                requested = BuiltInSynchronizationStrategy.REMOTE_FIRST,
                effective = BuiltInSynchronizationStrategy.REMOTE_FIRST,
                profileId = "remote-profile",
                disposition = StrategyDisposition.DEFER,
            ),
            acceptedPlan = plan,
            bindings = bindings(storage, transport),
        )

        val rejected = assertIs<StrategySynchronizationExecutionResult.Rejected>(result)
        assertEquals(StrategyExecutionRejectionReason.UNSUPPORTED_PLAN, rejected.reason)
        assertEquals(0, transport.pullCalls)
        assertEquals(0, fixture.pullPipeline.calls)
    }

    @Test
    fun localOnlyReconciliationExecutesExactlyOnce() = runTest {
        val storage = RecordingStrategyStorage(
            fallbackResult = ProviderOperationResult.Success(
                StrategyLocalFallbackResult.Available(StrategyCacheState.STALE),
            ),
        )
        val transport = RecordingTransport()
        val fixture = fixture(storage, transport)
        val plan = StrategyExecutionPlan(
            id = StrategyPlanId("plan-1"),
            requestedStrategy = BuiltInSynchronizationStrategy.HYBRID,
            effectiveProfileId = StrategyProfileId("hybrid-profile"),
            effectiveStrategy = BuiltInSynchronizationStrategy.HYBRID,
            configurationVersion = StrategyConfigurationVersion(1L),
            direction = SynchronizationDirection.PULL,
            mode = SynchronizationMode.DELTA,
            disposition = StrategyDisposition.DEFER,
            operations = listOf(StrategyOperation.ENQUEUE_DURABLE_WORK),
            requiredCapabilities = setOf(StrategyProviderCapability.QUEUE),
            dataOrigin = StrategyDataOrigin.LOCAL,
            consistency = StrategyConsistency.READ_YOUR_WRITES,
            deferralReason = StrategyDeferralReason.CONNECTIVITY_UNAVAILABLE,
            durableContinuation = StrategyDurableContinuationPlan(
                operations = listOf(
                    StrategyOperation.SERVE_LOCAL,
                    StrategyOperation.RECONCILE,
                ),
                requiredCapabilities = setOf(
                    StrategyProviderCapability.STORAGE,
                    StrategyProviderCapability.TRANSPORT,
                    StrategyProviderCapability.CONFLICT_STATE,
                ),
                dataOrigin = StrategyDataOrigin.LOCAL,
                consistency = StrategyConsistency.READ_YOUR_WRITES,
                evaluatedCacheState = StrategyCacheState.STALE,
            ),
        )

        val result = fixture.coordinator.execute(
            request = request(SynchronizationDirection.PULL),
            decision = decision(
                requested = BuiltInSynchronizationStrategy.HYBRID,
                effective = BuiltInSynchronizationStrategy.HYBRID,
                profileId = "hybrid-profile",
                disposition = StrategyDisposition.DEFER,
            ),
            acceptedPlan = plan,
            bindings = bindings(storage, transport),
        )

        val fallback = assertIs<StrategySynchronizationExecutionResult.FallbackActivated>(result)
        assertEquals(StrategyCacheState.STALE, fallback.cacheState)
        assertEquals(1, storage.reconcileCalls)
        assertEquals(
            listOf(StrategyOperation.SERVE_LOCAL),
            storage.lastReconciliation?.completedOperations,
        )
        assertEquals(0, transport.pullCalls)
        assertEquals(0, fixture.pullPipeline.calls)
    }

"""
runtime_test = runtime_test[:insert_at] + new_tests + runtime_test[insert_at:]
write(runtime_test_path, runtime_test)

# Keep the checkpoint explicit about exact finite replay and fail-closed retry.
doc_path = "docs/audits/DL-039B-persisted-accepted-plan-execution-checkpoint.md"
replace_once(
    doc_path,
    """- Unsupported or operation-inconsistent capability sets reject before provider resolution.
- Local serving and fallback require persisted cache-state evidence; no current or invented evidence is used.
""",
    """- Unsupported, extra, missing, or operation-inconsistent capability sets reject before provider resolution.
- Replay accepts only finite direction-specific operation sequences that match the executor actually invoked.
- Local serving and fallback require persisted cache-state evidence; no current or invented evidence is used.
""",
)
replace_once(
    doc_path,
    """- Entries without a complete plan retain the historical execution path.
""",
    """- Entries without a complete plan retain the historical execution path.
- A retry evaluator inconsistency for known failed work is terminal and can never become queue completion.
""",
)

print("Applied exact replay-sequence, local reconciliation, and retry fail-closed hardening.")
