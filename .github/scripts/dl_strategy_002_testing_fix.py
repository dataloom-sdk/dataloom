from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
TARGET = (
    ROOT
    / "dataloom-testing/src/commonTest/kotlin/io/dataloom/testing/queue/"
    / "StrategyDecisionInMemoryQueueProviderTest.kt"
)

content = TARGET.read_text()

old_import = "import kotlinx.coroutines.test.runTest\n"
new_import = """import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
"""
if content.count(old_import) != 1:
    raise SystemExit("Expected one kotlinx-coroutines-test import in generated test.")
content = content.replace(old_import, new_import, 1)

old_start = (
    "    fun decisionSurvivesRetryDeferralAndExpiredLeaseRecovery() = runTest {\n"
)
new_start = """    fun decisionSurvivesRetryDeferralAndExpiredLeaseRecovery() {
        runSynchronously {
"""
if content.count(old_start) != 1:
    raise SystemExit("Expected one runTest-based in-memory test body.")
content = content.replace(old_start, new_start, 1)

old_end = """        assertEquals(expected, acquire(provider, 6_000L, "lease-4").strategyDecision)
    }

    private suspend fun acquire(
"""
new_end = """            assertEquals(
                expected,
                acquire(provider, 6_000L, "lease-4").strategyDecision,
            )
        }
    }

    /**
     * Executes the synchronous in-memory provider's suspend API without adding
     * a coroutine-test dependency to the public testing-kit module.
     */
    private fun <T> runSynchronously(block: suspend () -> T): T {
        var result: Result<T>? = null
        block.startCoroutine(
            object : Continuation<T> {
                override val context: CoroutineContext = EmptyCoroutineContext

                override fun resumeWith(resumeResult: Result<T>) {
                    result = resumeResult
                }
            },
        )
        return checkNotNull(result) {
            "InMemoryQueueProvider unexpectedly suspended."
        }.getOrThrow()
    }

    private suspend fun acquire(
"""
if content.count(old_end) != 1:
    raise SystemExit("Expected one generated in-memory test ending.")
content = content.replace(old_end, new_end, 1)

TARGET.write_text(content.rstrip() + "\n")
print("Replaced runTest with a dependency-free synchronous continuation runner.")
