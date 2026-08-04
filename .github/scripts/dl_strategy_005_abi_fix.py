from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
PATH = (
    ROOT
    / "dataloom-runtime/src/commonMain/kotlin/io/dataloom/runtime/strategy/"
    "AcceptedStrategyPlanExecutionCoordinator.kt"
)
content = PATH.read_text()
old = "public class AcceptedStrategyPlanExecutionCoordinator("
new = "internal class AcceptedStrategyPlanExecutionCoordinator("
if new not in content:
    count = content.count(old)
    if count != 1:
        raise SystemExit(f"Expected one accepted-plan coordinator declaration, found {count}")
    PATH.write_text(content.replace(old, new, 1).rstrip() + "\n")
print("Kept accepted-plan coordinator behind the public DataLoom facade.")
