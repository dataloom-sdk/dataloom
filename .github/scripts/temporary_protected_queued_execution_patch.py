from pathlib import Path


def replace_once(path: str, old: str, new: str) -> None:
    file_path = Path(path)
    text = file_path.read_text()
    if old not in text:
        raise SystemExit(f"Patch anchor not found in {path}: {old!r}")
    if text.count(old) != 1:
        raise SystemExit(f"Expected one patch anchor in {path}, found {text.count(old)}")
    file_path.write_text(text.replace(old, new, 1))


replace_once(
    "dataloom-runtime/src/commonTest/kotlin/io/dataloom/runtime/queue/ProviderProtectedQueuedSynchronizationExecutionHandlerTest.kt",
    "import io.dataloom.runtime.retry.ProviderProtectionErrorsForTest\n",
    "",
)
