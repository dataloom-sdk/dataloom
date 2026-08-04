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
        raise SystemExit(f"Expected one accepted-plan facade match in {path}, found {count}: {old[:120]!r}")
    write(path, content.replace(old, new, 1))


data_loom = "dataloom-runtime/src/commonMain/kotlin/io/dataloom/runtime/facade/DataLoom.kt"
replace_once(
    data_loom,
    "import io.dataloom.api.strategy.StrategySynchronizationRequest\n",
    """import io.dataloom.api.strategy.PersistedStrategyDecision
import io.dataloom.api.strategy.StrategyExecutionPlan
import io.dataloom.api.strategy.StrategySynchronizationRequest
""",
)
replace_once(
    data_loom,
    """    public suspend fun synchronize(
        request: StrategySynchronizationRequest,
        bindings: StrategyProviderBindings,
    ): StrategySynchronizationExecutionResult

    /**
     * Returns a diagnostic snapshot of the current facade state.
""",
    """    public suspend fun synchronize(
        request: StrategySynchronizationRequest,
        bindings: StrategyProviderBindings,
    ): StrategySynchronizationExecutionResult

    /**
     * Executes the immutable accepted strategy plan using default strategy
     * provider bindings. Current profiles and runtime evidence are not read.
     */
    public suspend fun synchronizeAcceptedPlan(
        request: SynchronizationRequest,
        decision: PersistedStrategyDecision,
        plan: StrategyExecutionPlan,
    ): StrategySynchronizationExecutionResult

    /**
     * Executes the immutable accepted strategy plan with exact caller-supplied
     * strategy bindings. No strategy policy evaluation occurs.
     */
    public suspend fun synchronizeAcceptedPlan(
        request: SynchronizationRequest,
        decision: PersistedStrategyDecision,
        plan: StrategyExecutionPlan,
        bindings: StrategyProviderBindings,
    ): StrategySynchronizationExecutionResult

    /**
     * Returns a diagnostic snapshot of the current facade state.
""",
)

default_loom = "dataloom-runtime/src/commonMain/kotlin/io/dataloom/runtime/facade/DefaultDataLoom.kt"
replace_once(
    default_loom,
    "import io.dataloom.api.strategy.StrategySynchronizationRequest\n",
    """import io.dataloom.api.strategy.PersistedStrategyDecision
import io.dataloom.api.strategy.StrategyExecutionPlan
import io.dataloom.api.strategy.StrategySynchronizationRequest
""",
)
replace_once(
    default_loom,
    "import io.dataloom.runtime.strategy.StrategySynchronizationExecutionCoordinator\n",
    """import io.dataloom.runtime.strategy.AcceptedStrategyPlanExecutionCoordinator
import io.dataloom.runtime.strategy.StrategySynchronizationExecutionCoordinator
""",
)
replace_once(
    default_loom,
    """    private val strategyExecutionCoordinator: StrategySynchronizationExecutionCoordinator,
    private val defaultBindings: SynchronizationProviderBindings?,
""",
    """    private val strategyExecutionCoordinator: StrategySynchronizationExecutionCoordinator,
    private val acceptedStrategyPlanCoordinator: AcceptedStrategyPlanExecutionCoordinator,
    private val defaultBindings: SynchronizationProviderBindings?,
""",
)
replace_once(
    default_loom,
    """    override suspend fun synchronize(
        request: StrategySynchronizationRequest,
        bindings: StrategyProviderBindings,
    ): StrategySynchronizationExecutionResult =
        strategyExecutionCoordinator.execute(request, bindings)

    override fun health(): DataLoomHealth = DataLoomHealth(
""",
    """    override suspend fun synchronize(
        request: StrategySynchronizationRequest,
        bindings: StrategyProviderBindings,
    ): StrategySynchronizationExecutionResult =
        strategyExecutionCoordinator.execute(request, bindings)

    override suspend fun synchronizeAcceptedPlan(
        request: SynchronizationRequest,
        decision: PersistedStrategyDecision,
        plan: StrategyExecutionPlan,
    ): StrategySynchronizationExecutionResult =
        acceptedStrategyPlanCoordinator.execute(
            request = request,
            decision = decision,
            acceptedPlan = plan,
            bindings = defaultStrategyBindings,
        )

    override suspend fun synchronizeAcceptedPlan(
        request: SynchronizationRequest,
        decision: PersistedStrategyDecision,
        plan: StrategyExecutionPlan,
        bindings: StrategyProviderBindings,
    ): StrategySynchronizationExecutionResult =
        acceptedStrategyPlanCoordinator.execute(
            request = request,
            decision = decision,
            acceptedPlan = plan,
            bindings = bindings,
        )

    override fun health(): DataLoomHealth = DataLoomHealth(
""",
)

builder = "dataloom-runtime/src/commonMain/kotlin/io/dataloom/runtime/facade/DataLoomBuilder.kt"
replace_once(
    builder,
    "import io.dataloom.runtime.strategy.BuiltInSynchronizationStrategyEvaluator\n",
    """import io.dataloom.runtime.strategy.AcceptedStrategyPlanExecutionCoordinator
import io.dataloom.runtime.strategy.BuiltInSynchronizationStrategyEvaluator
""",
)
replace_once(
    builder,
    """        val strategyExecutionCoordinator = StrategySynchronizationExecutionCoordinator(
            lifecycleCoordinator = lifecycleCoordinator,
            evaluator = BuiltInSynchronizationStrategyEvaluator(),
            providerResolver = strategyResolver,
            clock = deps.clock,
            runtimeDependencies = deps,
            pipelineRegistry = buildStrategyPipelineRegistry(),
            lifecycleEventEmitter = lifecycleEventEmitter,
        )
""",
    """        val strategyPipelineRegistry = buildStrategyPipelineRegistry()
        val strategyExecutionCoordinator = StrategySynchronizationExecutionCoordinator(
            lifecycleCoordinator = lifecycleCoordinator,
            evaluator = BuiltInSynchronizationStrategyEvaluator(),
            providerResolver = strategyResolver,
            clock = deps.clock,
            runtimeDependencies = deps,
            pipelineRegistry = strategyPipelineRegistry,
            lifecycleEventEmitter = lifecycleEventEmitter,
        )
        val acceptedStrategyPlanCoordinator = AcceptedStrategyPlanExecutionCoordinator(
            lifecycleCoordinator = lifecycleCoordinator,
            providerResolver = strategyResolver,
            clock = deps.clock,
            runtimeDependencies = deps,
            pipelineRegistry = strategyPipelineRegistry,
            lifecycleEventEmitter = lifecycleEventEmitter,
        )
""",
)
replace_once(
    builder,
    """            strategyExecutionCoordinator = strategyExecutionCoordinator,
            defaultBindings = bindings,
""",
    """            strategyExecutionCoordinator = strategyExecutionCoordinator,
            acceptedStrategyPlanCoordinator = acceptedStrategyPlanCoordinator,
            defaultBindings = bindings,
""",
)

print("Assembled direct accepted-plan facade execution.")
