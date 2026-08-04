from __future__ import annotations

from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def read(path: str) -> str:
    return (ROOT / path).read_text()


def write(path: str, content: str) -> None:
    target = ROOT / path
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(content.rstrip() + "\n")


def replace_once(path: str, old: str, new: str) -> None:
    content = read(path)
    count = content.count(old)
    if count != 1:
        raise SystemExit(
            f"Expected exactly one match in {path}, found {count}: {old[:140]!r}",
        )
    write(path, content.replace(old, new, 1))


# ---------------------------------------------------------------------------
# Built-in evaluation freezes the exact durable continuation selected at the
# original policy boundary.
# ---------------------------------------------------------------------------
evaluator_path = (
    "dataloom-runtime/src/commonMain/kotlin/io/dataloom/runtime/strategy/"
    "BuiltInSynchronizationStrategyEvaluator.kt"
)
replace_once(
    evaluator_path,
    """        val capabilities = deriveCapabilities(
            operations + (fallbackPlan?.operations ?: emptyList()),
        )
        return StrategyEvaluationResult(
""",
    """        val capabilities = deriveCapabilities(
            operations + (fallbackPlan?.operations ?: emptyList()),
        )
        val durableContinuation = deriveDurableContinuation(
            request = request,
            profile = profile,
            operations = operations,
            consistency = consistency,
        )
        return StrategyEvaluationResult(
""",
)
replace_once(
    evaluator_path,
    """                rejectionReason = rejectionReason,
                fallbackPlan = fallbackPlan,
            ),
""",
    """                rejectionReason = rejectionReason,
                fallbackPlan = fallbackPlan,
                durableContinuation = durableContinuation,
            ),
""",
)
replace_once(
    evaluator_path,
    """    private fun deriveCapabilities(
        operations: List<StrategyOperation>,
    ): Set<StrategyProviderCapability> {
""",
    """    private fun deriveDurableContinuation(
        request: StrategyEvaluationRequest,
        profile: SynchronizationStrategyProfile,
        operations: List<StrategyOperation>,
        consistency: StrategyConsistency,
    ): io.dataloom.api.strategy.StrategyDurableContinuationPlan? {
        if (StrategyOperation.ENQUEUE_DURABLE_WORK !in operations) return null

        val continuationOperations = when (profile) {
            is OfflineFirstStrategyProfile ->
                remoteOperations(request.direction, persistRemote = true).toMutableList().also {
                    if (profile.reconcileWhenOnline) it += StrategyOperation.RECONCILE
                }
            is RemoteFirstStrategyProfile ->
                remoteOperations(
                    request.direction,
                    persistRemote = profile.persistRemoteResult,
                )
            is CacheFirstStrategyProfile -> when (request.direction) {
                SynchronizationDirection.PUSH -> listOf(
                    StrategyOperation.READ_LOCAL,
                    StrategyOperation.PUSH_REMOTE,
                )
                SynchronizationDirection.PULL,
                SynchronizationDirection.BIDIRECTIONAL,
                -> remoteOperations(request.direction, persistRemote = true)
            }
            is HybridStrategyProfile ->
                remoteOperations(
                    request.direction,
                    persistRemote = profile.persistRemoteResult,
                ).toMutableList().also {
                    if (profile.reconcileAfterFallback) it += StrategyOperation.RECONCILE
                }
            is NetworkOnlyStrategyProfile ->
                error("Network-only plans cannot admit durable queue work.")
            is AdaptiveStrategyProfile ->
                error("Nested adaptive profiles are rejected before plan construction.")
        }
        val continuationFallback = when (profile) {
            is RemoteFirstStrategyProfile -> remoteFallbackPlan(profile, request.direction)
            else -> null
        }
        return io.dataloom.api.strategy.StrategyDurableContinuationPlan(
            operations = continuationOperations,
            requiredCapabilities = deriveCapabilities(
                continuationOperations +
                    (continuationFallback?.operations ?: emptyList()),
            ),
            dataOrigin = originForOperations(request.direction, continuationOperations),
            consistency = consistency,
            evaluatedCacheState = request.evidence.cacheState,
            fallbackPlan = continuationFallback,
        )
    }

    private fun deriveCapabilities(
        operations: List<StrategyOperation>,
    ): Set<StrategyProviderCapability> {
""",
)

# ---------------------------------------------------------------------------
# Durable admission rejects identity-only plans.
# ---------------------------------------------------------------------------
admission_path = (
    "dataloom-runtime/src/commonMain/kotlin/io/dataloom/runtime/strategy/"
    "StrategyQueueAdmissionEvaluator.kt"
)
replace_once(
    admission_path,
    """    MISSING_QUEUE_CAPABILITY,
}
""",
    """    MISSING_QUEUE_CAPABILITY,
    MISSING_DURABLE_CONTINUATION,
}
""",
)
replace_once(
    admission_path,
    """ * [StrategyProviderCapability.QUEUE]. A rejected strategy plan can never be converted into
 * queue work.
""",
    """ * [StrategyProviderCapability.QUEUE] and contain the immutable continuation
 * selected by the original policy evaluation. A rejected or identity-only plan
 * can never be converted into queue work.
""",
)
replace_once(
    admission_path,
    """        if (StrategyProviderCapability.QUEUE !in plan.requiredCapabilities) {
            return StrategyQueueAdmissionResult.Rejected(
                planId = plan.id,
                reason = StrategyQueueAdmissionRejectionReason.MISSING_QUEUE_CAPABILITY,
            )
        }

        return StrategyQueueAdmissionResult.Admitted(
""",
    """        if (StrategyProviderCapability.QUEUE !in plan.requiredCapabilities) {
            return StrategyQueueAdmissionResult.Rejected(
                planId = plan.id,
                reason = StrategyQueueAdmissionRejectionReason.MISSING_QUEUE_CAPABILITY,
            )
        }

        if (plan.durableContinuation == null) {
            return StrategyQueueAdmissionResult.Rejected(
                planId = plan.id,
                reason = StrategyQueueAdmissionRejectionReason.MISSING_DURABLE_CONTINUATION,
            )
        }

        return StrategyQueueAdmissionResult.Admitted(
""",
)

# ---------------------------------------------------------------------------
# Shared public identity/plan correspondence.
# ---------------------------------------------------------------------------
write(
    "dataloom-api/src/commonMain/kotlin/io/dataloom/api/strategy/"
    "PersistedStrategyDecisionPlanCorrespondence.kt",
    r'''package io.dataloom.api.strategy

/**
 * Returns true only when this durable decision describes the exact immutable
 * identity fields of [plan].
 *
 * This check performs no provider access, policy evaluation, clock read, I/O,
 * identifier generation, or mutation. Full plan equality remains a separate
 * queue encoder/resolver correspondence requirement.
 */
public fun PersistedStrategyDecision.correspondsTo(
    plan: StrategyExecutionPlan,
): Boolean =
    planId == plan.id &&
        requestedStrategy == plan.requestedStrategy &&
        effectiveProfileId == plan.effectiveProfileId &&
        effectiveStrategy == plan.effectiveStrategy &&
        configurationVersion == plan.configurationVersion &&
        disposition == plan.disposition
''',
)

