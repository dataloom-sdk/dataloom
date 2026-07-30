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
    """| Submit and process durable work | [Queue submission](./queue-submission.md), [queue provider](./queue-provider.md), [queue-provider timeouts](./queue-provider-timeouts.md), and [queue worker](./queue-worker-coordinator.md) |
""",
    """| Submit and process durable work | [Queue submission](./queue-submission.md), [queue provider](./queue-provider.md), [queue-provider timeouts](./queue-provider-timeouts.md), [queue circuit adapter](./queue-circuit-operation-adapter.md), and [queue worker](./queue-worker-coordinator.md) |
""",
)
replace_once(
    readme,
    """| [Queue-provider timeouts](./queue-provider-timeouts.md) | Partial V1 subsystem | Cooperative lifecycle, acquisition, recovery, and transition timeout protection plus additive queue-worker assembly. |
| [Queue submission](./queue-submission.md) | Available foundation | Application-owned work encoding and durable enqueue. |
""",
    """| [Queue-provider timeouts](./queue-provider-timeouts.md) | Partial V1 subsystem | Cooperative lifecycle, submission, acquisition, recovery, and transition timeout protection plus builder/runtime assembly. |
| [Queue circuit operation adapter](./queue-circuit-operation-adapter.md) | Partial V1 subsystem | Explicit queue-operation circuit permission, queue-aware timeout classification, and uncollapsed provider/record evidence. |
| [Queue submission](./queue-submission.md) | Available foundation | Application-owned work encoding and durable enqueue with optional enqueue timeout. |
""",
)
replace_once(
    readme,
    """ambiguity and never replay a mutation automatically. KMP iOS persistence,
circuit assembly, builder adoption, and end-to-end qualification remain open.
""",
    """ambiguity and never replay a mutation automatically. Explicit queue circuit
operation adaptation now exists, while circuit-aware worker/submission assembly,
KMP iOS persistence, and end-to-end qualification remain open.
""",
)
replace_once(
    readme,
    """| [Circuit execution gate](./circuit-execution-gate.md) | Partial V1 subsystem | Pre-execution permission, once-only invocation, classified provider failures, post-execution evidence, and retry scheduling adaptation. |
| [Conflict contracts](./conflict-contracts.md) | Partial V1 subsystem | Custom detector, resolver, request, conflict, and decision contracts. |
""",
    """| [Circuit execution gate](./circuit-execution-gate.md) | Partial V1 subsystem | Pre-execution permission, once-only invocation, classified provider failures, post-execution evidence, retry scheduling, and queue-operation adaptation. |
| [Queue circuit operation adapter](./queue-circuit-operation-adapter.md) | Partial V1 subsystem | Exact queue operation scopes and provider/circuit result preservation without transparent mutation replay risk. |
| [Conflict contracts](./conflict-contracts.md) | Partial V1 subsystem | Custom detector, resolver, request, conflict, and decision contracts. |
""",
)
replace_once(
    readme,
    """V1 retry work still requires complete transport/storage/queue circuit assembly,
protocol-specific timeout enforcement, KMP iOS persistence, manual
""",
    """V1 retry work still requires circuit-aware queue worker/submission assembly,
complete transport/storage circuit assembly, protocol-specific timeout
enforcement, KMP iOS persistence, manual
""",
)

execution_doc = "docs/api/circuit-execution-gate.md"
replace_once(
    execution_doc,
    """`CircuitBreakerRetrySchedulingAdapter` applies the provider adapter to
`SchedulerProvider.schedule`. A provider-scoped circuit must identify the same
scheduler provider; global and workflow scopes remain valid explicit choices.
""",
    """`CircuitBreakerRetrySchedulingAdapter` applies the provider adapter to
`SchedulerProvider.schedule`. A provider-scoped circuit must identify the same
scheduler provider; global and workflow scopes remain valid explicit choices.

`CircuitBreakerQueueOperationAdapter` applies the same gate to explicit
`QueueProvider` lifecycle and queue operations while preserving the enriched
`CircuitBreakerExecutionResult`. It deliberately does not implement
`QueueProvider`, because collapsing an executed queue mutation and a later
circuit-recording failure into one plain provider result would lose idempotency-
critical evidence. Provider-bearing and operation-bearing scopes are validated
before state-store access or provider invocation.
""",
)
