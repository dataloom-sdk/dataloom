# DL-039B atomic offline-first admission contract checkpoint

## Accepted in this slice

`StrategyOfflineFirstAdmissionProvider` is the explicit application-owned
boundary for offline-first acceptance. Its one operation must commit local
intent and the durable outbox/reconciliation record together, or return a
typed failure without reporting acceptance.

The request freezes the synchronization request, decision identity, immutable
offline-first plan, trigger, queue identity, and caller-owned idempotency key.
It rejects non-offline-first, rejected, identity-only, or non-durable plans
before a provider can be invoked. The result distinguishes a first durable
commit from an idempotent duplicate.

The contract accepts no domain payloads, credentials, exception text, or
platform transaction types. Providers must preserve cancellation and must not
call remote transport from the admission transaction.

Evaluated plans that combine `ACCEPT_LOCAL` with `ENQUEUE_DURABLE_WORK` now
declare `ATOMIC_LOCAL_ADMISSION`. Admission requests reject plans that omit
that capability, and provider resolution fails closed unless the selected
storage provider implements `StrategyOfflineFirstAdmissionProvider`. Resolution
does not invoke the provider. Existing persisted V1 plan frames remain
decodable; the new requirement is enforced at new evaluation and admission.

## Remaining integration

This is a public contract checkpoint, not a completed offline-first runtime.
The next implementation must invoke the resolved boundary before reporting
direct or queued acceptance and provide Android, KMP Android, and KMP iOS
adapters with crash/restart and transaction-failure evidence. Durable strategy
events, cache ownership, hybrid coherence, and the full platform matrix remain
separate #102/#101 gates.
