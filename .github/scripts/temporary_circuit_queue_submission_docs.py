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
    """| Submit and process durable work | [Queue submission](./queue-submission.md), [queue provider](./queue-provider.md), [queue-provider timeouts](./queue-provider-timeouts.md), [queue circuit adapter](./queue-circuit-operation-adapter.md), and [queue worker](./queue-worker-coordinator.md) |
""",
    """| Submit and process durable work | [Queue submission](./queue-submission.md), [circuit-aware queue submission](./circuit-queue-submission.md), [queue provider](./queue-provider.md), [queue-provider timeouts](./queue-provider-timeouts.md), [queue circuit adapter](./queue-circuit-operation-adapter.md), and [queue worker](./queue-worker-coordinator.md) |
""",
)
replace_once(
    readme,
    """| [Queue circuit operation adapter](./queue-circuit-operation-adapter.md) | Partial V1 subsystem | Explicit queue-operation circuit permission, queue-aware timeout classification, and uncollapsed provider/record evidence. |
| [Queue submission](./queue-submission.md) | Available foundation | Application-owned work encoding and durable enqueue with optional enqueue timeout. |
""",
    """| [Queue circuit operation adapter](./queue-circuit-operation-adapter.md) | Partial V1 subsystem | Explicit queue-operation circuit permission, queue-aware timeout classification, and uncollapsed provider/record evidence. |
| [Circuit-aware queue submission](./circuit-queue-submission.md) | Partial V1 subsystem | Preflight-before-permission ordering and enriched enqueue/circuit evidence. |
| [Queue submission](./queue-submission.md) | Available foundation | Application-owned work encoding and durable enqueue with optional timeout and additive circuit-aware execution. |
""",
)
replace_once(
    readme,
    """ambiguity and never replay a mutation automatically. Explicit queue circuit
operation adaptation now exists, while circuit-aware worker/submission assembly,
KMP iOS persistence, and end-to-end qualification remain open.
""",
    """ambiguity and never replay a mutation automatically. Explicit queue circuit
operation adaptation and circuit-aware submission now exist, while circuit-aware
worker and builder assembly, KMP iOS persistence, and end-to-end qualification
remain open.
""",
)
replace_once(
    readme,
    """V1 retry work still requires circuit-aware queue worker/submission assembly,
complete transport/storage circuit assembly, protocol-specific timeout
enforcement, KMP iOS persistence, manual
""",
    """V1 retry work still requires circuit-aware queue-worker and builder assembly,
complete transport/storage circuit assembly, protocol-specific timeout
enforcement, KMP iOS persistence, manual
""",
)

queue_doc = "docs/api/queue-submission.md"
replace_once(
    queue_doc,
    """> **Status:** Available queue-submission foundation with separately governed
> enqueue timeout assembly. Applications still own work encoding; publication
> and complete consumer qualification remain open.
""",
    """> **Status:** Available queue-submission foundation with separately governed
> enqueue timeout and additive circuit-aware execution. Applications still own
> work encoding; builder circuit policy and complete qualification remain open.
""",
)
replace_once(
    queue_doc,
    """- `QueueSubmissionProviderTimeoutRuntime` — standalone protected assembly
""",
    """- `QueueSubmissionProviderTimeoutRuntime` — standalone timeout-protected assembly
- `CircuitBreakerQueueSubmission` — preflight-before-permission circuit path
- `CircuitBreakerQueueSubmissionResult` — enriched local and circuit result model
""",
)
replace_once(
    queue_doc,
    """7. On provider failure → returns `QueueProviderFailure`.

---

## DataLoom facade integration
""",
    """7. On provider failure → returns `QueueProviderFailure`.

---

## `CircuitBreakerQueueSubmission`

The additive circuit-aware path shares the same local encoding and structural
validation, but returns `CircuitBreakerQueueSubmissionResult` rather than
collapsing circuit evidence into `QueueSubmissionResult`.

```kotlin
val circuitSubmission = CircuitBreakerQueueSubmission(
    encoder = myEncoder,
    queueOperationAdapter = queueCircuitAdapter,
    scope = CircuitBreakerScope.providerOperation(
        providerId = queueProvider.descriptor.id,
        operation = QueueCircuitOperation.ENQUEUE.retryOperation,
    ),
)
```

Ordering is deliberate:

1. encode and validate locally;
2. return `EncodingRejected` or `ContractViolation` without circuit access;
3. request circuit permission only after preflight succeeds; and
4. preserve the full `CircuitBreakerExecutionResult<Unit>` in
   `EnqueueEvaluated`.

Preflight-before-permission prevents invalid input from reserving a half-open
probe. An `Executed` result proves enqueue ran exactly once and keeps the later
`CircuitBreakerRecordResult` visible. A recording failure must not be treated as
proof that enqueue did not occur.

This path is currently assembled explicitly and is not exposed through
`DataLoom.queueSubmission`; builder circuit-policy assembly remains open.

See [Circuit-aware queue submission](./circuit-queue-submission.md).

---

## DataLoom facade integration
""",
)

execution_doc = "docs/api/circuit-execution-gate.md"
replace_once(
    execution_doc,
    """critical evidence. Provider-bearing and operation-bearing scopes are validated
before state-store access or provider invocation.

## Security boundary
""",
    """critical evidence. Provider-bearing and operation-bearing scopes are validated
before state-store access or provider invocation.

`CircuitBreakerQueueSubmission` performs encoder and structural preflight before
calling the queue adapter. Invalid local input therefore cannot touch circuit
state or reserve a half-open probe, while valid enqueue attempts retain the full
execution and recording evidence.

## Security boundary
""",
)
