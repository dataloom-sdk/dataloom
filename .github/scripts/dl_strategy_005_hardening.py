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
            f"Expected one hardening match in {path}, found {count}: {old[:180]!r}",
        )
    write(path, content.replace(old, new, 1))


# ---------------------------------------------------------------------------
# Public immutable-plan invariants and collection snapshots.
# ---------------------------------------------------------------------------
plan_path = "dataloom-api/src/commonMain/kotlin/io/dataloom/api/strategy/StrategyExecutionPlan.kt"
replace_once(
    plan_path,
    """public enum class StrategyRejectionReason {
    NO_ELIGIBLE_ADAPTIVE_PROFILE,
    CACHE_MISS,
    STALE_CACHE_NOT_ALLOWED,
    CONNECTIVITY_UNAVAILABLE,
    CONNECTIVITY_UNKNOWN,
    REQUIRED_CAPABILITY_UNAVAILABLE,
    UNSUPPORTED_DIRECTION,
}

/**
 * Finite fallback branch admitted with a primary strategy plan.
""",
    """public enum class StrategyRejectionReason {
    NO_ELIGIBLE_ADAPTIVE_PROFILE,
    CACHE_MISS,
    STALE_CACHE_NOT_ALLOWED,
    CONNECTIVITY_UNAVAILABLE,
    CONNECTIVITY_UNKNOWN,
    REQUIRED_CAPABILITY_UNAVAILABLE,
    UNSUPPORTED_DIRECTION,
}

private val protectedFallbackOutcomes: Set<StrategyRemoteOutcome> = setOf(
    StrategyRemoteOutcome.CANCELLED,
    StrategyRemoteOutcome.AUTHENTICATION_FAILURE,
    StrategyRemoteOutcome.AUTHORIZATION_FAILURE,
    StrategyRemoteOutcome.VALIDATION_FAILURE,
    StrategyRemoteOutcome.INTEGRITY_FAILURE,
    StrategyRemoteOutcome.CONFLICT,
)

private fun minimumCapabilitiesFor(
    operations: Iterable<StrategyOperation>,
): Set<StrategyProviderCapability> {
    val capabilities = mutableSetOf<StrategyProviderCapability>()
    operations.forEach { operation ->
        when (operation) {
            StrategyOperation.READ_LOCAL,
            StrategyOperation.READ_CHECKPOINT,
            StrategyOperation.ACCEPT_LOCAL,
            StrategyOperation.SERVE_LOCAL,
            StrategyOperation.PERSIST_REMOTE,
            -> capabilities += StrategyProviderCapability.STORAGE
            StrategyOperation.ENQUEUE_DURABLE_WORK ->
                capabilities += StrategyProviderCapability.QUEUE
            StrategyOperation.PUSH_REMOTE,
            StrategyOperation.PULL_REMOTE,
            -> capabilities += StrategyProviderCapability.TRANSPORT
            StrategyOperation.SCHEDULE_REFRESH -> {
                capabilities += StrategyProviderCapability.SCHEDULER
                capabilities += StrategyProviderCapability.QUEUE
            }
            StrategyOperation.RECONCILE -> {
                capabilities += StrategyProviderCapability.STORAGE
                capabilities += StrategyProviderCapability.TRANSPORT
                capabilities += StrategyProviderCapability.CONFLICT_STATE
            }
        }
    }
    return capabilities
}

/**
 * Finite fallback branch admitted with a primary strategy plan.
""",
)
replace_once(
    plan_path,
    """        require(operationSnapshot.isNotEmpty()) {
            "StrategyFallbackPlan requires at least one fallback operation."
        }
        require(dataOrigin != StrategyDataOrigin.REMOTE) {
""",
    """        require(operationSnapshot.isNotEmpty()) {
            "StrategyFallbackPlan requires at least one fallback operation."
        }
        require(operationSnapshot.size == operationSnapshot.distinct().size) {
            "StrategyFallbackPlan operations must be unique and ordered."
        }
        require(StrategyOperation.SERVE_LOCAL in operationSnapshot) {
            "A local fallback branch must explicitly serve local state."
        }
        require(remoteOutcomeSnapshot.none { it in protectedFallbackOutcomes }) {
            "A fallback branch must not hide cancellation, protected failures, or conflict."
        }
        require(dataOrigin != StrategyDataOrigin.REMOTE) {
""",
)
replace_once(
    plan_path,
    """        require(orderedOperations.isNotEmpty()) {
            "StrategyDurableContinuationPlan requires at least one operation."
        }
        require(
            StrategyOperation.ENQUEUE_DURABLE_WORK !in orderedOperations &&
""",
    """        require(orderedOperations.isNotEmpty()) {
            "StrategyDurableContinuationPlan requires at least one operation."
        }
        require(orderedOperations.size == orderedOperations.distinct().size) {
            "StrategyDurableContinuationPlan operations must be unique and ordered."
        }
        require(
            StrategyOperation.ENQUEUE_DURABLE_WORK !in orderedOperations &&
""",
)
replace_once(
    plan_path,
    """        require(
            fallbackPlan == null ||
                StrategyOperation.PULL_REMOTE in orderedOperations,
        ) {
            "A durable fallback branch requires a remote pull operation."
        }
    }
""",
    """        require(
            fallbackPlan == null ||
                StrategyOperation.PULL_REMOTE in orderedOperations,
        ) {
            "A durable fallback branch requires a remote pull operation."
        }
        require(
            evaluatedCacheState != null ||
                (
                    fallbackPlan == null &&
                        StrategyOperation.SERVE_LOCAL !in orderedOperations
                    ),
        ) {
            "Durable local fallback or serving requires persisted cache-state evidence."
        }
        val minimumCapabilities = minimumCapabilitiesFor(
            orderedOperations + (fallbackPlan?.operations ?: emptyList()),
        )
        require(providerCapabilities.containsAll(minimumCapabilities)) {
            "Durable continuation capabilities must cover every admitted operation."
        }
    }
""",
)
replace_once(
    plan_path,
    """    public val operations: List<StrategyOperation>
        get() = orderedOperations

    public val requiredCapabilities: Set<StrategyProviderCapability>
        get() = providerCapabilities
""",
    """    public val operations: List<StrategyOperation>
        get() = orderedOperations.toList()

    public val requiredCapabilities: Set<StrategyProviderCapability>
        get() = providerCapabilities.toSet()
""",
)

