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
            f"Expected one reconciliation-evidence match in {path}, found {count}: "
            f"{old[:180]!r}",
        )
    write(path, content.replace(old, new, 1))


coordinator_path = (
    "dataloom-runtime/src/commonMain/kotlin/io/dataloom/runtime/strategy/"
    "AcceptedStrategyPlanExecutionCoordinator.kt"
)
replace_once(
    coordinator_path,
    """        is StrategySynchronizationExecutionResult.Executed -> {
            val providerBacked = result.output as? StrategyTransportOutput.ProviderBacked
            if (providerBacked?.result is SynchronizationResult.PartiallySucceeded) {
                observedOperations
            } else {
                continuation.operations.filterNot { it == StrategyOperation.RECONCILE }
            }
        }
""",
    """        is StrategySynchronizationExecutionResult.Executed -> {
            val providerBacked = result.output as? StrategyTransportOutput.ProviderBacked
            when (providerBacked?.result) {
                is SynchronizationResult.PartiallySucceeded,
                is SynchronizationResult.Skipped,
                -> observedOperations
                else -> continuation.operations.filterNot {
                    it == StrategyOperation.RECONCILE
                }
            }
        }
""",
)
replace_once(
    coordinator_path,
    """        val evidence = completedOperations
            .filterNot { it == StrategyOperation.RECONCILE }
            .ifEmpty {
                continuation.operations.filterNot { it == StrategyOperation.RECONCILE }
            }
        return when (
""",
    """        val evidence = completedOperations
            .filterNot { it == StrategyOperation.RECONCILE }
        if (evidence.isEmpty()) return result
        return when (
""",
)

runtime_test_path = (
    "dataloom-runtime/src/commonTest/kotlin/io/dataloom/runtime/strategy/"
    "AcceptedStrategyPlanExecutionCoordinatorTest.kt"
)
replace_once(
    runtime_test_path,
    """import io.dataloom.api.synchronization.SynchronizationResult
import io.dataloom.api.synchronization.SynchronizationSummary
""",
    """import io.dataloom.api.synchronization.SynchronizationResult
import io.dataloom.api.synchronization.SynchronizationSkipReason
import io.dataloom.api.synchronization.SynchronizationSummary
""",
)
content = read(runtime_test_path)
insert_at = content.index("    private suspend fun fixture(")
new_test = """    @Test
    fun skippedReplayWithoutProviderEffectDoesNotReconcile() = runTest {
        val storage = RecordingStrategyStorage()
        val transport = RecordingTransport()
        val fixture = fixture(storage, transport)
        fixture.pushPipeline.skipWithoutProviderCalls = true

        val result = fixture.coordinator.execute(
            request = request(SynchronizationDirection.PUSH),
            decision = decision(),
            acceptedPlan = offlinePlan(),
            bindings = bindings(storage, transport),
        )

        val executed = assertIs<StrategySynchronizationExecutionResult.Executed>(result)
        val output = assertIs<StrategyTransportOutput.ProviderBacked>(executed.output)
        assertIs<SynchronizationResult.Skipped>(output.result)
        assertEquals(0, storage.reconcileCalls)
        assertEquals(0, transport.pushCalls)
    }

"""
content = content[:insert_at] + new_test + content[insert_at:]
write(runtime_test_path, content)
replace_once(
    runtime_test_path,
    """    private class RecordingPipeline(
        override val direction: SynchronizationDirection,
    ) : SynchronizationPipeline {
        var calls: Int = 0
        override suspend fun execute(
            context: SynchronizationExecutionContext,
        ): SynchronizationResult {
            calls++
            return SynchronizationResult.Succeeded(
                request = context.request,
                completedAt = DataLoomInstant(8_000L),
                summary = SynchronizationSummary(),
            )
        }
    }
""",
    """    private class RecordingPipeline(
        override val direction: SynchronizationDirection,
    ) : SynchronizationPipeline {
        var calls: Int = 0
        var skipWithoutProviderCalls: Boolean = false

        override suspend fun execute(
            context: SynchronizationExecutionContext,
        ): SynchronizationResult {
            calls++
            return if (skipWithoutProviderCalls) {
                SynchronizationResult.Skipped(
                    request = context.request,
                    completedAt = DataLoomInstant(8_000L),
                    summary = SynchronizationSummary(),
                    reason = SynchronizationSkipReason.NO_CHANGES,
                )
            } else {
                SynchronizationResult.Succeeded(
                    request = context.request,
                    completedAt = DataLoomInstant(8_000L),
                    summary = SynchronizationSummary(),
                )
            }
        }
    }
""",
)

doc_path = "docs/audits/DL-039B-persisted-accepted-plan-execution-checkpoint.md"
replace_once(
    doc_path,
    """- A retry evaluator inconsistency for known failed work is terminal and can never become queue completion.
""",
    """- A retry evaluator inconsistency for known failed work is terminal and can never become queue completion.
- A pipeline that skips before provider effects contributes no fabricated
  completed-operation evidence and does not trigger reconciliation.
""",
)

print("Prevented skipped accepted-plan replay from fabricating reconciliation evidence.")
