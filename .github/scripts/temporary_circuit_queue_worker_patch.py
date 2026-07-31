from pathlib import Path


def replace_once(path: str, old: str, new: str) -> None:
    file = Path(path)
    text = file.read_text()
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"Expected one match in {path}, found {count}")
    file.write_text(text.replace(old, new, 1))


test_path = (
    "dataloom-runtime/src/commonTest/kotlin/io/dataloom/runtime/worker/"
    "CircuitBreakerQueueWorkerCoordinatorTest.kt"
)
replace_once(
    test_path,
    "import io.dataloom.api.identifier.ProviderId\n",
    "import io.dataloom.api.provider.ProviderId\n",
)
replace_once(
    test_path,
    "summary = QueueProcessingSummary(),",
    """summary = QueueProcessingSummary(
                acquired = 0,
                executed = 0,
                completed = 0,
                rescheduled = 0,
                failed = 0,
                cancelled = 0,
            ),""",
)
replace_once(
    test_path,
    "summary = QueueProcessingSummary(acquired = 1, executed = 1, completed = 1),",
    """summary = QueueProcessingSummary(
                    acquired = 1,
                    executed = 1,
                    completed = 1,
                    rescheduled = 0,
                    failed = 0,
                    cancelled = 0,
                ),""",
)

engine_path = (
    "dataloom-runtime/src/commonMain/kotlin/io/dataloom/runtime/queue/"
    "CircuitBreakerQueueProcessingEngine.kt"
)
replace_once(
    engine_path,
    "public fun interface CircuitBreakerQueueProcessingEngine",
    "internal fun interface CircuitBreakerQueueProcessingEngine",
)

coordinator_path = (
    "dataloom-runtime/src/commonMain/kotlin/io/dataloom/runtime/worker/"
    "CircuitBreakerQueueWorkerCoordinator.kt"
)
replace_once(
    coordinator_path,
    "public class CircuitBreakerQueueWorkerCoordinator(\n",
    "public class CircuitBreakerQueueWorkerCoordinator internal constructor(\n",
)

processing_result_path = (
    "dataloom-runtime/src/commonMain/kotlin/io/dataloom/runtime/queue/"
    "CircuitBreakerQueueProcessingResult.kt"
)
replace_once(
    processing_result_path,
    """        /** Defensive immutable snapshot of entries affected by the successful provider call. */
        public val affectedEntryIds: List<QueueEntryId> = affectedEntryIds.toList()

        override fun equals(other: Any?): Boolean {
""",
    """        /** Defensive immutable snapshot of entries affected by the successful provider call. */
        public val affectedEntryIds: List<QueueEntryId> = affectedEntryIds.toList()

        init {
            require(
                recordResult !is CircuitBreakerRecordResult.Recorded &&
                    recordResult !is CircuitBreakerRecordResult.Ignored,
            ) {
                "CircuitRecordingUnconfirmed requires an unaccepted circuit recording result."
            }
        }

        override fun equals(other: Any?): Boolean {
""",
)

recovery_result_path = (
    "dataloom-runtime/src/commonMain/kotlin/io/dataloom/runtime/worker/"
    "CircuitBreakerQueueWorkerRecoveryResult.kt"
)
replace_once(
    recovery_result_path,
    """    public data class CircuitRecordingUnconfirmed(
        public val result: ExpiredLeaseRecoveryResult,
        public val recordResult: CircuitBreakerRecordResult,
    ) : CircuitBreakerQueueWorkerRecoveryResult
""",
    """    public data class CircuitRecordingUnconfirmed(
        public val result: ExpiredLeaseRecoveryResult,
        public val recordResult: CircuitBreakerRecordResult,
    ) : CircuitBreakerQueueWorkerRecoveryResult {
        init {
            require(
                recordResult !is CircuitBreakerRecordResult.Recorded &&
                    recordResult !is CircuitBreakerRecordResult.Ignored,
            ) {
                "CircuitRecordingUnconfirmed recovery requires an unaccepted " +
                    "circuit recording result."
            }
        }
    }
""",
)