profile_path = (
    "dataloom-api/src/commonMain/kotlin/io/dataloom/api/strategy/"
    "SynchronizationStrategyProfile.kt"
)
replace_once(
    profile_path,
    """    public val fallbackOn: Set<StrategyRemoteOutcome>
        get() = fallbackOutcomes
""",
    """    public val fallbackOn: Set<StrategyRemoteOutcome>
        get() = fallbackOutcomes.toSet()
""",
)
replace_once(
    profile_path,
    """    public val candidates: List<SynchronizationStrategyProfile>
        get() = candidateProfiles
""",
    """    public val candidates: List<SynchronizationStrategyProfile>
        get() = candidateProfiles.toList()
""",
)

evaluation_path = "dataloom-api/src/commonMain/kotlin/io/dataloom/api/strategy/StrategyEvaluation.kt"
replace_once(
    evaluation_path,
    """    public val reasonCodes: List<String>
        get() = reasons
""",
    """    public val reasonCodes: List<String>
        get() = reasons.toList()
""",
)

# ---------------------------------------------------------------------------
# Fail-closed replay structure before provider resolution and exact capability
# correspondence. Also support local-only SERVE_LOCAL continuations.
# ---------------------------------------------------------------------------
coordinator_path = (
    "dataloom-runtime/src/commonMain/kotlin/io/dataloom/runtime/strategy/"
    "AcceptedStrategyPlanExecutionCoordinator.kt"
)
content = read(coordinator_path)
anchor = """        val providerCapabilities = continuation.requiredCapabilities -
            StrategyProviderCapability.CONFLICT_STATE
"""
insert = """        validateReplayPlanStructure(
            acceptedPlan = acceptedPlan,
            continuation = continuation,
        )?.let { reason -> return rejected(evaluation, reason) }

        val providerCapabilities = continuation.requiredCapabilities -
            StrategyProviderCapability.CONFLICT_STATE
"""
if content.count(anchor) != 1:
    raise SystemExit("Expected one provider-capability anchor in accepted-plan coordinator.")
content = content.replace(anchor, insert, 1)
old_post = """        validateReplayPlan(
            acceptedPlan = acceptedPlan,
            continuation = continuation,
            providers = executableProviders,
        )?.let { reason -> return rejected(evaluation, reason) }
"""
new_post = """        validateReplayProviders(
            continuation = continuation,
            providers = executableProviders,
        )?.let { reason -> return rejected(evaluation, reason) }
"""
if content.count(old_post) != 1:
    raise SystemExit("Expected one post-resolution replay validation call.")
