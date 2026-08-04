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
            f"Expected one capability-alignment match in {path}, found {count}: "
            f"{old[:180]!r}",
        )
    write(path, content.replace(old, new, 1))


# RECONCILE is implemented by StrategyReconciliationProvider, a narrow storage
# capability. Remote operations independently declare TRANSPORT when present.
plan_path = "dataloom-api/src/commonMain/kotlin/io/dataloom/api/strategy/StrategyExecutionPlan.kt"
replace_once(
    plan_path,
    """            StrategyOperation.RECONCILE -> {
                capabilities += StrategyProviderCapability.STORAGE
                capabilities += StrategyProviderCapability.TRANSPORT
                capabilities += StrategyProviderCapability.CONFLICT_STATE
            }
""",
    """            StrategyOperation.RECONCILE -> {
                capabilities += StrategyProviderCapability.STORAGE
                capabilities += StrategyProviderCapability.CONFLICT_STATE
            }
""",
)

evaluator_path = (
    "dataloom-runtime/src/commonMain/kotlin/io/dataloom/runtime/strategy/"
    "BuiltInSynchronizationStrategyEvaluator.kt"
)
replace_once(
    evaluator_path,
    """                StrategyOperation.RECONCILE -> {
                    capabilities += StrategyProviderCapability.STORAGE
                    capabilities += StrategyProviderCapability.TRANSPORT
                    capabilities += StrategyProviderCapability.CONFLICT_STATE
                }
""",
    """                StrategyOperation.RECONCILE -> {
                    capabilities += StrategyProviderCapability.STORAGE
                    capabilities += StrategyProviderCapability.CONFLICT_STATE
                }
""",
)

coordinator_path = (
    "dataloom-runtime/src/commonMain/kotlin/io/dataloom/runtime/strategy/"
    "AcceptedStrategyPlanExecutionCoordinator.kt"
)
replace_once(
    coordinator_path,
    """                StrategyOperation.RECONCILE -> {
                    capabilities += StrategyProviderCapability.STORAGE
                    capabilities += StrategyProviderCapability.TRANSPORT
                    capabilities += StrategyProviderCapability.CONFLICT_STATE
                }
""",
    """                StrategyOperation.RECONCILE -> {
                    capabilities += StrategyProviderCapability.STORAGE
                    capabilities += StrategyProviderCapability.CONFLICT_STATE
                }
""",
)

runtime_test_path = (
    "dataloom-runtime/src/commonTest/kotlin/io/dataloom/runtime/strategy/"
    "AcceptedStrategyPlanExecutionCoordinatorTest.kt"
)
replace_once(
    runtime_test_path,
    """                requiredCapabilities = setOf(
                    StrategyProviderCapability.STORAGE,
                    StrategyProviderCapability.TRANSPORT,
                    StrategyProviderCapability.CONFLICT_STATE,
                ),
                dataOrigin = StrategyDataOrigin.LOCAL,
""",
    """                requiredCapabilities = setOf(
                    StrategyProviderCapability.STORAGE,
                    StrategyProviderCapability.CONFLICT_STATE,
                ),
                dataOrigin = StrategyDataOrigin.LOCAL,
""",
)
replace_once(
    runtime_test_path,
    """            bindings = bindings(storage, transport),
        )

        val fallback = assertIs<StrategySynchronizationExecutionResult.FallbackActivated>(result)
        assertEquals(StrategyCacheState.STALE, fallback.cacheState)
        assertEquals(1, storage.reconcileCalls)
""",
    """            bindings = StrategyProviderBindings(
                storageProviderId = storage.descriptor.id,
            ),
        )

        val fallback = assertIs<StrategySynchronizationExecutionResult.FallbackActivated>(result)
        assertEquals(StrategyCacheState.STALE, fallback.cacheState)
        assertEquals(1, storage.reconcileCalls)
""",
)

# The API contract test remains a missing-capability test, but now explicitly
# supplies CONFLICT_STATE without STORAGE to prove both are required.
hardening_test_path = (
    "dataloom-api/src/commonTest/kotlin/io/dataloom/api/strategy/"
    "StrategyExecutionPlanHardeningTest.kt"
)
replace_once(
    hardening_test_path,
    """                requiredCapabilities = setOf(StrategyProviderCapability.STORAGE),
                dataOrigin = StrategyDataOrigin.NONE,
""",
    """                requiredCapabilities = setOf(StrategyProviderCapability.CONFLICT_STATE),
                dataOrigin = StrategyDataOrigin.NONE,
""",
)

doc_path = "docs/audits/DL-039B-persisted-accepted-plan-execution-checkpoint.md"
replace_once(
    doc_path,
    """- `RECONCILE` uses the optional narrow `StrategyReconciliationProvider` and has
  independent circuit/timeout protection.
""",
    """- `RECONCILE` uses the optional narrow storage-owned
  `StrategyReconciliationProvider`; it does not require an otherwise unused
  transport binding, and it has independent circuit/timeout protection.
""",
)

print("Aligned reconciliation capability requirements with the implemented provider boundary.")
