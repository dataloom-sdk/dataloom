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
    """| [Transport timeout and circuit boundary](./transport-circuit-timeouts.md) | Partial V1 subsystem | Cooperative transport provider timeout, exact lifecycle/push/pull scopes, and uncollapsed remote-operation/record evidence. |
| [Queue circuit operation adapter](./queue-circuit-operation-adapter.md) | Partial V1 subsystem | Exact queue operation scopes and provider/circuit result preservation without transparent mutation replay risk. |
""",
    """| [Transport timeout and circuit boundary](./transport-circuit-timeouts.md) | Partial V1 subsystem | Cooperative transport provider timeout, exact lifecycle/push/pull scopes, and uncollapsed remote-operation/record evidence. |
| [Storage timeout and circuit boundary](./storage-circuit-timeouts.md) | Partial V1 subsystem | Cooperative storage provider timeout, exact lifecycle/read/mutation/checkpoint scopes, and uncollapsed durable-operation/record evidence. |
| [Queue circuit operation adapter](./queue-circuit-operation-adapter.md) | Partial V1 subsystem | Exact queue operation scopes and provider/circuit result preservation without transparent mutation replay risk. |
""",
)
replace_once(
    readme,
    """V1 retry work still requires direct transport pipeline/builder assembly,
storage circuit assembly, protocol-specific timeout
""",
    """V1 retry work still requires direct transport/storage pipeline and builder
assembly, protocol-specific timeout
""",
)

circuit_doc = "docs/api/circuit-execution-gate.md"
replace_once(
    circuit_doc,
    """`CircuitBreakerTransportOperationAdapter` applies the same gate to explicit
transport lifecycle, push, and pull operations. Exact provider and operation
scope validation occurs before state or provider access, and an executed remote
operation remains separate from its later circuit-recording result.

`CircuitBreakerQueueOperationAdapter` applies the same gate to explicit
""",
    """`CircuitBreakerTransportOperationAdapter` applies the same gate to explicit
transport lifecycle, push, and pull operations. Exact provider and operation
scope validation occurs before state or provider access, and an executed remote
operation remains separate from its later circuit-recording result.

`CircuitBreakerStorageOperationAdapter` applies the gate to storage lifecycle,
reads, mutations, acknowledgements, and checkpoints. Executed durable mutations
remain separate from later circuit-recording failures so they are not replayed
implicitly.

`CircuitBreakerQueueOperationAdapter` applies the same gate to explicit
""",
)

storage_doc = "docs/api/storage-provider.md"
replace_once(
    storage_doc,
    """## Package
""",
    """## Timeout and circuit adaptation

The provider contract remains direct. The additive
`TimeoutEnforcingStorageProvider`, `StorageProviderTimeoutRuntime`, and
`CircuitBreakerStorageOperationAdapter` provide cooperative provider timeout
and explicit circuit evidence without changing the SPI. See
[Storage provider timeout and circuit boundary](./storage-circuit-timeouts.md).

A timed-out apply, acknowledgement, or checkpoint write remains
completion-ambiguous; replay requires reconciliation or an independently proven
idempotency contract.

## Package
""",
)

retry_timeout_doc = "docs/api/retry-timeouts.md"
replace_once(
    retry_timeout_doc,
    """## Transport-provider timeout enforcement
""",
    """## Storage-provider timeout enforcement

`TimeoutEnforcingStorageProvider` and `StorageProviderTimeoutRuntime` apply the
provider boundary to storage lifecycle, reads, mutations, acknowledgements, and
checkpoints. Mutating timeout results retain unknown completion and do not
authorize automatic replay.

## Transport-provider timeout enforcement
""",
)