# ---------------------------------------------------------------------------
# Queue domain and resolved work carry the exact accepted plan.
# ---------------------------------------------------------------------------
queue_entry = "dataloom-api/src/commonMain/kotlin/io/dataloom/api/queue/QueueEntry.kt"
replace_once(
    queue_entry,
    "import io.dataloom.api.strategy.PersistedStrategyDecision\n",
    """import io.dataloom.api.strategy.PersistedStrategyDecision
import io.dataloom.api.strategy.StrategyExecutionPlan
import io.dataloom.api.strategy.correspondsTo
""",
)
replace_once(
    queue_entry,
    """    /** Immutable strategy identity accepted before durable queue admission. */
    public val strategyDecision: PersistedStrategyDecision? = null,
) {
""",
    """    /** Immutable strategy identity accepted before durable queue admission. */
    public val strategyDecision: PersistedStrategyDecision? = null,

    /** Complete immutable accepted plan used for later durable replay. */
    public val strategyPlan: StrategyExecutionPlan? = null,
) {
""",
)
replace_once(
    queue_entry,
    """        require(retryBudgetState == null || retryAttempt != null) {
            "QueueEntry retryBudgetState requires a non-null retryAttempt."
        }
    }
""",
    """        require(retryBudgetState == null || retryAttempt != null) {
            "QueueEntry retryBudgetState requires a non-null retryAttempt."
        }
        require(strategyPlan == null || strategyDecision != null) {
            "QueueEntry strategyPlan requires a non-null strategyDecision."
        }
        require(strategyPlan == null || strategyDecision?.correspondsTo(strategyPlan) == true) {
            "QueueEntry strategyPlan must match the durable strategy decision."
        }
        require(strategyPlan == null || strategyPlan.direction == synchronizationRequest.direction) {
            "QueueEntry strategyPlan direction must match the synchronization request."
        }
        require(strategyPlan == null || strategyPlan.mode == synchronizationRequest.mode) {
            "QueueEntry strategyPlan mode must match the synchronization request."
        }
        require(strategyPlan == null || strategyPlan.durableContinuation != null) {
            "QueueEntry strategyPlan requires an immutable durable continuation."
        }
    }
""",
)
replace_once(
    queue_entry,
    """            "hasWorkflowTimeoutState=${workflowTimeoutState != null}, " +
            "hasStrategyDecision=${strategyDecision != null})"
""",
    """            "hasWorkflowTimeoutState=${workflowTimeoutState != null}, " +
            "hasStrategyDecision=${strategyDecision != null}, " +
            "hasStrategyPlan=${strategyPlan != null})"
""",
)

work_path = (
    "dataloom-runtime/src/commonMain/kotlin/io/dataloom/runtime/queue/"
    "QueuedSynchronizationWork.kt"
)
replace_once(
    work_path,
    "import io.dataloom.api.strategy.PersistedStrategyDecision\n",
    """import io.dataloom.api.strategy.PersistedStrategyDecision
import io.dataloom.api.strategy.StrategyExecutionPlan
import io.dataloom.api.strategy.correspondsTo
""",
)
replace_once(
    work_path,
    """    /** Optional immutable strategy decision associated with durable work. */
    public val strategyDecision: PersistedStrategyDecision? = null,
) {
""",
    """    /** Optional immutable strategy decision associated with durable work. */
    public val strategyDecision: PersistedStrategyDecision? = null,

    /** Optional complete immutable accepted strategy plan. */
    public val strategyPlan: StrategyExecutionPlan? = null,
) {
    init {
        require(strategyPlan == null || strategyDecision != null) {
            "QueuedSynchronizationWork strategyPlan requires a strategyDecision."
        }
        require(strategyPlan == null || strategyDecision?.correspondsTo(strategyPlan) == true) {
            "QueuedSynchronizationWork strategyPlan must match strategyDecision."
        }
        require(strategyPlan == null || strategyPlan.direction == request.direction) {
            "QueuedSynchronizationWork strategyPlan direction must match request."
        }
        require(strategyPlan == null || strategyPlan.mode == request.mode) {
            "QueuedSynchronizationWork strategyPlan mode must match request."
        }
        require(strategyPlan == null || strategyPlan.durableContinuation != null) {
            "QueuedSynchronizationWork strategyPlan requires a durable continuation."
        }
    }
""",
)
replace_once(
    work_path,
    """            bindings == other.bindings &&
            strategyDecision == other.strategyDecision
""",
    """            bindings == other.bindings &&
            strategyDecision == other.strategyDecision &&
            strategyPlan == other.strategyPlan
""",
)
replace_once(
    work_path,
    """        result = 31 * result + (strategyDecision?.hashCode() ?: 0)
        return result
""",
    """        result = 31 * result + (strategyDecision?.hashCode() ?: 0)
        result = 31 * result + (strategyPlan?.hashCode() ?: 0)
        return result
""",
)
replace_once(
    work_path,
    """            "hasStrategyDecision=${strategyDecision != null}" +
            ")"
""",
    """            "hasStrategyDecision=${strategyDecision != null}, " +
            "hasStrategyPlan=${strategyPlan != null}" +
            ")"
""",
)

# ---------------------------------------------------------------------------
# Queue-submission encoders and work resolvers cannot change, drop, or invent
# the complete plan.
# ---------------------------------------------------------------------------
preflight = (
    "dataloom-runtime/src/commonMain/kotlin/io/dataloom/runtime/submission/"
    "QueueSubmissionPreflight.kt"
)
replace_once(
    preflight,
    """        if (entry.strategyDecision != submission.work.strategyDecision) {
            return ContractViolationError(
                message = "Encoded strategy decision does not match submitted work.",
            )
        }

        return null
""",
    """        if (entry.strategyDecision != submission.work.strategyDecision) {
            return ContractViolationError(
                message = "Encoded strategy decision does not match submitted work.",
            )
        }

        if (entry.strategyPlan != submission.work.strategyPlan) {
            return ContractViolationError(
                message = "Encoded strategy plan does not match submitted work.",
            )
        }

        return null
""",
)

