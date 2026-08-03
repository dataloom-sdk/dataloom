# DL-040 Apple circuit-administration checkpoint

## Scope

This checkpoint records production KMP Apple command persistence and atomic
circuit-administration execution. It does not claim facade assembly, complete
observability, executable process-death qualification, AC-FUNC-004 acceptance,
or V1 readiness.

## Implemented boundary

- `AppleFileCircuitAdministrationStateStore` with exact versioned compare-and-set
  command persistence and immutable request protection;
- `AppleFileCircuitAdministrationExecutor` for authorized `OPEN`, `CLOSE`, and
  `RESET` operations;
- one process-shared advisory lock and one bounded snapshot shared with
  `AppleFileCircuitBreakerStateStore`;
- atomic temporary-write, file fsync, rename, and directory fsync for the circuit
  mutation plus exact `SUCCEEDED` command receipt;
- durable replay of the exact resulting circuit record without a second
  mutation;
- tagged v2 snapshot records with backward-compatible v1 circuit-only reads;
  and
- bounded administrative record count under the existing 4 MiB file cap.

## Safety invariants

1. The exact durable command must exist in `AUTHORIZED` state before mutation.
2. The immutable request and authorization ID must match the executor input.
3. Command conflict, authorization mismatch, elapsed open deadline, clock
   regression, corrupt state, and exhausted versions fail closed.
4. Administrative transitions preserve the monotonic probe generation so a
   stale probe permit cannot become valid again.
5. `CLOSE` preserves an existing closed failure window; `RESET` clears it.
6. Circuit mutation and success receipt become visible together through one
   atomic replacement.
7. Durable evidence excludes payloads, credentials, headers, exception text,
   stack traces, provider instances, and arbitrary metadata.
8. Lock waiting remains cancellation-aware and cancellation is never converted
   into a command failure.

## Focused evidence

Apple simulator tests cover shared-snapshot preservation, command persistence,
exact conflicts, immutable request protection, all terminal status shapes,
corruption, version exhaustion, cancellation, path validation, reset and exact
receipt, replay, authorization mismatch, command conflict, close/open semantics,
deadline and clock rejection, missing/non-authorized commands, and v1-to-v2
migration without circuit-state loss.

The new platform-independent codec, state-store, and executor sources compile
against the exact Kotlin 2.4.10 common API. The immutable pull-request head must
additionally pass iOS target/test compilation, Kotlin/Native ABI validation,
XCFramework/header audit, and Swift smoke compilation.

## Remaining DL-040 work

- facade and operations assembly;
- retry/circuit events, bounded metrics, logs, traces, health, exporters, and
  an operational read model;
- executable relaunch, multi-process contention, and forced-failure injection;
  and
- complete Book 2 AC-FUNC-004 qualification on mandatory consumer paths.
