from pathlib import Path


def replace_once(path: str, old: str, new: str) -> None:
    file = Path(path)
    text = file.read_text()
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"Expected one match in {path}, found {count}")
    file.write_text(text.replace(old, new, 1))


coordinator = (
    "dataloom-runtime/src/commonMain/kotlin/io/dataloom/runtime/worker/"
    "CircuitBreakerQueueWorkerCoordinator.kt"
)
replace_once(
    coordinator,
    """    private val queueProcessor: CircuitBreakerQueueProcessingEngine,
    directSchedulerProvider: SchedulerProvider?,
    private val schedulerCircuitAdapter: CircuitBreakerRetrySchedulingAdapter?,
    private val clock: DataLoomClock,
""",
    """    private val queueProcessor: CircuitBreakerQueueProcessingEngine,
    schedulerProvider: SchedulerProvider?,
    private val schedulerCircuitAdapter: CircuitBreakerRetrySchedulingAdapter? = null,
    private val clock: DataLoomClock,
""",
)
replace_once(
    coordinator,
    """    private val schedulerProvider: SchedulerProvider? =
        if (schedulerCircuitAdapter == null) {
""",
    """    private val directSchedulerProvider: SchedulerProvider? = schedulerProvider

    private val schedulerProvider: SchedulerProvider? =
        if (schedulerCircuitAdapter == null) {
""",
)
replace_once(
    coordinator,
    """        directSchedulerProvider = schedulerProvider,
        schedulerCircuitAdapter = null,
""",
    """        schedulerProvider = schedulerProvider,
        schedulerCircuitAdapter = null,
""",
)

runtime = (
    "dataloom-runtime/src/commonMain/kotlin/io/dataloom/runtime/worker/"
    "CircuitBreakerQueueWorkerRuntime.kt"
)
replace_once(
    runtime,
    """            directSchedulerProvider = null,
            schedulerCircuitAdapter = schedulerAdapter,
""",
    """            schedulerProvider = null,
            schedulerCircuitAdapter = schedulerAdapter,
""",
)

test = (
    "dataloom-runtime/src/commonTest/kotlin/io/dataloom/runtime/worker/"
    "CircuitBreakerQueueWorkerSchedulerCircuitTest.kt"
)
replace_once(
    test,
    """            directSchedulerProvider = null,
            schedulerCircuitAdapter = schedulerAdapter,
""",
    """            schedulerProvider = null,
            schedulerCircuitAdapter = schedulerAdapter,
""",
)