correspondence = (
    "dataloom-runtime/src/commonMain/kotlin/io/dataloom/runtime/queue/"
    "QueuedStrategyDecisionCorrespondence.kt"
)
replace_once(
    correspondence,
    "import io.dataloom.api.strategy.PersistedStrategyDecision\n",
    """import io.dataloom.api.strategy.PersistedStrategyDecision
import io.dataloom.api.strategy.StrategyExecutionPlan
""",
)
replace_once(
    correspondence,
    """    ): DataLoomError? = validate(
        durableDecision = entry.strategyDecision,
        resolvedDecision = work.strategyDecision,
    )

    internal fun validate(
        durableDecision: PersistedStrategyDecision?,
        resolvedDecision: PersistedStrategyDecision?,
    ): DataLoomError? = if (durableDecision == resolvedDecision) {
        null
    } else {
        QueuedStrategyDecisionMismatchError()
    }
""",
    """    ): DataLoomError? = validate(
        durableDecision = entry.strategyDecision,
        resolvedDecision = work.strategyDecision,
        durablePlan = entry.strategyPlan,
        resolvedPlan = work.strategyPlan,
    )

    internal fun validate(
        durableDecision: PersistedStrategyDecision?,
        resolvedDecision: PersistedStrategyDecision?,
    ): DataLoomError? = validate(
        durableDecision = durableDecision,
        resolvedDecision = resolvedDecision,
        durablePlan = null,
        resolvedPlan = null,
    )

    internal fun validate(
        durableDecision: PersistedStrategyDecision?,
        resolvedDecision: PersistedStrategyDecision?,
        durablePlan: StrategyExecutionPlan?,
        resolvedPlan: StrategyExecutionPlan?,
    ): DataLoomError? = when {
        durableDecision != resolvedDecision -> QueuedStrategyDecisionMismatchError()
        durablePlan != resolvedPlan -> QueuedStrategyPlanMismatchError()
        else -> null
    }
""",
)
replace_once(
    correspondence,
    """    private data class QueuedStrategyDecisionMismatchError(
        override val code: ErrorCode = ErrorCode("DL-Q-STRATEGY-DECISION-MISMATCH"),
        override val category: ErrorCategory = ErrorCategory.CONFIGURATION,
        override val severity: ErrorSeverity = ErrorSeverity.ERROR,
        override val recoverability: Recoverability = Recoverability.NON_RECOVERABLE,
        override val message: String =
            "Resolved queued work does not match the durable strategy decision.",
        override val cause: Throwable? = null,
    ) : DataLoomError
}
""",
    """    private data class QueuedStrategyDecisionMismatchError(
        override val code: ErrorCode = ErrorCode("DL-Q-STRATEGY-DECISION-MISMATCH"),
        override val category: ErrorCategory = ErrorCategory.CONFIGURATION,
        override val severity: ErrorSeverity = ErrorSeverity.ERROR,
        override val recoverability: Recoverability = Recoverability.NON_RECOVERABLE,
        override val message: String =
            "Resolved queued work does not match the durable strategy decision.",
        override val cause: Throwable? = null,
    ) : DataLoomError

    /** Canonical, redacted complete-plan resolver-contract failure. */
    private data class QueuedStrategyPlanMismatchError(
        override val code: ErrorCode = ErrorCode("DL-Q-STRATEGY-PLAN-MISMATCH"),
        override val category: ErrorCategory = ErrorCategory.CONFIGURATION,
        override val severity: ErrorSeverity = ErrorSeverity.ERROR,
        override val recoverability: Recoverability = Recoverability.NON_RECOVERABLE,
        override val message: String =
            "Resolved queued work does not match the durable strategy plan.",
        override val cause: Throwable? = null,
    ) : DataLoomError
}
""",
)

# ---------------------------------------------------------------------------
# Foundation, invariant, and correspondence tests.
# ---------------------------------------------------------------------------
write(
    "dataloom-api/src/commonTest/kotlin/io/dataloom/api/strategy/"
    "PersistedStrategyDecisionPlanCorrespondenceTest.kt",
    r'''package io.dataloom.api.strategy

import io.dataloom.api.model.SynchronizationDirection
import io.dataloom.api.model.SynchronizationMode
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PersistedStrategyDecisionPlanCorrespondenceTest {

    @Test
    fun exactIdentityCorresponds() {
        val plan = plan()
        assertTrue(decision().correspondsTo(plan))
    }

    @Test
    fun everyIdentityDimensionIsChecked() {
        val plan = plan()
        assertFalse(decision(planId = "other").correspondsTo(plan))
        assertFalse(
            decision(requested = BuiltInSynchronizationStrategy.REMOTE_FIRST)
                .correspondsTo(plan),
        )
        assertFalse(decision(profileId = "other").correspondsTo(plan))
        assertFalse(
            decision(effective = BuiltInSynchronizationStrategy.CACHE_FIRST)
                .correspondsTo(plan),
        )
        assertFalse(decision(version = 8L).correspondsTo(plan))
        assertFalse(decision(disposition = StrategyDisposition.EXECUTE).correspondsTo(plan))
    }

    private fun decision(
        planId: String = "plan-1",
        requested: BuiltInSynchronizationStrategy = BuiltInSynchronizationStrategy.ADAPTIVE,
        profileId: String = "offline-profile",
        effective: BuiltInSynchronizationStrategy = BuiltInSynchronizationStrategy.OFFLINE_FIRST,
        version: Long = 7L,
        disposition: StrategyDisposition = StrategyDisposition.DEFER,
    ): PersistedStrategyDecision = PersistedStrategyDecision(
        decisionId = StrategyDecisionId("decision-1"),
        planId = StrategyPlanId(planId),
        requestedStrategy = requested,
        effectiveProfileId = StrategyProfileId(profileId),
        effectiveStrategy = effective,
        configurationVersion = StrategyConfigurationVersion(version),
        disposition = disposition,
    )

    private fun plan(): StrategyExecutionPlan = StrategyExecutionPlan(
        id = StrategyPlanId("plan-1"),
        requestedStrategy = BuiltInSynchronizationStrategy.ADAPTIVE,
        effectiveProfileId = StrategyProfileId("offline-profile"),
        effectiveStrategy = BuiltInSynchronizationStrategy.OFFLINE_FIRST,
        configurationVersion = StrategyConfigurationVersion(7L),
        direction = SynchronizationDirection.PUSH,
        mode = SynchronizationMode.DELTA,
        disposition = StrategyDisposition.DEFER,
        operations = listOf(
            StrategyOperation.ACCEPT_LOCAL,
            StrategyOperation.ENQUEUE_DURABLE_WORK,
        ),
        requiredCapabilities = setOf(
            StrategyProviderCapability.STORAGE,
            StrategyProviderCapability.QUEUE,
        ),
        dataOrigin = StrategyDataOrigin.LOCAL,
        consistency = StrategyConsistency.LOCAL_AUTHORITATIVE,
        deferralReason = StrategyDeferralReason.CONNECTIVITY_UNAVAILABLE,
        durableContinuation = StrategyDurableContinuationPlan(
            operations = listOf(StrategyOperation.READ_LOCAL, StrategyOperation.PUSH_REMOTE),
            requiredCapabilities = setOf(
                StrategyProviderCapability.STORAGE,
                StrategyProviderCapability.TRANSPORT,
            ),
            dataOrigin = StrategyDataOrigin.NONE,
            consistency = StrategyConsistency.LOCAL_AUTHORITATIVE,
        ),
    )
}
''',
)

