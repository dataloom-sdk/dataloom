#!/usr/bin/env python3
"""Rebase the staged durable-refresh candidate onto the current main sources."""

from __future__ import annotations

import base64
import gzip
import hashlib
from pathlib import Path

EXECUTOR_GZIP_BASE64 = """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"""
EXECUTOR_SHA256 = "ffafe338dfd0809dc9309707c2439d624893a61a07148aef2ee5d73c31d6c0e0"


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected one replacement, found {count}")
    return text.replace(old, new)


def write_executor() -> None:
    content = gzip.decompress(base64.b64decode(EXECUTOR_GZIP_BASE64))
    actual = hashlib.sha256(content).hexdigest()
    if actual != EXECUTOR_SHA256:
        raise SystemExit(
            f"Executor checksum mismatch: expected {EXECUTOR_SHA256}, got {actual}"
        )
    path = Path(
        "dataloom-runtime/src/commonMain/kotlin/io/dataloom/runtime/strategy/"
        "CacheFirstStrategyExecutor.kt"
    )
    path.write_bytes(content)


def make_integration_test_dependency_neutral() -> None:
    path = Path(
        "dataloom-testing/src/commonTest/kotlin/io/dataloom/testing/strategy/"
        "CacheFirstDurableRefreshAdmissionIntegrationTest.kt"
    )
    text = path.read_text()
    text = replace_once(
        text,
        "import kotlinx.coroutines.test.runTest\n",
        "import kotlin.coroutines.Continuation\n"
        "import kotlin.coroutines.EmptyCoroutineContext\n"
        "import kotlin.coroutines.startCoroutine\n",
        "integration test coroutine imports",
    )
    count = text.count("= runTest {")
    if count == 0:
        raise SystemExit("Expected durable-refresh runTest usages")
    text = text.replace("= runTest {", "= runSuspend {")

    marker = "    private companion object {\n"
    helper = (
        "    /** Runs the deterministic, immediately completing suspend test body. */\n"
        "    private fun <T> runSuspend(block: suspend () -> T): T {\n"
        "        var outcome: Result<T>? = null\n"
        "        block.startCoroutine(\n"
        "            object : Continuation<T> {\n"
        "                override val context = EmptyCoroutineContext\n"
        "\n"
        "                override fun resumeWith(result: Result<T>) {\n"
        "                    outcome = result\n"
        "                }\n"
        "            },\n"
        "        )\n"
        "        return requireNotNull(outcome) {\n"
        "            \"Durable refresh test operation did not complete synchronously.\"\n"
        "        }.getOrThrow()\n"
        "    }\n"
        "\n"
    )
    text = replace_once(
        text,
        marker,
        helper + marker,
        "integration test continuation helper",
    )
    path.write_text(text)


def main() -> None:
    write_executor()
    make_integration_test_dependency_neutral()


if __name__ == "__main__":
    main()
