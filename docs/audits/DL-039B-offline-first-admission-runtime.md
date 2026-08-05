# DL-039B deferred offline-first admission runtime checkpoint

## Accepted in this slice

The strategy runtime now invokes the application-owned
`StrategyOfflineFirstAdmissionProvider` before it reports a deferred
offline-first request as durably admitted.

The caller supplies a bounded `StrategyOperationInput.OfflineFirstAdmission`
containing the queue-entry identity and a caller-owned idempotency key. The
runtime evaluates the immutable offline-first plan, resolves the exact storage,
queue, and atomic-admission capabilities, and invokes one provider operation.
It does not issue a second `StorageProvider` or `QueueProvider` write around the
atomic transaction.

A successful provider result is accepted only when its queue-entry identity and
idempotency key exactly match the request. First-time and duplicate admissions
are represented separately as `ACCEPTED` and `ALREADY_ACCEPTED`. A mismatched
success fails closed with the stable non-recoverable code
`STRATEGY_OFFLINE_FIRST_ADMISSION_IDENTITY_MISMATCH`.

Provider failure returns `StrategySynchronizationExecutionResult.Failed` with
`transportAttempted=false`; the runtime does not report durable acceptance or
attempt remote reconciliation. Cancellation remains uncaught and propagates to
the caller.

The current generic provider-protection bridge does not claim to preserve the
atomic storage extension. A protected offline-first admission therefore fails
closed unless a future mutation-safe protection boundary explicitly preserves
that contract. This avoids placing a cancellation timeout around an atomic
mutation whose commit outcome could become ambiguous.

## Executable evidence

Focused common/JVM tests cover:

- first durable admission;
- idempotent duplicate admission;
- provider failure without false acceptance or transport activity;
- mismatched provider identity;
- missing admission input;
- uninitialized provider lifecycle;
- no separate queue-provider operation; and
- fail-closed online execution outside this bounded slice.

The external-consumer fixture compiles the public admission input and deferred
admission disposition without depending on internal runtime types.

## Deliberate remaining boundary

This checkpoint implements direct deferred admission for offline-first plans
that contain `ACCEPT_LOCAL`, `ENQUEUE_DURABLE_WORK`, an immutable durable
continuation, and `ATOMIC_LOCAL_ADMISSION`.

It does not yet complete offline-first V1 acceptance. Remaining work includes:

1. Execute the online admission-plus-immediate-reconciliation path without
   weakening the atomic acceptance boundary.
2. Add production native Android, KMP Android, and KMP iOS provider
   implementations that commit application intent and durable continuation in
   one transaction or equivalent atomic protocol.
3. Prove transaction rollback, duplicate delivery, process termination after
   every durable transition, relaunch, scheduler failure, lease recovery, and
   exactly-once observable recovery.
4. Add durable strategy lifecycle events and operations-read-model adoption.
5. Complete retry, conflict, acknowledgement, and cancellation matrices for
   PUSH, PULL, and BIDIRECTIONAL FULL/DELTA flows.

Issue #102 and platform gate #101 remain open until those conditions pass on the
same immutable candidate.
