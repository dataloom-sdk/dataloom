#!/usr/bin/env python3
"""Reconcile the pre-durable-refresh cache strategy regression expectation."""

from pathlib import Path

PATH = Path(
    "dataloom-runtime/src/commonTest/kotlin/io/dataloom/runtime/strategy/"
    "CacheFirstInlineRefreshExecutionTest.kt"
)


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected one replacement, found {count}")
    return text.replace(old, new)


def main() -> None:
    text = PATH.read_text()
    text = replace_once(
        text,
        "fun adaptiveWorksWhileDurableAndBidirectionalRemainRejected()",
        "fun adaptiveWorksWhileDurableRequiresExplicitIdentitiesAndBidirectionalRemainsRejected()",
        "test name",
    )
    text = replace_once(
        text,
        """        assertEquals(
            StrategyExecutionRejectionReason.UNSUPPORTED_PLAN,
            assertIs<StrategySynchronizationExecutionResult.Rejected>(durableResult).reason,
        )
""",
        """        assertEquals(
            StrategyExecutionRejectionReason.INCOMPATIBLE_INPUT,
            assertIs<StrategySynchronizationExecutionResult.Rejected>(durableResult).reason,
        )
""",
        "durable generic-input rejection",
    )
    PATH.write_text(text)


if __name__ == "__main__":
    main()