write(
    "dataloom-api/src/commonTest/kotlin/io/dataloom/api/queue/QueueEntryStrategyPlanTest.kt",
    r'''package io.dataloom.api.queue

import io.dataloom.api.context.ExecutionContext
import io.dataloom.api.identifier.CorrelationId
import io.dataloom.api.identifier.ExecutionId
import io.dataloom.api.identifier.QueueEntryId
import io.dataloom.api.identifier.SynchronizationSessionId
import io.dataloom.api.identifier.WorkflowId
import io.dataloom.api.model.SynchronizationDirection
import io.dataloom.api.model.SynchronizationMode
import io.dataloom.api.model.SynchronizationRequest
import io.dataloom.api.strategy.BuiltInSynchronizationStrategy
import io.dataloom.api.strategy.PersistedStrategyDecision
import io.dataloom.api.strategy.StrategyConfigurationVersion
import io.dataloom.api.strategy.StrategyConsistency
import io.dataloom.api.strategy.StrategyDataOrigin
import io.dataloom.api.strategy.StrategyDecisionId
import io.dataloom.api.strategy.StrategyDeferralReason
import io.dataloom.api.strategy.StrategyDisposition
import io.dataloom.api.strategy.StrategyDurableContinuationPlan
import io.dataloom.api.strategy.StrategyExecutionPlan
import io.dataloom.api.strategy.StrategyOperation
import io.dataloom.api.strategy.StrategyPlanId
import io.dataloom.api.strategy.StrategyProfileId
import io.dataloom.api.strategy.StrategyProviderCapability
import io.dataloom.api.time.DataLoomInstant
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class QueueEntryStrategyPlanTest {

    @Test
    fun completeAcceptedPlanIsAdmittedWithoutDiagnosticDisclosure() {
        val entry = entry(decision(), plan())
        val diagnostic = entry.toString()
        assertFalse("plan-sensitive" in diagnostic)
        assertFalse("profile-sensitive" in diagnostic)
    }

    @Test
    fun planWithoutDecisionIsRejected() {
        assertFailsWith<IllegalArgumentException> { entry(null, plan()) }
    }

    @Test
    fun identityDirectionModeAndContinuationAreRequired() {
        assertFailsWith<IllegalArgumentException> {
            entry(decision(version = 2L), plan())
        }
        assertFailsWith<IllegalArgumentException> {
            entry(decision(), plan(direction = SynchronizationDirection.PULL))
        }
        assertFailsWith<IllegalArgumentException> {
            entry(decision(), plan(includeContinuation = false))
        }
    }

    private fun entry(
        decision: PersistedStrategyDecision?,
        plan: StrategyExecutionPlan?,
    ): QueueEntry = QueueEntry(
        id = QueueEntryId("entry-1"),
        synchronizationRequest = request(),
        state = QueueEntryState.PENDING,
        enqueuedAt = DataLoomInstant(1_000L),
        availableAt = DataLoomInstant(1_000L),
        strategyDecision = decision,
        strategyPlan = plan,
    )

    private fun request(): SynchronizationRequest = SynchronizationRequest(
        workflowId = WorkflowId("workflow-1"),
        sessionId = SynchronizationSessionId("session-1"),
        direction = SynchronizationDirection.PUSH,
        mode = SynchronizationMode.DELTA,
        context = ExecutionContext(
            executionId = ExecutionId("execution-1"),
            correlationId = CorrelationId("correlation-1"),
        ),
    )

    private fun decision(version: Long = 1L): PersistedStrategyDecision =
        PersistedStrategyDecision(
            decisionId = StrategyDecisionId("decision-sensitive"),
            planId = StrategyPlanId("plan-sensitive"),
            requestedStrategy = BuiltInSynchronizationStrategy.ADAPTIVE,
            effectiveProfileId = StrategyProfileId("profile-sensitive"),
            effectiveStrategy = BuiltInSynchronizationStrategy.OFFLINE_FIRST,
            configurationVersion = StrategyConfigurationVersion(version),
            disposition = StrategyDisposition.DEFER,
        )

    private fun plan(
        direction: SynchronizationDirection = SynchronizationDirection.PUSH,
        includeContinuation: Boolean = true,
    ): StrategyExecutionPlan = StrategyExecutionPlan(
        id = StrategyPlanId("plan-sensitive"),
        requestedStrategy = BuiltInSynchronizationStrategy.ADAPTIVE,
        effectiveProfileId = StrategyProfileId("profile-sensitive"),
        effectiveStrategy = BuiltInSynchronizationStrategy.OFFLINE_FIRST,
        configurationVersion = StrategyConfigurationVersion(1L),
        direction = direction,
        mode = SynchronizationMode.DELTA,
        disposition = StrategyDisposition.DEFER,
        operations = listOf(
            StrategyOperation.ACCEPT_LOCAL,
            StrategyOperation.ENQUEUE_DURABLE_WORK,
        ),
        requiredCapabilities = setOf(
            StrategyProviderCapability.STORAGE,
            StrategyProviderCapability.QUEUE,
        ),
        dataOrigin = StrategyDataOrigin.LOCAL,
        consistency = StrategyConsistency.LOCAL_AUTHORITATIVE,
        deferralReason = StrategyDeferralReason.CONNECTIVITY_UNAVAILABLE,
        durableContinuation = if (includeContinuation) {
            StrategyDurableContinuationPlan(
                operations = listOf(
                    StrategyOperation.READ_LOCAL,
                    StrategyOperation.PUSH_REMOTE,
                ),
                requiredCapabilities = setOf(
                    StrategyProviderCapability.STORAGE,
                    StrategyProviderCapability.TRANSPORT,
                ),
                dataOrigin = StrategyDataOrigin.NONE,
                consistency = StrategyConsistency.LOCAL_AUTHORITATIVE,
            )
        } else {
            null
        },
    )
}
''',
)

