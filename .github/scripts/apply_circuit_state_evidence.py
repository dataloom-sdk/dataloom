from pathlib import Path


def replace_once(path: str, old: str, new: str) -> None:
    target = Path(path)
    text = target.read_text(encoding="utf-8")
    if text.count(old) != 1:
        raise RuntimeError(f"Expected one match in {path}: {old[:80]!r}")
    target.write_text(text.replace(old, new, 1), encoding="utf-8")


coordinator = (
    "dataloom-runtime/src/commonMain/kotlin/io/dataloom/runtime/retry/"
    "CircuitBreakerCoordinator.kt"
)
replace_once(
    coordinator,
    """        if (current != null && observedAt.epochMilliseconds < current.updatedAt.epochMilliseconds) {
            return OutcomeTransition.ClockRegression(current.updatedAt)
        }

        return when (outcome) {""",
    """        if (current != null && observedAt.epochMilliseconds < current.updatedAt.epochMilliseconds) {
            return OutcomeTransition.ClockRegression(current.updatedAt)
        }
        if (probePermit != null && current?.phase != CircuitBreakerPhase.HALF_OPEN) {
            return OutcomeTransition.StaleProbe
        }

        return when (outcome) {""",
)

consumer = Path(
    "runtime-external-consumer/src/commonMain/kotlin/io/dataloom/consumer/"
    "RuntimeExternalConsumerProbe.kt"
)
consumer_text = consumer.read_text(encoding="utf-8")
import_anchor = "import io.dataloom.api.error.RetryDelayHint\n"
circuit_imports = (
    "import io.dataloom.api.circuit.CircuitBreakerScope\n"
    "import io.dataloom.api.circuit.CircuitBreakerStateStore\n"
)
if "import io.dataloom.api.circuit.CircuitBreakerScope" not in consumer_text:
    consumer_text = consumer_text.replace(
        import_anchor,
        circuit_imports + import_anchor,
        1,
    )
if "import io.dataloom.runtime.retry.CircuitBreakerConfiguration" not in consumer_text:
    consumer_text = consumer_text.replace(
        "import io.dataloom.runtime.retry.RetryBackoffStrategy\n",
        """import io.dataloom.runtime.retry.CircuitBreakerConfiguration
import io.dataloom.runtime.retry.CircuitBreakerCoordinator
import io.dataloom.runtime.retry.CircuitBreakerPermission
import io.dataloom.runtime.retry.CircuitBreakerRecordResult
import io.dataloom.runtime.retry.RetryBackoffStrategy
""",
        1,
    )
if "internal fun compileCircuitBreakerConsumer(" not in consumer_text:
    consumer_text += """

/** Compile-only use of the circuit-breaker persistence and runtime surface. */
internal fun compileCircuitBreakerConsumer(
    store: CircuitBreakerStateStore,
    clock: DataLoomClock,
): CircuitBreakerCoordinator {
    val scope = CircuitBreakerScope.global()
    val configuration = CircuitBreakerConfiguration(
        failureThreshold = 3,
        failureWindow = SchedulingDelay(30_000L),
        openDuration = SchedulingDelay(60_000L),
    )
    val coordinator = CircuitBreakerCoordinator(configuration, clock, store)
    val permission: CircuitBreakerPermission = CircuitBreakerPermission.Allowed
    val recordResult: CircuitBreakerRecordResult = CircuitBreakerRecordResult.Ignored
    scope.kind
    permission.toString()
    recordResult.toString()
    return coordinator
}
"""
consumer.write_text(consumer_text, encoding="utf-8")

replace_once(
    "README.md",
    "| Retry and circuit breaking | Fail-closed classification, deterministic backoff/full/equal jitter, seeded randomness, attempt plus durable elapsed/cumulative budgets, bounded provider/server hints, queue/scheduler orchestration, and restart-safe history implemented; broader engine partial | Timeout separation, durable circuit state, operations, and full qualification |",
    "| Retry and circuit breaking | Fail-closed classification, deterministic backoff/jitter, durable budgets, bounded hints, independent timeout contracts, and a deterministic circuit state machine with atomic persistence SPI; broader engine partial | Production durable circuit stores, retry-path integration, operations, observability, and full qualification |",
)
replace_once(
    "docs/api/README.md",
    "| [Retry orchestration](./retry-orchestration.md) | Partial V1 subsystem | Protected-failure handling, bounded hint minimums, final-delay aggregation, central budgets, scheduling, and queue integration boundaries. |",
    """| [Retry orchestration](./retry-orchestration.md) | Partial V1 subsystem | Protected-failure handling, bounded hint minimums, final-delay aggregation, central budgets, scheduling, and queue integration boundaries. |
| [Circuit breaker](./circuit-breaker.md) | Partial V1 subsystem | Explicit scopes, durable state contracts, atomic compare-and-set persistence, deterministic transitions, and one controlled half-open probe. |""",
)
replace_once(
    "docs/api/README.md",
    """V1 retry work still requires timeout separation, durable circuit-breaker and
half-open recovery, manual retry/reclassification, complete
observability, and platform qualification.""",
    """V1 retry work still requires production Android/iOS circuit stores,
retry-path circuit integration, manual retry/reclassification, complete
observability, and platform qualification.""",
)
replace_once(
    "docs/architecture/retry-boundaries.md",
    """> limits, central durable elapsed/cumulative budgets, and bounded typed hints.
> Circuit breaking, timeout separation, observability, and administration remain
> incomplete, so this is not yet the""",
    """> limits, central durable elapsed/cumulative budgets, bounded typed hints,
> independent timeout contracts, and a deterministic circuit state machine.
> Production circuit stores, retry-path integration, observability, and administration
> remain incomplete, so this is not yet the""",
)
