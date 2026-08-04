from pathlib import Path

path = Path(__file__).with_name("dl_strategy_005_protected_coordinator.py")
content = path.read_text()
invalid_import_patch = '''replace_once(
    path,
    """import io.dataloom.runtime.retry.StrategyLocalFallbackTimeoutErrors
import io.dataloom.runtime.retry.StorageCircuitOperation
""",
    """import io.dataloom.runtime.retry.StrategyLocalFallbackTimeoutErrors
import io.dataloom.runtime.retry.StrategyReconciliationCircuitOperation
import io.dataloom.runtime.retry.StrategyReconciliationTimeoutErrors
import io.dataloom.runtime.retry.StorageCircuitOperation
""",
)
'''
if content.count(invalid_import_patch) != 1:
    raise SystemExit("Expected one stale protected-coordinator import patch.")
path.write_text(content.replace(invalid_import_patch, "", 1))
print("Removed stale protected-coordinator import patch.")
