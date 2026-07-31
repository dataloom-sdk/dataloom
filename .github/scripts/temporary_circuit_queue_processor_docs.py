from pathlib import Path


def replace_once(path: str, old: str, new: str) -> None:
    file = Path(path)
    text = file.read_text()
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"Expected one match in {path}, found {count}")
    file.write_text(text.replace(old, new, 1))


readme = "docs/api/README.md"
replace_once(
    readme,
    """| Submit and process durable work | [Queue submission](./queue-submission.md), [circuit-aware queue submission](./circuit-queue-submission.md), [queue provider](./queue-provider.md), [queue-provider timeouts](./queue-provider-timeouts.md), [queue circuit adapter](./queue-circuit-operation-adapter.md), and [queue worker](./queue-worker-coordinator.md) |
""",
    """| Submit and process durable work | [Queue submission](./queue-submission.md), [circuit-aware queue submission](./circuit-queue-submission.md), [circuit-aware queue processing](./circuit-queue-processing.md), [queue provider](./queue-provider.md), [queue-provider timeouts](./queue-provider-timeouts.md), [queue circuit adapter](./queue-circuit-operation-adapter.md), and [queue worker](./queue-worker-coordinator.md) |
""",
)
replace_once(
    readme,
    """| [Circuit-aware queue submission](./circuit-queue-submission.md) | Partial V1 subsystem | Preflight-before-permission ordering and enriched enqueue/circuit evidence. |
| [Queue submission](./queue-submission.md) | Available foundation | Application-owned work encoding and durable enqueue with optional timeout and additive circuit-aware execution. |
| [Durable queue processor](./durable-queue-processor.md) | Available foundation | Bounded acquire, execute, and single-transition processing. |
""",
    """| [Circuit-aware queue submission](./circuit-queue-submission.md) | Partial V1 subsystem | Preflight-before-permission ordering and enriched enqueue/circuit evidence. |
| [Circuit-aware queue processing](./circuit-queue-processing.md) | Partial V1 subsystem | Explicit acquisition/transition circuits, truthful partial counters, and uncollapsed record evidence. |
| [Queue submission](./queue-submission.md) | Available foundation | Application-owned work encoding and durable enqueue with optional timeout and additive circuit-aware execution. |
| [Durable queue processor](./durable-queue-processor.md) | Available foundation | Bounded acquire, execute, and single-transition processing. |
""",
)
replace_once(
    readme,
    """operation adaptation and circuit-aware submission now exist, while circuit-aware
worker and builder assembly, KMP iOS persistence, and end-to-end qualification
remain open.
""",
    """operation adaptation, submission, and bounded acquisition/transition processing
now exist, while circuit-aware recovery/worker and builder assembly, KMP iOS
persistence, and end-to-end qualification remain open.
""",
)
replace_once(
    readme,
    """V1 retry work still requires circuit-aware queue-worker and builder assembly,
complete transport/storage circuit assembly, protocol-specific timeout
""",
    """V1 retry work still requires circuit-aware queue recovery/worker and builder
assembly, complete transport/storage circuit assembly, protocol-specific timeout
""",
)

processor_doc = "docs/api/durable-queue-processor.md"
replace_once(
    processor_doc,
    """> **Status:** Available at-least-once queue-processing foundation. Retry and
> non-retry deferral transitions are distinct; complete retry/circuit policy,
> migrations, platform persistence, and V1 qualification remain.
""",
    """> **Status:** Available direct at-least-once queue-processing foundation. An
> additive circuit-aware processor now preserves permission, provider, and record
> evidence; recovery/worker assembly and V1 qualification remain open.
""",
)
replace_once(
    processor_doc,
    """## Public runtime contracts

Package: `io.dataloom.runtime.queue`

- `QueueEntryExecutionHandler`
""",
    """## Public runtime contracts

Package: `io.dataloom.runtime.queue`

The direct `DurableQueueExecutionProcessor` remains unchanged. For explicit
circuit permission and outcome recording, see
[Circuit-aware bounded queue processing](./circuit-queue-processing.md).

- `QueueEntryExecutionHandler`
""",
)

circuit_doc = "docs/api/circuit-execution-gate.md"
replace_once(
    circuit_doc,
    """`CircuitBreakerQueueSubmission` performs encoder and structural preflight before
calling the queue adapter. Invalid local input therefore cannot touch circuit
state or reserve a half-open probe, while valid enqueue attempts retain the full
execution and recording evidence.

## Security boundary
""",
    """`CircuitBreakerQueueSubmission` performs encoder and structural preflight before
calling the queue adapter. Invalid local input therefore cannot touch circuit
state or reserve a half-open probe, while valid enqueue attempts retain the full
execution and recording evidence.

`CircuitBreakerDurableQueueExecutionProcessor` applies explicit scopes to atomic
acquisition and every lease-guarded transition. Its terminal results distinguish
pre-execution stop, provider failure, and provider success followed by circuit-
recording failure, so confirmed transitions are counted without replaying them.

## Security boundary
""",
)