content = content.replace(old_post, new_post, 1)
start = content.index("    private fun validateReplayPlan(")
end = content.index("    private fun replayEvaluation(", start)
new_validation = """    private fun validateReplayPlanStructure(
        acceptedPlan: StrategyExecutionPlan,
        continuation: StrategyDurableContinuationPlan,
    ): StrategyExecutionRejectionReason? {
        val operations = continuation.operations
        val fallbackOperations = continuation.fallbackPlan?.operations ?: emptyList()
        val expectedCapabilities = minimumReplayCapabilities(
            operations + fallbackOperations,
        )
        if (continuation.requiredCapabilities != expectedCapabilities) {
            return StrategyExecutionRejectionReason.UNSUPPORTED_PLAN
        }
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

    private fun validateReplayProviders(
        continuation: StrategyDurableContinuationPlan,
        providers: StrategyProviderSet,
    ): StrategyExecutionRejectionReason? {
        val requiresFallback =
            continuation.fallbackPlan != null ||
                StrategyOperation.SERVE_LOCAL in continuation.operations
        if (
            requiresFallback &&
            providers.storageProvider !is StrategyLocalFallbackProvider
        ) {
            return StrategyExecutionRejectionReason.LOCAL_FALLBACK_PROVIDER_NOT_CONFIGURED
        }
        if (
            StrategyOperation.RECONCILE in continuation.operations &&
            providers.storageProvider !is StrategyReconciliationProvider
        ) {
            return StrategyExecutionRejectionReason.RECONCILIATION_PROVIDER_NOT_CONFIGURED
        }
        return null
    }

    private fun minimumReplayCapabilities(
        operations: Iterable<StrategyOperation>,
    ): Set<StrategyProviderCapability> {
        val capabilities = mutableSetOf<StrategyProviderCapability>()
        operations.forEach { operation ->
            when (operation) {
                StrategyOperation.READ_LOCAL,
                StrategyOperation.READ_CHECKPOINT,
                StrategyOperation.ACCEPT_LOCAL,
                StrategyOperation.SERVE_LOCAL,
                StrategyOperation.PERSIST_REMOTE,
                -> capabilities += StrategyProviderCapability.STORAGE
                StrategyOperation.PUSH_REMOTE,
                StrategyOperation.PULL_REMOTE,
                -> capabilities += StrategyProviderCapability.TRANSPORT
                StrategyOperation.RECONCILE -> {
                    capabilities += StrategyProviderCapability.STORAGE
                    capabilities += StrategyProviderCapability.TRANSPORT
                    capabilities += StrategyProviderCapability.CONFLICT_STATE
                }
                StrategyOperation.ENQUEUE_DURABLE_WORK,
                StrategyOperation.SCHEDULE_REFRESH,
                -> return emptySet()
            }
        }
        return capabilities
    }

"""
content = content[:start] + new_validation + content[end:]
old_fallback = """        val fallbackProvider = if (continuation.fallbackPlan != null) {
            providers.storageProvider as? StrategyLocalFallbackProvider
                ?: return rejected(
                    evaluation,
                    StrategyExecutionRejectionReason.LOCAL_FALLBACK_PROVIDER_NOT_CONFIGURED,
                )
        } else {
            null
        }
"""
new_fallback = """        val requiresFallback =
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
"""
if content.count(old_fallback) != 1:
    raise SystemExit("Expected one accepted-plan fallback-provider block.")
content = content.replace(old_fallback, new_fallback, 1)
old_cache = """            evaluatedCacheState =
                continuation.evaluatedCacheState ?: StrategyCacheState.MISSING,
"""
new_cache = """            evaluatedCacheState = requireNotNull(
                continuation.evaluatedCacheState,
            ) {
                "Accepted local fallback requires persisted cache-state evidence."
            },
"""
if content.count(old_cache) != 1:
    raise SystemExit("Expected one accepted-plan cache-state fallback.")
content = content.replace(old_cache, new_cache, 1)
write(coordinator_path, content)

