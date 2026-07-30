# DL-040 Android Room circuit-state persistence checkpoint

## Decision

This slice implements the production Android `CircuitBreakerStateStore` adapter
and its non-destructive Room migration. It advances FR-RETRY-007, FR-RETRY-008,
and FR-RETRY-009, but it does **not** complete DL-040 or make V1 release-ready.

## Implemented evidence

- `RoomCircuitBreakerStateStore` implements the shared atomic load/compare-and-set
  contract over the application-owned DataLoom Room database.
- `circuit_breaker_states` persists explicit scope identity, phase, failure
  window, open deadline, probe generation, active probe lease, update time, and
  record version using stable names rather than enum ordinals.
- insert-if-absent and version-guarded update behavior execute inside one Room
  transaction and return the current record on conflict.
- record-version exhaustion fails closed without touching Room.
- malformed rows, unknown persisted names, invalid state shapes, mismatched
  scope keys, and invalid versions fail closed with a sanitized non-recoverable
  integrity error.
- coroutine cancellation propagates; ordinary Room failures map to a sanitized
  recoverable database error.

## Migration evidence

- `DataLoomRoomDatabase` advances from schema 2 to schema 3.
- `MIGRATION_2_3` adds an independent `circuit_breaker_states` table and does not
  rewrite or delete `queue_entries`.
- version 2 to 3 instrumentation preserves representative retry attempt,
  availability, elapsed-window, last-evaluation, and cumulative-delay values.
- version 1 to 2 migration remains covered and the production reopen path now
  installs the complete ordered migration set.
- committed Room schema 3 has identity hash
  `b8c634e15746eed115504cf1e0f16fe2` and contains exactly the queue and circuit
  tables expected by this database version.

## Test evidence

Focused JVM and Android-test compilation covers:

- missing record load;
- insert and version advancement;
- stale-version conflict with current-record evidence;
- close/reopen persistence;
- active probe-lease persistence;
- malformed-row fail-closed behavior;
- record-version exhaustion;
- sanitized database failure;
- cancellation propagation; and
- version 1 to 2 and version 2 to 3 migration behavior.

A temporary evidence workflow generated and verified schema 3, assembled debug,
release, and Android-test artifacts, ran unit tests and lint, committed the exact
schema, and removed itself before the final review head.

## Audit corrections made during review

The initial draft was not accepted as complete. Review found and corrected:

1. Android CI was hard-coded to schema version 2 and compared the wrong identity
   hash after the database advanced to version 3. Validation now derives the
   current version and hash from KSP output and verifies the matching committed
   schema.
2. The existing migration test reopened the current version-3 database with only
   `MIGRATION_1_2`. It now installs the complete production migration set and
   includes explicit version 2 to 3 preservation evidence.
3. Malformed durable state and version exhaustion were initially collapsed into
   recoverable database failure. They now fail closed as distinct,
   non-recoverable state errors.
4. New JVM test methods initially inferred non-`Unit` return types and were
   rejected by JUnit. The signatures now return `Unit` and the focused test lane
   passes.

## Remaining DL-040 gates

- production KMP iOS circuit-state persistence and relaunch migration;
- direct circuit assembly in transport, storage, queue, and synchronization
  execution paths;
- a production timeout executor and complete timeout integration;
- retry/circuit events, metrics, structured logs, and trace correlation;
- authorized and audited manual retry, reclassification, open, close, and reset;
- true concurrent and multi-process contention qualification; and
- Book 2 AC-FUNC-004 end-to-end backoff, jitter, open, reject, half-open, and
  recovery evidence.

The complete DataLoom V1 release remains a **NO-GO** until those gates and the
other mandatory V1 capability families are complete.
