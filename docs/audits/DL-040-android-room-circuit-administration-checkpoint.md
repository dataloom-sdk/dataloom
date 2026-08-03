# DL-040 Android Room circuit-administration checkpoint

## Scope

This checkpoint adds the production Android Room implementation of
`CircuitAdministrationStateStore` and `CircuitAdministrationExecutor`. It
persists authorized open/close/reset commands and closes the crash window
between a circuit-state mutation and its durable idempotency receipt.

The command and circuit state must use the same `DataLoomRoomDatabase`. No
Room or SQLite type crosses the public platform-neutral contracts.

## Durable schema

Room schema version 6 adds `circuit_administration_states` with:

- exact bounded command, principal, scope, action, reason, request time, and
  optional requested open deadline;
- authorization, status, update time, and rejection evidence;
- the exact resulting circuit phase, counters, deadlines, probe state, update
  time, and durable circuit version;
- canonical execution error code/category/severity/recoverability; and
- a monotonic command-record version.

`MIGRATION_5_6` only creates the new table. Existing queue, circuit, and retry
administration rows remain unchanged. The committed schema identity is
`55b94bad54de59f5750399cb64f0974b`.

## Atomic executor invariants

One Room transaction:

1. loads and reconstructs the durable command through public model invariants;
2. verifies every immutable field and the exact authorization identifier;
3. rejects non-authorized, conflicting, expired-open, or clock-regressed input;
4. loads and validates the exact circuit scope and version;
5. applies `OPEN`, `CLOSE`, or `RESET` while preserving monotonic probe
   generation;
6. advances the circuit record exactly once;
7. advances the command from `AUTHORIZED` to `SUCCEEDED`; and
8. records the exact resulting circuit state/version as the replay receipt.

If either guarded update does not affect exactly one row, Room rolls the whole
transaction back. A repeated `SUCCEEDED` command returns the stored result
before touching circuit state.

`CLOSE` preserves an existing closed failure window, while `RESET` clears that
window. Both invalidate an active probe by leaving `HALF_OPEN` and retain its
generation, preventing stale permit reuse.

## Focused evidence

Unit tests cover load/conflict reconstruction, partial-result corruption,
version exhaustion, canonical storage/executor failures, semantic rejection,
exact applied-result mapping, redaction, and cancellation propagation.

Managed-device tests exercise real SQLite transactions for successful reset,
exact command/circuit receipt agreement, replay without a second mutation,
probe-generation preservation, and authorization mismatch without mutation.
Migration coverage verifies a version-5 circuit row survives migration and the
new command table starts empty.

The final pull-request head must pass Pull Request Validation, Android static
and managed-device validation, exact Room schema verification, and Apple
regression validation before merge.

## Remaining DL-040 work

- production Apple circuit-administration persistence and atomic execution;
- operations facade assembly;
- canonical retry/circuit administration events, metrics, logs, traces, health,
  and operational read models;
- relaunch, multi-process, higher-contention, and injected-failure evidence; and
- complete Book 2 `AC-FUNC-004` qualification.
