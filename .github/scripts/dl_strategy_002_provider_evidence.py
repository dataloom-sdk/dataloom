from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def read(path: str) -> str:
    return (ROOT / path).read_text()


def write(path: str, content: str) -> None:
    target = ROOT / path
    target.write_text(content.rstrip() + "\n")


def replace_once(path: str, old: str, new: str) -> None:
    content = read(path)
    count = content.count(old)
    if count != 1:
        raise SystemExit(
            f"Expected exactly one match in {path}, found {count}: {old[:100]!r}",
        )
    write(path, content.replace(old, new, 1))


provider_path = (
    "dataloom-runtime/src/iosMain/kotlin/io/dataloom/runtime/queue/"
    "AppleFileQueueProvider.kt"
)
replace_once(
    provider_path,
    """ * Version-1 entry-only snapshots remain readable. Every successful mutation
 * writes the version-2 entry-plus-receipt format and preserves existing
 * administrative retry receipts.
""",
    """ * Version-1 entry-only and version-2 entry-plus-receipt snapshots remain
 * readable. Every successful mutation writes the version-3
 * entry-plus-receipt-plus-strategy-decision format and preserves existing
 * administrative retry receipts and bounded strategy identity.
""",
)
replace_once(
    provider_path,
    """ * history, retry budgets, immutable workflow timeout evidence, lease state, and
 * sanitized canonical errors. It must not be used for credentials, tokens,
""",
    """ * history, retry budgets, immutable workflow timeout evidence, bounded strategy
 * decision identity, lease state, and sanitized canonical errors. It must not
 * be used for credentials, tokens,
""",
)

test_path = (
    "dataloom-runtime/src/iosTest/kotlin/io/dataloom/runtime/queue/"
    "AppleFileQueueProviderRetryTest.kt"
)
replace_once(
    test_path,
    "import io.dataloom.api.scheduling.SchedulingDelay\n",
    """import io.dataloom.api.scheduling.SchedulingDelay
import io.dataloom.api.strategy.BuiltInSynchronizationStrategy
import io.dataloom.api.strategy.PersistedStrategyDecision
import io.dataloom.api.strategy.StrategyConfigurationVersion
import io.dataloom.api.strategy.StrategyDecisionId
import io.dataloom.api.strategy.StrategyDisposition
import io.dataloom.api.strategy.StrategyPlanId
import io.dataloom.api.strategy.StrategyProfileId
""",
)
replace_once(
    test_path,
    """

    private suspend fun AppleFileQueueProvider.enqueueSuccess(entry: QueueEntry) {
""",
    r'''

    @Test
    fun `strategy decision survives restart retry deferral and lease recovery`() = runTest {
        val directory = uniqueDirectory()
        val expected = strategyDecision()
        AppleFileQueueProvider(directory).enqueueSuccess(
            entry(strategyDecision = expected),
        )

        val first = AppleFileQueueProvider(directory).acquireEntries(
            acquiredAt = 1_000L,
            expiresAt = 2_000L,
            leaseId = "strategy-lease-1",
        ).single()
        assertEquals(expected, first.strategyDecision)

        AppleFileQueueProvider(directory).reschedule(
            QueueRescheduleRequest(
                entryId = first.id,
                leaseId = requireNotNull(first.lease).id,
                retryAttempt = RetryAttempt(1),
                availableAt = DataLoomInstant(3_000L),
                error = testError(),
            ),
        ).assertSuccess()

        val retried = AppleFileQueueProvider(directory).acquireEntries(
            acquiredAt = 3_000L,
            expiresAt = 4_000L,
            leaseId = "strategy-lease-2",
        ).single()
        assertEquals(expected, retried.strategyDecision)

        AppleFileQueueProvider(directory).defer(
            QueueDeferralRequest(
                entryId = retried.id,
                leaseId = requireNotNull(retried.lease).id,
                availableAt = DataLoomInstant(5_000L),
                reason = QueueDeferralReason.CONNECTIVITY_REQUIREMENT_NOT_MET,
            ),
        ).assertSuccess()

        val deferred = AppleFileQueueProvider(directory).acquireEntries(
            acquiredAt = 5_000L,
            expiresAt = 5_500L,
            leaseId = "strategy-lease-3",
        ).single()
        assertEquals(expected, deferred.strategyDecision)

        AppleFileQueueProvider(directory).recoverExpiredLeases(
            ExpiredLeaseRecoveryRequest(DataLoomInstant(5_501L)),
        ).successValue()

        val recovered = AppleFileQueueProvider(directory).acquireEntries(
            acquiredAt = 5_501L,
            expiresAt = 6_000L,
            leaseId = "strategy-lease-4",
        ).single()
        assertEquals(expected, recovered.strategyDecision)
    }

    private suspend fun AppleFileQueueProvider.enqueueSuccess(entry: QueueEntry) {
''',
)
replace_once(
    test_path,
    """        workflowTimeoutState: WorkflowTimeoutState? = null,
        lastError: DataLoomError? = null,
""",
    """        workflowTimeoutState: WorkflowTimeoutState? = null,
        strategyDecision: PersistedStrategyDecision? = null,
        lastError: DataLoomError? = null,
""",
)
replace_once(
    test_path,
    """        retryBudgetState = retryBudgetState,
        workflowTimeoutState = workflowTimeoutState,
    )
""",
    """        retryBudgetState = retryBudgetState,
        workflowTimeoutState = workflowTimeoutState,
        strategyDecision = strategyDecision,
    )
""",
)
replace_once(
    test_path,
    """    private fun testError(): DataLoomError = TestQueueError(
""",
    """    private fun strategyDecision(): PersistedStrategyDecision =
        PersistedStrategyDecision(
            decisionId = StrategyDecisionId("decision-apple-1"),
            planId = StrategyPlanId("plan-apple-1"),
            requestedStrategy = BuiltInSynchronizationStrategy.ADAPTIVE,
            effectiveProfileId = StrategyProfileId("offline-apple-profile"),
            effectiveStrategy = BuiltInSynchronizationStrategy.OFFLINE_FIRST,
            configurationVersion = StrategyConfigurationVersion(14L),
            disposition = StrategyDisposition.DEFER,
        )

    private fun testError(): DataLoomError = TestQueueError(
""",
)

checkpoint_path = (
    "docs/audits/"
    "DL-039B-durable-strategy-decision-persistence-checkpoint.md"
)
replace_once(
    checkpoint_path,
    """Migration
coverage validates 6 to 7 without data invention. iOS Simulator tests cover
version-3 round trip, version-2 backward read, and corrupt partial decision
rejection. Exact JVM and Kotlin/Native ABI declarations, Room schema evidence,
""",
    """Migration
coverage validates 6 to 7 without data invention. iOS Simulator tests cover
version-3 round trip, version-2 backward read, corrupt partial decision
rejection, and production file-provider preservation through reopen, retry,
deferral, and expired-lease recovery. Exact JVM and Kotlin/Native ABI
declarations, Room schema evidence,
""",
)

print("Added Apple provider-level durable strategy decision evidence.")
