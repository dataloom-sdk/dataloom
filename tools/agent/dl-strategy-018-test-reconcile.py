from pathlib import Path

path = Path(
    "dataloom-runtime/src/commonTest/kotlin/io/dataloom/runtime/strategy/"
    "CacheFirstInlineRefreshExecutionTest.kt"
)
text = path.read_text()

old_name = "fun adaptiveWorksWhileDurableAndBidirectionalRemainRejected() = runTest {"
new_name = "fun adaptiveWorksWhileDurableInputAndBidirectionalValidationRemainExplicit() = runTest {"
if text.count(old_name) != 1:
    raise RuntimeError("Expected one stale inline-refresh test name")
text = text.replace(old_name, new_name, 1)

old_assertion = """        assertEquals(
            StrategyExecutionRejectionReason.UNSUPPORTED_PLAN,
            assertIs<StrategySynchronizationExecutionResult.Rejected>(durableResult).reason,
        )
"""
new_assertion = """        assertEquals(
            StrategyExecutionRejectionReason.INCOMPATIBLE_INPUT,
            assertIs<StrategySynchronizationExecutionResult.Rejected>(durableResult).reason,
        )
"""
if text.count(old_assertion) != 1:
    raise RuntimeError("Expected one obsolete durable-refresh rejection assertion")
text = text.replace(old_assertion, new_assertion, 1)
path.write_text(text)