readme = "docs/api/README.md"
replace_once(
    readme,
    """| Submit and process durable work | [Queue submission](./queue-submission.md), [circuit-aware queue submission](./circuit-queue-submission.md), [circuit-aware queue processing](./circuit-queue-processing.md), [queue provider](./queue-provider.md), [queue-provider timeouts](./queue-provider-timeouts.md), [queue circuit adapter](./queue-circuit-operation-adapter.md), and [queue worker](./queue-worker-coordinator.md) |
""",
    """| Submit and process durable work | [Queue submission](./queue-submission.md), [circuit-aware queue submission](./circuit-queue-submission.md), [circuit-aware queue processing](./circuit-queue-processing.md), [circuit-aware queue worker](./circuit-queue-worker.md), [queue provider](./queue-provider.md), [queue-provider timeouts](./queue-provider-timeouts.md), [queue circuit adapter](./queue-circuit-operation-adapter.md), and [queue worker](./queue-worker-coordinator.md) |
""",
)
replace_once(
    readme,
    """| [Circuit-aware queue processing](./circuit-queue-processing.md) | Partial V1 subsystem | Explicit acquisition/transition circuits, truthful partial counters, and uncollapsed record evidence. |
| [Queue submission](./queue-submission.md) | Available foundation | Application-owned work encoding and durable enqueue with optional timeout and additive circuit-aware execution. |
""",
    """| [Circuit-aware queue processing](./circuit-queue-processing.md) | Partial V1 subsystem | Explicit acquisition/transition circuits, truthful partial counters, and uncollapsed record evidence. |
| [Circuit-aware queue worker](./circuit-queue-worker.md) | Partial V1 subsystem | Circuit-protected recovery, bounded processing, and scheduler isolation with explicit terminal evidence. |
| [Queue submission](./queue-submission.md) | Available foundation | Application-owned work encoding and durable enqueue with optional timeout and additive circuit-aware execution. |
""",
)
replace_once(
    readme,
    """operation adaptation, submission, and bounded acquisition/transition processing
now exist, while circuit-aware recovery/worker and builder assembly, KMP iOS
persistence, and end-to-end qualification remain open.
""",
    """operation adaptation, submission, bounded acquisition/transitions, and
circuit-aware recovery/worker coordination now exist. Explicit builder adoption,
scheduler-circuit policy, KMP iOS persistence, and end-to-end qualification
remain open.
""",
)
replace_once(
    readme,
    """V1 retry work still requires circuit-aware queue recovery/worker and builder
assembly, complete transport/storage circuit assembly, protocol-specific timeout
""",
    """V1 retry work still requires explicit builder adoption of circuit-aware queue
execution, scheduler-circuit policy, complete transport/storage circuit assembly,
protocol-specific timeout
""",
)

worker_doc = "docs/api/queue-worker-coordinator.md"
replace_once(
    worker_doc,
    """> **Status:** Available recovery, bounded-processing, wake-up, and optional
> scheduler-provider timeout foundation. Complete retry/circuit integration and
> platform qualification remain V1 gates.
""",
    """> **Status:** Available direct recovery, bounded-processing, wake-up, and
> optional scheduler-provider timeout foundation. An additive circuit-aware
> coordinator now preserves recovery and processing evidence; builder adoption,
> scheduler-circuit policy, and platform qualification remain V1 gates.
""",
)
replace_once(
    worker_doc,
    """- `QueueWorkerRunResult` — terminal result of one coordinator run; and
- `QueueWorkerCoordinator` — bounded coordinator.
""",
    """- `QueueWorkerRunResult` — terminal result of one direct coordinator run;
- `QueueWorkerCoordinator` — bounded direct coordinator;
- `CircuitBreakerQueueWorkerRecoveryResult` — enriched recovery evidence;
- `CircuitBreakerQueueWorkerRunResult` — enriched worker terminal result;
- `CircuitBreakerQueueWorkerCoordinator` — circuit-aware bounded coordinator;
  and
- `CircuitBreakerQueueWorkerRuntime` — shared adapter/processor assembly.

The direct coordinator remains source compatible. See
[Circuit-aware queue worker](./circuit-queue-worker.md) for the additive circuit
path.
""",
)

worker_circuit_doc = "docs/api/circuit-queue-worker.md"
replace_once(
    worker_circuit_doc,
    """The queue-processing boundary is represented by
`CircuitBreakerQueueProcessingEngine`; the production runtime assembles it from
`CircuitBreakerDurableQueueExecutionProcessor`.
""",
    """An internal queue-processing seam keeps coordinator tests deterministic without
adding a host-replaceable public execution engine. The public production path
uses `CircuitBreakerDurableQueueExecutionProcessor` through the coordinator's
production constructor and `CircuitBreakerQueueWorkerRuntime`.
""",
)

circuit_doc = "docs/api/circuit-execution-gate.md"
replace_once(
    circuit_doc,
    """`CircuitBreakerDurableQueueExecutionProcessor` applies explicit scopes to atomic
acquisition and every lease-guarded transition. Its terminal results distinguish
pre-execution stop, provider failure, and provider success followed by circuit-
recording failure, so confirmed transitions are counted without replaying them.

## Security boundary
""",
    """`CircuitBreakerDurableQueueExecutionProcessor` applies explicit scopes to atomic
acquisition and every lease-guarded transition. Its terminal results distinguish
pre-execution stop, provider failure, and provider success followed by circuit-
recording failure, so confirmed transitions are counted without replaying them.

`CircuitBreakerQueueWorkerCoordinator` extends the same evidence model to
expired-lease recovery and one bounded worker cycle. Processing begins only
after accepted recovery evidence, and scheduling occurs only after a normal
processing result. Queue circuit scopes are not silently reused as scheduler
circuit policy.

## Security boundary
""",
)