write(
    "dataloom-runtime/src/commonTest/kotlin/io/dataloom/runtime/queue/"
    "QueuedStrategyPlanCorrespondenceTest.kt",
    r'''package io.dataloom.runtime.queue

import io.dataloom.api.error.ErrorCategory
import io.dataloom.api.error.Recoverability
import io.dataloom.api.model.SynchronizationDirection
import io.dataloom.api.model.SynchronizationMode
import io.dataloom.api.strategy.BuiltInSynchronizationStrategy
import io.dataloom.api.strategy.PersistedStrategyDecision
import io.dataloom.api.strategy.StrategyConfigurationVersion
import io.dataloom.api.strategy.StrategyConsistency
import io.dataloom.api.strategy.StrategyDataOrigin
import io.dataloom.api.strategy.StrategyDecisionId
import io.dataloom.api.strategy.StrategyDeferralReason
import io.dataloom.api.strategy.StrategyDisposition
import io.dataloom.api.strategy.StrategyDurableContinuationPlan
import io.dataloom.api.strategy.StrategyExecutionPlan
import io.dataloom.api.strategy.StrategyOperation
import io.dataloom.api.strategy.StrategyPlanId
import io.dataloom.api.strategy.StrategyProfileId
import io.dataloom.api.strategy.StrategyProviderCapability
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class QueuedStrategyPlanCorrespondenceTest {

    @Test
    fun exactDecisionAndPlanCorrespond() {
        val decision = decision()
        val plan = plan()
        assertNull(
            QueuedStrategyDecisionCorrespondence.validate(
                durableDecision = decision,
                resolvedDecision = decision,
                durablePlan = plan,
                resolvedPlan = plan,
            ),
        )
    }

    @Test
    fun changedDroppedAndInventedPlansFailClosed() {
        val decision = decision()
        assertPlanFailure(
            QueuedStrategyDecisionCorrespondence.validate(
                decision,
                decision,
                plan(continuationOperation = StrategyOperation.PUSH_REMOTE),
                plan(continuationOperation = StrategyOperation.RECONCILE),
            ),
        )
        assertPlanFailure(
            QueuedStrategyDecisionCorrespondence.validate(
                decision,
                decision,
                plan(),
                null,
            ),
        )
        assertPlanFailure(
            QueuedStrategyDecisionCorrespondence.validate(
                decision,
                decision,
                null,
                plan(),
            ),
        )
    }

    @Test
    fun planFailureDiagnosticsExcludeDynamicIdentifiersAndContents() {
        val error = assertNotNull(
            QueuedStrategyDecisionCorrespondence.validate(
                decision(),
                decision(),
                plan(planId = "durable-sensitive"),
                plan(planId = "resolved-sensitive"),
            ),
        )
        val diagnostic = error.toString()
        assertFalse("durable-sensitive" in diagnostic)
        assertFalse("resolved-sensitive" in diagnostic)
    }

    private fun assertPlanFailure(error: io.dataloom.api.error.DataLoomError?) {
        val actual = assertNotNull(error)
        assertEquals("DL-Q-STRATEGY-PLAN-MISMATCH", actual.code.value)
        assertEquals(ErrorCategory.CONFIGURATION, actual.category)
        assertEquals(Recoverability.NON_RECOVERABLE, actual.recoverability)
        assertEquals(null, actual.cause)
    }

    private fun decision(): PersistedStrategyDecision = PersistedStrategyDecision(
        decisionId = StrategyDecisionId("decision-1"),
        planId = StrategyPlanId("plan-1"),
        requestedStrategy = BuiltInSynchronizationStrategy.ADAPTIVE,
        effectiveProfileId = StrategyProfileId("profile-1"),
        effectiveStrategy = BuiltInSynchronizationStrategy.OFFLINE_FIRST,
        configurationVersion = StrategyConfigurationVersion(1L),
        disposition = StrategyDisposition.DEFER,
    )

    private fun plan(
        planId: String = "plan-1",
        continuationOperation: StrategyOperation = StrategyOperation.PUSH_REMOTE,
    ): StrategyExecutionPlan = StrategyExecutionPlan(
        id = StrategyPlanId(planId),
        requestedStrategy = BuiltInSynchronizationStrategy.ADAPTIVE,
        effectiveProfileId = StrategyProfileId("profile-1"),
        effectiveStrategy = BuiltInSynchronizationStrategy.OFFLINE_FIRST,
        configurationVersion = StrategyConfigurationVersion(1L),
        direction = SynchronizationDirection.PUSH,
        mode = SynchronizationMode.DELTA,
        disposition = StrategyDisposition.DEFER,
        operations = listOf(StrategyOperation.ENQUEUE_DURABLE_WORK),
        requiredCapabilities = setOf(StrategyProviderCapability.QUEUE),
        dataOrigin = StrategyDataOrigin.NONE,
        consistency = StrategyConsistency.LOCAL_AUTHORITATIVE,
        deferralReason = StrategyDeferralReason.CONNECTIVITY_UNAVAILABLE,
        durableContinuation = StrategyDurableContinuationPlan(
            operations = if (continuationOperation == StrategyOperation.RECONCILE) {
                listOf(StrategyOperation.PUSH_REMOTE, StrategyOperation.RECONCILE)
            } else {
                listOf(StrategyOperation.PUSH_REMOTE)
            },
            requiredCapabilities = setOf(StrategyProviderCapability.TRANSPORT),
            dataOrigin = StrategyDataOrigin.NONE,
            consistency = StrategyConsistency.LOCAL_AUTHORITATIVE,
        ),
    )
}
''',
)

