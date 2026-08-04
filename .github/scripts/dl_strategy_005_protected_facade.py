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
        raise SystemExit(f"Expected one protected facade match in {path}, found {count}: {old[:140]!r}")
    write(path, content.replace(old, new, 1))


interface = (
    "dataloom-runtime/src/commonMain/kotlin/io/dataloom/runtime/facade/"
    "DataLoomProtectedStrategySynchronization.kt"
)
replace_once(
    interface,
    """package io.dataloom.runtime.facade

import io.dataloom.api.provider.StrategyProviderBindings
import io.dataloom.api.strategy.StrategySynchronizationRequest
""",
    """package io.dataloom.runtime.facade

import io.dataloom.api.model.SynchronizationRequest
import io.dataloom.api.provider.StrategyProviderBindings
import io.dataloom.api.strategy.PersistedStrategyDecision
import io.dataloom.api.strategy.StrategyExecutionPlan
import io.dataloom.api.strategy.StrategySynchronizationRequest
""",
)
replace_once(
    interface,
    """    public suspend fun synchronize(
        request: StrategySynchronizationRequest,
        bindings: StrategyProviderBindings,
    ): ProviderProtectedStrategySynchronizationResult
}
""",
    """    public suspend fun synchronize(
        request: StrategySynchronizationRequest,
        bindings: StrategyProviderBindings,
    ): ProviderProtectedStrategySynchronizationResult

    /** Executes a persisted accepted plan with default protected strategy bindings. */
    public suspend fun synchronizeAcceptedPlan(
        request: SynchronizationRequest,
        decision: PersistedStrategyDecision,
        plan: StrategyExecutionPlan,
    ): ProviderProtectedStrategySynchronizationResult

    /** Executes a persisted accepted plan with exact protected strategy bindings. */
    public suspend fun synchronizeAcceptedPlan(
        request: SynchronizationRequest,
        decision: PersistedStrategyDecision,
        plan: StrategyExecutionPlan,
        bindings: StrategyProviderBindings,
    ): ProviderProtectedStrategySynchronizationResult
}
""",
)

default = (
    "dataloom-runtime/src/commonMain/kotlin/io/dataloom/runtime/facade/"
    "DefaultDataLoomProtectedStrategySynchronization.kt"
)
replace_once(
    default,
    """package io.dataloom.runtime.facade

import io.dataloom.api.provider.StrategyProviderBindings
import io.dataloom.api.strategy.StrategySynchronizationRequest
""",
    """package io.dataloom.runtime.facade

import io.dataloom.api.model.SynchronizationRequest
import io.dataloom.api.provider.StrategyProviderBindings
import io.dataloom.api.strategy.PersistedStrategyDecision
import io.dataloom.api.strategy.StrategyExecutionPlan
import io.dataloom.api.strategy.StrategySynchronizationRequest
""",
)
replace_once(
    default,
    """    override suspend fun synchronize(
        request: StrategySynchronizationRequest,
        bindings: StrategyProviderBindings,
    ): ProviderProtectedStrategySynchronizationResult =
        coordinator.execute(request, bindings)
}
""",
    """    override suspend fun synchronize(
        request: StrategySynchronizationRequest,
        bindings: StrategyProviderBindings,
    ): ProviderProtectedStrategySynchronizationResult =
        coordinator.execute(request, bindings)

    override suspend fun synchronizeAcceptedPlan(
        request: SynchronizationRequest,
        decision: PersistedStrategyDecision,
        plan: StrategyExecutionPlan,
    ): ProviderProtectedStrategySynchronizationResult =
        coordinator.executeAcceptedPlan(request, decision, plan, defaultBindings)

    override suspend fun synchronizeAcceptedPlan(
        request: SynchronizationRequest,
        decision: PersistedStrategyDecision,
        plan: StrategyExecutionPlan,
        bindings: StrategyProviderBindings,
    ): ProviderProtectedStrategySynchronizationResult =
        coordinator.executeAcceptedPlan(request, decision, plan, bindings)
}
""",
)

builder = "dataloom-runtime/src/commonMain/kotlin/io/dataloom/runtime/facade/DataLoomBuilder.kt"
replace_once(
    builder,
    """            val coordinator = ProviderProtectedStrategySynchronizationCoordinator(
                strategyCoordinator = strategyExecutionCoordinator,
                protectionSpec = spec,
                clock = deps.clock,
            )
""",
    """            val coordinator = ProviderProtectedStrategySynchronizationCoordinator(
                strategyCoordinator = strategyExecutionCoordinator,
                acceptedPlanCoordinator = acceptedStrategyPlanCoordinator,
                protectionSpec = spec,
                clock = deps.clock,
            )
""",
)

print("Assembled protected accepted-plan facade methods.")