# ---------------------------------------------------------------------------
# Adversarial API tests.
# ---------------------------------------------------------------------------
hardening_test = """package io.dataloom.api.strategy

import io.dataloom.api.model.SynchronizationDirection
import io.dataloom.api.model.SynchronizationMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class StrategyExecutionPlanHardeningTest {

    @Test
    fun fallbackPlanRejectsProtectedRemoteOutcomes() {
        listOf(
            StrategyRemoteOutcome.CANCELLED,
            StrategyRemoteOutcome.AUTHENTICATION_FAILURE,
            StrategyRemoteOutcome.AUTHORIZATION_FAILURE,
            StrategyRemoteOutcome.VALIDATION_FAILURE,
            StrategyRemoteOutcome.INTEGRITY_FAILURE,
            StrategyRemoteOutcome.CONFLICT,
        ).forEach { outcome ->
            assertFailsWith<IllegalArgumentException> {
                StrategyFallbackPlan(
                    remoteOutcomes = setOf(outcome),
                    operations = listOf(StrategyOperation.SERVE_LOCAL),
                    dataOrigin = StrategyDataOrigin.LOCAL,
                )
            }
        }
    }

    @Test
    fun fallbackPlanRequiresAnExplicitLocalServeOperation() {
        assertFailsWith<IllegalArgumentException> {
            StrategyFallbackPlan(
                remoteOutcomes = setOf(StrategyRemoteOutcome.UNAVAILABLE),
                operations = listOf(StrategyOperation.READ_LOCAL),
                dataOrigin = StrategyDataOrigin.LOCAL,
            )
        }
    }

    @Test
    fun durableContinuationRequiresCapabilitiesForEveryOperation() {
        assertFailsWith<IllegalArgumentException> {
            StrategyDurableContinuationPlan(
                operations = listOf(StrategyOperation.PULL_REMOTE),
                requiredCapabilities = emptySet(),
                dataOrigin = StrategyDataOrigin.REMOTE,
                consistency = StrategyConsistency.REMOTE_AUTHORITATIVE,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            StrategyDurableContinuationPlan(
                operations = listOf(StrategyOperation.RECONCILE),
                requiredCapabilities = setOf(StrategyProviderCapability.STORAGE),
                dataOrigin = StrategyDataOrigin.NONE,
                consistency = StrategyConsistency.READ_YOUR_WRITES,
            )
        }
    }

    @Test
    fun durableLocalServingRequiresPersistedCacheEvidence() {
        assertFailsWith<IllegalArgumentException> {
            StrategyDurableContinuationPlan(
                operations = listOf(StrategyOperation.SERVE_LOCAL),
                requiredCapabilities = setOf(StrategyProviderCapability.STORAGE),
                dataOrigin = StrategyDataOrigin.LOCAL,
                consistency = StrategyConsistency.EVENTUAL,
            )
        }
    }

    @Test
    fun exposedPlanAndProfileCollectionsCannotMutateInternalSnapshots() {
        val plan = StrategyExecutionPlan(
            id = StrategyPlanId("plan"),
            requestedStrategy = BuiltInSynchronizationStrategy.OFFLINE_FIRST,
            effectiveProfileId = StrategyProfileId("offline"),
            effectiveStrategy = BuiltInSynchronizationStrategy.OFFLINE_FIRST,
            configurationVersion = StrategyConfigurationVersion(1),
            direction = SynchronizationDirection.PUSH,
            mode = SynchronizationMode.DELTA,
            disposition = StrategyDisposition.DEFER,
            operations = listOf(StrategyOperation.ACCEPT_LOCAL),
            requiredCapabilities = setOf(StrategyProviderCapability.STORAGE),
            dataOrigin = StrategyDataOrigin.NONE,
            consistency = StrategyConsistency.LOCAL_AUTHORITATIVE,
            deferralReason = StrategyDeferralReason.CONNECTIVITY_UNAVAILABLE,
        )
        (plan.operations as? MutableList<StrategyOperation>)?.clear()
        (plan.requiredCapabilities as? MutableSet<StrategyProviderCapability>)?.clear()
        assertEquals(listOf(StrategyOperation.ACCEPT_LOCAL), plan.operations)
        assertEquals(setOf(StrategyProviderCapability.STORAGE), plan.requiredCapabilities)

        val profile = RemoteFirstStrategyProfile(
            id = StrategyProfileId("remote"),
            configurationVersion = StrategyConfigurationVersion(1),
            fallbackOn = setOf(StrategyRemoteOutcome.UNAVAILABLE),
        )
        (profile.fallbackOn as? MutableSet<StrategyRemoteOutcome>)?.clear()
        assertEquals(setOf(StrategyRemoteOutcome.UNAVAILABLE), profile.fallbackOn)

        val candidate = OfflineFirstStrategyProfile(
            id = StrategyProfileId("candidate"),
            configurationVersion = StrategyConfigurationVersion(1),
        )
        val adaptive = AdaptiveStrategyProfile(
            id = StrategyProfileId("adaptive"),
            configurationVersion = StrategyConfigurationVersion(1),
            candidates = listOf(candidate),
        )
        (adaptive.candidates as? MutableList<SynchronizationStrategyProfile>)?.clear()
        assertEquals(listOf(candidate), adaptive.candidates)
    }
}
"""
write(
    "dataloom-api/src/commonTest/kotlin/io/dataloom/api/strategy/"
    "StrategyExecutionPlanHardeningTest.kt",
    hardening_test,
)