# Exact codec and evaluator tests from the qualified foundation.
write(
    "dataloom-api/src/commonTest/kotlin/io/dataloom/api/strategy/StrategyExecutionPlanCodecTest.kt",
    r'''package io.dataloom.api.strategy

import io.dataloom.api.model.SynchronizationDirection
import io.dataloom.api.model.SynchronizationMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class StrategyExecutionPlanCodecTest {

    @Test
    fun roundTripPreservesAcceptedPlanAndContinuation() {
        val plan = plan()
        assertEquals(plan, StrategyExecutionPlanCodec.decode(StrategyExecutionPlanCodec.encode(plan)))
    }

    @Test
    fun capabilityAndOutcomeSetsEncodeDeterministically() {
        val first = plan(
            continuationCapabilities = linkedSetOf(
                StrategyProviderCapability.TRANSPORT,
                StrategyProviderCapability.STORAGE,
            ),
        )
        val second = plan(
            continuationCapabilities = linkedSetOf(
                StrategyProviderCapability.STORAGE,
                StrategyProviderCapability.TRANSPORT,
            ),
        )
        assertEquals(
            StrategyExecutionPlanCodec.encode(first),
            StrategyExecutionPlanCodec.encode(second),
        )
    }

    @Test
    fun malformedAndUnsupportedFramesFailClosed() {
        assertFailsWith<IllegalArgumentException> {
            StrategyExecutionPlanCodec.decode("not-a-plan")
        }
        val encoded = StrategyExecutionPlanCodec.encode(plan())
        assertFailsWith<IllegalArgumentException> {
            StrategyExecutionPlanCodec.decode(encoded.replace("|1|", "|2|"))
        }
    }

    @Test
    fun diagnosticsDoNotExposeEncodedDynamicIdentifiers() {
        val plan = plan(
            planId = "sensitive-plan",
            profileId = "sensitive-profile",
        )
        val diagnostic = plan.durableContinuation.toString()
        assertFalse("sensitive-plan" in diagnostic)
        assertFalse("sensitive-profile" in diagnostic)
    }

    private fun plan(
        planId: String = "plan-雪-1",
        profileId: String = "profile-1",
        continuationCapabilities: Set<StrategyProviderCapability> = setOf(
            StrategyProviderCapability.STORAGE,
            StrategyProviderCapability.TRANSPORT,
        ),
    ): StrategyExecutionPlan {
        val fallback = StrategyFallbackPlan(
            remoteOutcomes = setOf(
                StrategyRemoteOutcome.UNAVAILABLE,
                StrategyRemoteOutcome.SERVER_FAILURE,
            ),
            operations = listOf(StrategyOperation.SERVE_LOCAL),
            dataOrigin = StrategyDataOrigin.LOCAL,
        )
        return StrategyExecutionPlan(
            id = StrategyPlanId(planId),
            requestedStrategy = BuiltInSynchronizationStrategy.ADAPTIVE,
            effectiveProfileId = StrategyProfileId(profileId),
            effectiveStrategy = BuiltInSynchronizationStrategy.REMOTE_FIRST,
            configurationVersion = StrategyConfigurationVersion(11L),
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
                    StrategyOperation.READ_CHECKPOINT,
                    StrategyOperation.PULL_REMOTE,
                    StrategyOperation.PERSIST_REMOTE,
                ),
                requiredCapabilities = continuationCapabilities,
                dataOrigin = StrategyDataOrigin.REMOTE,
                consistency = StrategyConsistency.REMOTE_AUTHORITATIVE,
                evaluatedCacheState = StrategyCacheState.STALE,
                fallbackPlan = fallback,
            ),
        )
    }
}
''',
)

write(
    "dataloom-runtime/src/commonTest/kotlin/io/dataloom/runtime/strategy/"
    "StrategyDurableContinuationEvaluationTest.kt",
    r'''package io.dataloom.runtime.strategy

import io.dataloom.api.model.SynchronizationDirection
import io.dataloom.api.model.SynchronizationMode
import io.dataloom.api.strategy.AdaptiveStrategyProfile
import io.dataloom.api.strategy.CacheFirstStrategyProfile
import io.dataloom.api.strategy.OfflineFirstStrategyProfile
import io.dataloom.api.strategy.RemoteFirstStrategyProfile
import io.dataloom.api.strategy.StrategyCacheState
import io.dataloom.api.strategy.StrategyConfigurationVersion
import io.dataloom.api.strategy.StrategyConnectivity
import io.dataloom.api.strategy.StrategyDecisionId
import io.dataloom.api.strategy.StrategyEvaluationRequest
import io.dataloom.api.strategy.StrategyOperation
import io.dataloom.api.strategy.StrategyPlanId
import io.dataloom.api.strategy.StrategyProfileId
import io.dataloom.api.strategy.StrategyRemoteOutcome
import io.dataloom.api.strategy.StrategyRuntimeEvidence
import io.dataloom.api.strategy.UnknownConnectivityPolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class StrategyDurableContinuationEvaluationTest {

    private val evaluator = BuiltInSynchronizationStrategyEvaluator()

    @Test
    fun offlineFirstFreezesRemoteReconciliationContinuation() {
        val result = evaluate(
            OfflineFirstStrategyProfile(
                id = StrategyProfileId("offline"),
                configurationVersion = StrategyConfigurationVersion(1L),
            ),
            connectivity = StrategyConnectivity.UNAVAILABLE,
        )
        val continuation = assertNotNull(result.plan.durableContinuation)
        assertEquals(
            listOf(
                StrategyOperation.READ_LOCAL,
                StrategyOperation.PUSH_REMOTE,
                StrategyOperation.RECONCILE,
            ),
            continuation.operations,
        )
    }

    @Test
    fun durableCacheRefreshFreezesPullAndPersistence() {
        val result = evaluate(
            CacheFirstStrategyProfile(
                id = StrategyProfileId("cache"),
                configurationVersion = StrategyConfigurationVersion(2L),
                refreshOnFreshHit = true,
                requireDurableRefresh = true,
            ),
            direction = SynchronizationDirection.PULL,
            connectivity = StrategyConnectivity.AVAILABLE,
            cacheState = StrategyCacheState.FRESH,
        )
        val continuation = assertNotNull(result.plan.durableContinuation)
        assertEquals(
            listOf(
                StrategyOperation.READ_CHECKPOINT,
                StrategyOperation.PULL_REMOTE,
                StrategyOperation.PERSIST_REMOTE,
            ),
            continuation.operations,
        )
    }

    @Test
    fun remoteFirstUnknownConnectivityFreezesTypedFallbackBranch() {
        val result = evaluate(
            RemoteFirstStrategyProfile(
                id = StrategyProfileId("remote"),
                configurationVersion = StrategyConfigurationVersion(3L),
                fallbackOn = setOf(StrategyRemoteOutcome.UNAVAILABLE),
                unknownConnectivityPolicy = UnknownConnectivityPolicy.DEFER,
            ),
            direction = SynchronizationDirection.PULL,
            connectivity = StrategyConnectivity.UNKNOWN,
            cacheState = StrategyCacheState.STALE,
        )
        val continuation = assertNotNull(result.plan.durableContinuation)
        assertTrue(StrategyOperation.PULL_REMOTE in continuation.operations)
        assertEquals(
            setOf(StrategyRemoteOutcome.UNAVAILABLE),
            assertNotNull(continuation.fallbackPlan).remoteOutcomes,
        )
        assertEquals(StrategyCacheState.STALE, continuation.evaluatedCacheState)
    }

    @Test
    fun adaptiveSelectionFreezesConcreteCandidateContinuation() {
        val offline = OfflineFirstStrategyProfile(
            id = StrategyProfileId("offline-candidate"),
            configurationVersion = StrategyConfigurationVersion(4L),
        )
        val result = evaluate(
            AdaptiveStrategyProfile(
                id = StrategyProfileId("adaptive"),
                configurationVersion = StrategyConfigurationVersion(5L),
                candidates = listOf(offline),
                safeDefaultProfileId = offline.id,
            ),
            connectivity = StrategyConnectivity.UNAVAILABLE,
        )
        assertEquals(offline.id, result.plan.effectiveProfileId)
        assertNotNull(result.plan.durableContinuation)
    }

    private fun evaluate(
        profile: io.dataloom.api.strategy.SynchronizationStrategyProfile,
        direction: SynchronizationDirection = SynchronizationDirection.PUSH,
        connectivity: StrategyConnectivity,
        cacheState: StrategyCacheState = StrategyCacheState.MISSING,
    ) = evaluator.evaluate(
        StrategyEvaluationRequest(
            decisionId = StrategyDecisionId("decision"),
            planId = StrategyPlanId("plan"),
            profile = profile,
            direction = direction,
            mode = SynchronizationMode.DELTA,
            evidence = StrategyRuntimeEvidence(
                connectivity = connectivity,
                cacheState = cacheState,
                hasPendingLocalChanges = true,
            ),
        ),
    )
}
''',
)

