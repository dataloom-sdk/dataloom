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
            f"Expected one compile-fix match in {path}, found {count}: {old[:160]!r}",
        )
    write(path, content.replace(old, new, 1))


coordinator = (
    "dataloom-runtime/src/commonMain/kotlin/io/dataloom/runtime/strategy/"
    "AcceptedStrategyPlanExecutionCoordinator.kt"
)
replace_once_unless_present(
    coordinator,
    """    public suspend fun execute(
        request: SynchronizationRequest,
        decision: PersistedStrategyDecision,
        acceptedPlan: StrategyExecutionPlan,
        bindings: StrategyProviderBindings,
        providerBoundary: StrategyProviderExecutionBoundary =
            IdentityStrategyProviderExecutionBoundary,
    ): StrategySynchronizationExecutionResult {
        val evaluation = replayEvaluation(decision, acceptedPlan)
""",
    """    public suspend fun execute(
        request: SynchronizationRequest,
        decision: PersistedStrategyDecision,
        acceptedPlan: StrategyExecutionPlan,
        bindings: StrategyProviderBindings,
    ): StrategySynchronizationExecutionResult = execute(
        request = request,
        decision = decision,
        acceptedPlan = acceptedPlan,
        bindings = bindings,
        providerBoundary = StrategyProviderExecutionBoundary.Identity,
    )

    internal suspend fun execute(
        request: SynchronizationRequest,
        decision: PersistedStrategyDecision,
        acceptedPlan: StrategyExecutionPlan,
        bindings: StrategyProviderBindings,
        providerBoundary: StrategyProviderExecutionBoundary,
    ): StrategySynchronizationExecutionResult {
        val evaluation = replayEvaluation(decision, acceptedPlan)
""",
    "internal suspend fun execute(\n        request: SynchronizationRequest,\n        decision: PersistedStrategyDecision,\n",
)

queued_test = (
    "dataloom-runtime/src/commonTest/kotlin/io/dataloom/runtime/queue/"
    "AcceptedStrategyQueuedExecutionRoutingTest.kt"
)
replace_once_unless_present(
    queued_test,
    """    private fun <T> fixed(value: T): IdentifierGenerator<T> = IdentifierGenerator { value }
""",
    """    private fun <T> fixed(value: T): IdentifierGenerator<T> =
        object : IdentifierGenerator<T> {
            override fun generate(): T = value
        }
""",
    "private fun <T> fixed(value: T): IdentifierGenerator<T> =\n        object : IdentifierGenerator<T>",
)

coordinator_test = (
    "dataloom-runtime/src/commonTest/kotlin/io/dataloom/runtime/strategy/"
    "AcceptedStrategyPlanExecutionCoordinatorTest.kt"
)
replace_once_unless_present(
    coordinator_test,
    """    private fun <T> fixedGenerator(value: T): IdentifierGenerator<T> =
        IdentifierGenerator { value }
""",
    """    private fun <T> fixedGenerator(value: T): IdentifierGenerator<T> =
        object : IdentifierGenerator<T> {
            override fun generate(): T = value
        }
""",
    "private fun <T> fixedGenerator(value: T): IdentifierGenerator<T> =\n        object : IdentifierGenerator<T>",
)

print("Corrected accepted-plan coordinator and Native identifier test fixtures.")