# ---------------------------------------------------------------------------
# Runtime tests for exact capability correspondence and local-only replay.
# ---------------------------------------------------------------------------
runtime_test_path = (
    "dataloom-runtime/src/commonTest/kotlin/io/dataloom/runtime/strategy/"
    "AcceptedStrategyPlanExecutionCoordinatorTest.kt"
)
runtime_test = read(runtime_test_path)
insert_at = runtime_test.index("    private suspend fun fixture(")
new_runtime_tests = """    @Test
    fun extraReplayCapabilityRejectsBeforeProviderExecution() = runTest {
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
                operations = listOf(StrategyOperation.PULL_REMOTE),
                requiredCapabilities = setOf(
                    StrategyProviderCapability.TRANSPORT,
                    StrategyProviderCapability.STORAGE,
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
    fun localOnlyContinuationUsesPersistedCacheEvidenceWithoutTransport() = runTest {
        val storage = RecordingStrategyStorage(
            fallbackResult = ProviderOperationResult.Success(
                StrategyLocalFallbackResult.Available(StrategyCacheState.STALE),
            ),
        )
        val transport = RecordingTransport()
        val fixture = fixture(storage, transport)
        val plan = StrategyExecutionPlan(
            id = StrategyPlanId("plan-1"),
            requestedStrategy = BuiltInSynchronizationStrategy.CACHE_FIRST,
            effectiveProfileId = StrategyProfileId("cache-profile"),
            effectiveStrategy = BuiltInSynchronizationStrategy.CACHE_FIRST,
            configurationVersion = StrategyConfigurationVersion(1L),
            direction = SynchronizationDirection.PULL,
            mode = SynchronizationMode.DELTA,
            disposition = StrategyDisposition.DEFER,
            operations = listOf(StrategyOperation.ENQUEUE_DURABLE_WORK),
            requiredCapabilities = setOf(StrategyProviderCapability.QUEUE),
            dataOrigin = StrategyDataOrigin.LOCAL,
            consistency = StrategyConsistency.EVENTUAL,
            deferralReason = StrategyDeferralReason.CONNECTIVITY_UNAVAILABLE,
            durableContinuation = StrategyDurableContinuationPlan(
                operations = listOf(StrategyOperation.SERVE_LOCAL),
                requiredCapabilities = setOf(StrategyProviderCapability.STORAGE),
                dataOrigin = StrategyDataOrigin.LOCAL,
                consistency = StrategyConsistency.EVENTUAL,
                evaluatedCacheState = StrategyCacheState.STALE,
            ),
        )

        val result = fixture.coordinator.execute(
            request = request(SynchronizationDirection.PULL),
            decision = decision(
                requested = BuiltInSynchronizationStrategy.CACHE_FIRST,
                effective = BuiltInSynchronizationStrategy.CACHE_FIRST,
                profileId = "cache-profile",
                disposition = StrategyDisposition.DEFER,
            ),
            acceptedPlan = plan,
            bindings = StrategyProviderBindings(
                storageProviderId = storage.descriptor.id,
            ),
        )

        val fallback = assertIs<StrategySynchronizationExecutionResult.FallbackActivated>(result)
        assertEquals(StrategyCacheState.STALE, fallback.cacheState)
        assertEquals(StrategyCacheState.STALE, storage.lastFallback?.evaluatedCacheState)
        assertEquals(0, transport.pullCalls)
        assertEquals(0, fixture.pullPipeline.calls)
    }

"""
runtime_test = runtime_test[:insert_at] + new_runtime_tests + runtime_test[insert_at:]
write(runtime_test_path, runtime_test)

# Documentation checkpoint for the additional fail-closed invariants.
doc_path = "docs/audits/DL-039B-persisted-accepted-plan-execution-checkpoint.md"
replace_once(
    doc_path,
    """- Unsupported capabilities reject before provider invocation.
- Provider-backed PUSH/PULL/BIDIRECTIONAL reuse canonical pipelines.
""",
    """- Unsupported or operation-inconsistent capability sets reject before provider resolution.
- Local serving and fallback require persisted cache-state evidence; no current or invented evidence is used.
- Protected failure classes and cancellation cannot be converted into local fallback.
- Provider-backed PUSH/PULL/BIDIRECTIONAL reuse canonical pipelines.
""",
)

print("Applied accepted-plan replay hardening and adversarial tests.")