# Admission tests gain continuation evidence and the missing-continuation case.
admission_test = (
    "dataloom-runtime/src/commonTest/kotlin/io/dataloom/runtime/strategy/"
    "StrategyQueueAdmissionEvaluatorTest.kt"
)
replace_once(
    admission_test,
    "import io.dataloom.api.strategy.StrategyDisposition\n",
    """import io.dataloom.api.strategy.StrategyDisposition
import io.dataloom.api.strategy.StrategyDurableContinuationPlan
""",
)
replace_once(
    admission_test,
    """    @Test
    fun queueOperationWithoutQueueCapabilityIsRejected() {
""",
    """    @Test
    fun planWithoutDurableContinuationIsRejected() {
        val plan = plan(
            disposition = StrategyDisposition.DEFER,
            operations = listOf(StrategyOperation.ENQUEUE_DURABLE_WORK),
            capabilities = setOf(StrategyProviderCapability.QUEUE),
            deferralReason = StrategyDeferralReason.CONNECTIVITY_UNAVAILABLE,
            includeContinuation = false,
        )

        val rejected = assertIs<StrategyQueueAdmissionResult.Rejected>(
            StrategyQueueAdmissionEvaluator.evaluate(evaluation(plan)),
        )

        assertEquals(
            StrategyQueueAdmissionRejectionReason.MISSING_DURABLE_CONTINUATION,
            rejected.reason,
        )
    }

    @Test
    fun queueOperationWithoutQueueCapabilityIsRejected() {
""",
)
replace_once(
    admission_test,
    """        rejectionReason: StrategyRejectionReason? = null,
    ): StrategyExecutionPlan = StrategyExecutionPlan(
""",
    """        rejectionReason: StrategyRejectionReason? = null,
        includeContinuation: Boolean = true,
    ): StrategyExecutionPlan = StrategyExecutionPlan(
""",
)
replace_once(
    admission_test,
    """        rejectionReason = rejectionReason,
    )
}
""",
    """        rejectionReason = rejectionReason,
        durableContinuation = if (
            includeContinuation &&
            StrategyOperation.ENQUEUE_DURABLE_WORK in operations
        ) {
            StrategyDurableContinuationPlan(
                operations = listOf(StrategyOperation.PUSH_REMOTE),
                requiredCapabilities = setOf(StrategyProviderCapability.TRANSPORT),
                dataOrigin = StrategyDataOrigin.NONE,
                consistency = StrategyConsistency.READ_YOUR_WRITES,
            )
        } else {
            null
        },
    )
}
""",
)

# Submission preflight tests cover exact plan correspondence.
submission_test = (
    "dataloom-runtime/src/commonTest/kotlin/io/dataloom/runtime/submission/"
    "QueueSubmissionStrategyDecisionPreflightTest.kt"
)
replace_once(
    submission_test,
    "import io.dataloom.api.strategy.StrategyDisposition\n",
    """import io.dataloom.api.strategy.StrategyConsistency
import io.dataloom.api.strategy.StrategyDataOrigin
import io.dataloom.api.strategy.StrategyDeferralReason
import io.dataloom.api.strategy.StrategyDisposition
import io.dataloom.api.strategy.StrategyDurableContinuationPlan
import io.dataloom.api.strategy.StrategyExecutionPlan
import io.dataloom.api.strategy.StrategyOperation
import io.dataloom.api.strategy.StrategyProviderCapability
""",
)
replace_once(
    submission_test,
    """    private fun encoder(entry: QueueEntry): QueuedSynchronizationWorkEncoder =
""",
    """    @Test
    fun matchingCompleteStrategyPlanIsAccepted() {
        val decision = decision()
        val plan = plan()
        val submission = submission(decision, plan)
        val preflight = QueueSubmissionPreflight(encoder(entry(decision, plan)))

        assertIs<QueueSubmissionPreflightResult.Ready>(preflight.prepare(submission))
    }

    @Test
    fun encoderCannotChangeDropOrInventStrategyPlan() {
        val decision = decision()
        val original = plan()
        val changed = plan(continuationOperation = StrategyOperation.RECONCILE)
        assertIs<QueueSubmissionPreflightResult.ContractViolation>(
            QueueSubmissionPreflight(encoder(entry(decision, changed)))
                .prepare(submission(decision, original)),
        )
        assertIs<QueueSubmissionPreflightResult.ContractViolation>(
            QueueSubmissionPreflight(encoder(entry(decision, null)))
                .prepare(submission(decision, original)),
        )
        assertIs<QueueSubmissionPreflightResult.ContractViolation>(
            QueueSubmissionPreflight(encoder(entry(decision, original)))
                .prepare(submission(decision, null)),
        )
    }

    private fun encoder(entry: QueueEntry): QueuedSynchronizationWorkEncoder =
""",
)
replace_once(
    submission_test,
    """    private fun submission(
        decision: PersistedStrategyDecision?,
    ): QueuedSynchronizationSubmission = QueuedSynchronizationSubmission(
""",
    """    private fun submission(
        decision: PersistedStrategyDecision?,
        plan: StrategyExecutionPlan? = null,
    ): QueuedSynchronizationSubmission = QueuedSynchronizationSubmission(
""",
)
replace_once(
    submission_test,
    """            strategyDecision = decision,
        ),
""",
    """            strategyDecision = decision,
            strategyPlan = plan,
        ),
""",
)
replace_once(
    submission_test,
    """    private fun entry(decision: PersistedStrategyDecision?): QueueEntry = QueueEntry(
""",
    """    private fun entry(
        decision: PersistedStrategyDecision?,
        plan: StrategyExecutionPlan? = null,
    ): QueueEntry = QueueEntry(
""",
)
replace_once(
    submission_test,
    """        strategyDecision = decision,
    )

    private fun decision(version: Long = 3L): PersistedStrategyDecision =
""",
    """        strategyDecision = decision,
        strategyPlan = plan,
    )

    private fun plan(
        continuationOperation: StrategyOperation = StrategyOperation.PUSH_REMOTE,
    ): StrategyExecutionPlan = StrategyExecutionPlan(
        id = StrategyPlanId("plan-1"),
        requestedStrategy = BuiltInSynchronizationStrategy.ADAPTIVE,
        effectiveProfileId = StrategyProfileId("offline-profile"),
        effectiveStrategy = BuiltInSynchronizationStrategy.OFFLINE_FIRST,
        configurationVersion = StrategyConfigurationVersion(3L),
        direction = SynchronizationDirection.PUSH,
        mode = SynchronizationMode.DELTA,
        disposition = StrategyDisposition.DEFER,
        operations = listOf(StrategyOperation.ENQUEUE_DURABLE_WORK),
        requiredCapabilities = setOf(StrategyProviderCapability.QUEUE),
        dataOrigin = StrategyDataOrigin.NONE,
        consistency = StrategyConsistency.LOCAL_AUTHORITATIVE,
        deferralReason = StrategyDeferralReason.CONNECTIVITY_UNAVAILABLE,
        durableContinuation = StrategyDurableContinuationPlan(
            operations = if (continuationOperation == StrategyOperation.RECONCILE) {
                listOf(StrategyOperation.PUSH_REMOTE, StrategyOperation.RECONCILE)
            } else {
                listOf(StrategyOperation.PUSH_REMOTE)
            },
            requiredCapabilities = setOf(StrategyProviderCapability.TRANSPORT),
            dataOrigin = StrategyDataOrigin.NONE,
            consistency = StrategyConsistency.LOCAL_AUTHORITATIVE,
        ),
    )

    private fun decision(version: Long = 3L): PersistedStrategyDecision =
""",
)

