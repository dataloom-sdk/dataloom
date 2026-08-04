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
            f"Expected one test-expectation match in {path}, found {count}: "
            f"{old[:180]!r}",
        )
    write(path, content.replace(old, new, 1))


correspondence = (
    "dataloom-runtime/src/commonTest/kotlin/io/dataloom/runtime/queue/"
    "QueuedStrategyPlanCorrespondenceTest.kt"
)
replace_once(
    correspondence,
    """                plan(continuationOperation = StrategyOperation.PUSH_REMOTE),
                plan(continuationOperation = StrategyOperation.RECONCILE),
""",
    """                plan(
                    continuationConsistency = StrategyConsistency.LOCAL_AUTHORITATIVE,
                ),
                plan(continuationConsistency = StrategyConsistency.EVENTUAL),
""",
)
replace_once(
    correspondence,
    """    private fun plan(
        planId: String = "plan-1",
        continuationOperation: StrategyOperation = StrategyOperation.PUSH_REMOTE,
    ): StrategyExecutionPlan = StrategyExecutionPlan(
""",
    """    private fun plan(
        planId: String = "plan-1",
        continuationConsistency: StrategyConsistency =
            StrategyConsistency.LOCAL_AUTHORITATIVE,
    ): StrategyExecutionPlan = StrategyExecutionPlan(
""",
)
replace_once(
    correspondence,
    """        durableContinuation = StrategyDurableContinuationPlan(
            operations = if (continuationOperation == StrategyOperation.RECONCILE) {
                listOf(StrategyOperation.PUSH_REMOTE, StrategyOperation.RECONCILE)
            } else {
                listOf(StrategyOperation.PUSH_REMOTE)
            },
            requiredCapabilities = setOf(StrategyProviderCapability.TRANSPORT),
            dataOrigin = StrategyDataOrigin.NONE,
            consistency = StrategyConsistency.LOCAL_AUTHORITATIVE,
        ),
""",
    """        durableContinuation = StrategyDurableContinuationPlan(
            operations = listOf(StrategyOperation.PUSH_REMOTE),
            requiredCapabilities = setOf(StrategyProviderCapability.TRANSPORT),
            dataOrigin = StrategyDataOrigin.NONE,
            consistency = continuationConsistency,
        ),
""",
)

preflight = (
    "dataloom-runtime/src/commonTest/kotlin/io/dataloom/runtime/submission/"
    "QueueSubmissionStrategyDecisionPreflightTest.kt"
)
replace_once(
    preflight,
    """        val changed = plan(continuationOperation = StrategyOperation.RECONCILE)
""",
    """        val changed = plan(continuationConsistency = StrategyConsistency.EVENTUAL)
""",
)
replace_once(
    preflight,
    """    private fun plan(
        continuationOperation: StrategyOperation = StrategyOperation.PUSH_REMOTE,
    ): StrategyExecutionPlan = StrategyExecutionPlan(
""",
    """    private fun plan(
        continuationConsistency: StrategyConsistency =
            StrategyConsistency.LOCAL_AUTHORITATIVE,
    ): StrategyExecutionPlan = StrategyExecutionPlan(
""",
)
replace_once(
    preflight,
    """        durableContinuation = StrategyDurableContinuationPlan(
            operations = if (continuationOperation == StrategyOperation.RECONCILE) {
                listOf(StrategyOperation.PUSH_REMOTE, StrategyOperation.RECONCILE)
            } else {
                listOf(StrategyOperation.PUSH_REMOTE)
            },
            requiredCapabilities = setOf(StrategyProviderCapability.TRANSPORT),
            dataOrigin = StrategyDataOrigin.NONE,
            consistency = StrategyConsistency.LOCAL_AUTHORITATIVE,
        ),
""",
    """        durableContinuation = StrategyDurableContinuationPlan(
            operations = listOf(StrategyOperation.PUSH_REMOTE),
            requiredCapabilities = setOf(StrategyProviderCapability.TRANSPORT),
            dataOrigin = StrategyDataOrigin.NONE,
            consistency = continuationConsistency,
        ),
""",
)

print("Updated plan mismatch tests to use distinct valid immutable plans.")
