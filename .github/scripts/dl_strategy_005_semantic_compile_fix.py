from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
PATH = (
    ROOT
    / "dataloom-runtime/src/commonMain/kotlin/io/dataloom/runtime/strategy/"
    "AcceptedStrategyPlanExecutionCoordinator.kt"
)
content = PATH.read_text()
old = """        if (
            continuation.fallbackPlan != null &&
            StrategyOperation.SERVE_LOCAL !in continuation.fallbackPlan.operations
        ) {
            return StrategyExecutionRejectionReason.UNSUPPORTED_PLAN
        }
        val requiresFallback =
            continuation.fallbackPlan != null ||
                StrategyOperation.SERVE_LOCAL in operations
"""
new = """        val admittedFallbackPlan = continuation.fallbackPlan
        if (
            admittedFallbackPlan != null &&
            StrategyOperation.SERVE_LOCAL !in admittedFallbackPlan.operations
        ) {
            return StrategyExecutionRejectionReason.UNSUPPORTED_PLAN
        }
        val requiresFallback =
            admittedFallbackPlan != null ||
                StrategyOperation.SERVE_LOCAL in operations
"""
if "val admittedFallbackPlan = continuation.fallbackPlan" not in content:
    count = content.count(old)
    if count != 1:
        raise SystemExit(
            f"Expected one Native fallback access match, found {count}",
        )
    PATH.write_text(content.replace(old, new, 1).rstrip() + "\n")
print("Stabilized Native fallback-plan access.")