# External-consumer coverage.
write(
    "runtime-external-consumer/src/commonMain/kotlin/io/dataloom/consumer/"
    "StrategyAcceptedPlanCodecExternalConsumerProbe.kt",
    r'''package io.dataloom.consumer

import io.dataloom.api.model.SynchronizationDirection
import io.dataloom.api.model.SynchronizationMode
import io.dataloom.api.strategy.BuiltInSynchronizationStrategy
import io.dataloom.api.strategy.StrategyConfigurationVersion
import io.dataloom.api.strategy.StrategyConsistency
import io.dataloom.api.strategy.StrategyDataOrigin
import io.dataloom.api.strategy.StrategyDeferralReason
import io.dataloom.api.strategy.StrategyDisposition
import io.dataloom.api.strategy.StrategyDurableContinuationPlan
import io.dataloom.api.strategy.StrategyExecutionPlan
import io.dataloom.api.strategy.StrategyExecutionPlanCodec
import io.dataloom.api.strategy.StrategyOperation
import io.dataloom.api.strategy.StrategyPlanId
import io.dataloom.api.strategy.StrategyProfileId
import io.dataloom.api.strategy.StrategyProviderCapability

public fun strategyAcceptedPlanCodecExternalConsumerProbe(): StrategyExecutionPlan {
    val plan = StrategyExecutionPlan(
        id = StrategyPlanId("external-plan"),
        requestedStrategy = BuiltInSynchronizationStrategy.OFFLINE_FIRST,
        effectiveProfileId = StrategyProfileId("external-profile"),
        effectiveStrategy = BuiltInSynchronizationStrategy.OFFLINE_FIRST,
        configurationVersion = StrategyConfigurationVersion(1L),
        direction = SynchronizationDirection.PUSH,
        mode = SynchronizationMode.DELTA,
        disposition = StrategyDisposition.DEFER,
        operations = listOf(
            StrategyOperation.ACCEPT_LOCAL,
            StrategyOperation.ENQUEUE_DURABLE_WORK,
        ),
        requiredCapabilities = setOf(
            StrategyProviderCapability.STORAGE,
            StrategyProviderCapability.QUEUE,
        ),
        dataOrigin = StrategyDataOrigin.NONE,
        consistency = StrategyConsistency.LOCAL_AUTHORITATIVE,
        deferralReason = StrategyDeferralReason.CONNECTIVITY_UNAVAILABLE,
        durableContinuation = StrategyDurableContinuationPlan(
            operations = listOf(
                StrategyOperation.READ_LOCAL,
                StrategyOperation.PUSH_REMOTE,
            ),
            requiredCapabilities = setOf(
                StrategyProviderCapability.STORAGE,
                StrategyProviderCapability.TRANSPORT,
            ),
            dataOrigin = StrategyDataOrigin.NONE,
            consistency = StrategyConsistency.LOCAL_AUTHORITATIVE,
        ),
    )
    return StrategyExecutionPlanCodec.decode(StrategyExecutionPlanCodec.encode(plan))
}
''',
)

# Current docs and audit remain explicit about the next persistence/replay gate.
strategy_doc = "docs/api/synchronization-strategy.md"
replace_once(
    strategy_doc,
    """The next execution gate must reconstruct or load the immutable accepted plan
from this identity. It must not re-evaluate current policy after retry, restart,
or platform rescheduling. An authorized migration is required to replace an
accepted plan.
""",
    """Every built-in plan that admits durable work now freezes a
`StrategyDurableContinuationPlan`: exact ordered operations, required
capabilities, origin, consistency, evaluated cache state, and finite fallback
branch. `StrategyExecutionPlanCodec` provides a bounded deterministic V1 frame.

Queue encoders and work resolvers must preserve both the exact decision and the
complete plan. Changed, dropped, or invented plan evidence fails before timeout,
clock, provider, circuit, retry, or coordinator work. Platform stores and the
accepted-plan execution coordinator are the next integration boundary; they
must never re-evaluate current policy after retry, restart, or rescheduling.
""",
)

write(
    "docs/audits/DL-039B-immutable-plan-contract-checkpoint.md",
    """# DL-039B immutable accepted-plan contract checkpoint

## Accepted behavior

- Every evaluated built-in plan that enters durable admission contains a finite
  immutable continuation selected during the original evaluation.
- Queue admission rejects identity-only plans.
- `QueueEntry` and `QueuedSynchronizationWork` carry the complete accepted plan
  beside the bounded decision identity.
- A plan must match the decision, request direction, request mode, and contain a
  durable continuation.
- Application-owned encoders and work resolvers cannot change, drop, or invent
  the plan.
- Plan mismatch stops before timeout, clock, provider/circuit, retry, execution,
  or a queue transition and reports only the redacted code
  `DL-Q-STRATEGY-PLAN-MISMATCH`.
- The deterministic bounded V1 codec round-trips identifiers, operations,
  capabilities, consistency, origin, cache state, and the finite fallback
  branch.

## Remaining integration in this branch

Android Room schema version 8, Apple queue format version 4, platform migration
and corruption evidence, and accepted-plan execution without policy
re-evaluation follow this contract checkpoint.
""",
)

print("Applied immutable queued strategy plan contract.")
