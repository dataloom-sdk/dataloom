# DL-039B idempotent queue admission checkpoint

## Candidate decision

DataLoom introduces an additive provider-neutral queue capability for reconciling
an ambiguous durable admission without parsing duplicate-error messages:

```text
QueueIdempotentAdmissionProvider.admit(QueueEnqueueRequest)
    ├─ ID absent
    │    └─ persist once → Accepted(PENDING)
    ├─ same ID + same immutable admission identity
    │    └─ no mutation → AlreadyAccepted(current state)
    └─ same ID + different immutable admission identity
         └─ no mutation → IdentityConflict(current state)
```

The decision is atomic under the provider's existing transaction or shared file
lock. Ordinary `QueueProvider.enqueue` remains create-only and source-compatible.
No caller is silently moved to idempotent semantics.

## Immutable admission identity

`QueueEntry.hasSameQueueAdmissionIdentityAs` compares:

- queue entry ID;
- exact synchronization request and execution context;
- entry metadata;
- immutable workflow timeout evidence;
- persisted strategy decision; and
- complete immutable accepted strategy plan.

It deliberately ignores lifecycle state, enqueue/availability timestamps, retry
state, lease, and last error. Those fields change after a successful admission
and cannot safely determine whether a caller is reconciling the same logical
work.

`AlreadyAccepted` exposes the current queue state. A terminal or cancelled state
is therefore not misrepresented as currently runnable work. `IdentityConflict`
exposes no existing request, metadata, strategy identity, payload, or error.

## Candidate provider implementations

### In-memory testing provider

The deterministic testing provider performs first-or-existing evaluation against
its existing insertion-ordered map. Idempotent admission deliberately does not
populate the historical ordinary-enqueue request log, preventing tests from
mistaking `admit` for `enqueue`. As documented for this testing provider,
callers still serialize mutable access externally.

### Android Room

Room admission executes in one `@Transaction`:

1. insert with `OnConflictStrategy.IGNORE`;
2. return `Accepted` when a row was inserted;
3. otherwise read the existing row inside the same transaction;
4. compare immutable admission identity; and
5. return `AlreadyAccepted` or `IdentityConflict` without mutation.

No Room column, table, index, database version, or migration is added.

### Apple file-backed queue

Apple admission executes while holding the provider's existing process-shared
exclusive advisory lock. It reads and validates the bounded snapshot, evaluates
the exact identity, and writes the snapshot only for first admission. Duplicate
or conflicting evaluation performs no replacement write.

The existing version-4 queue format remains unchanged.

## Correctness boundaries

- Duplicate semantics are typed, not inferred from an error code, exception, or
  provider message.
- The same queue ID can never replace different immutable work.
- First admission stores an initially pending entry with mutable failure state
  cleared.
- A repeated same admission may observe `PENDING`, `LEASED`, `RETRY_WAITING`,
  `COMPLETED`, `FAILED`, `CANCELLED`, or `DEAD_LETTER`.
- Admission invokes no scheduler, worker, transport, application storage,
  retry policy, conflict engine, or observer.
- Caller cancellation propagates.
- Provider I/O, corruption, capacity, or indeterminate state remains an ordinary
  `ProviderOperationResult.Failure`.

## Dependency and SDK boundary

This checkpoint adds no Gradle dependency, repository, plugin, third-party
library, hosted service, database wrapper, networking wrapper, analytics SDK,
or vendor integration. It uses only the existing DataLoom queue model, Kotlin,
Room already present in the Android queue module, and native Apple file/locking
mechanics already present in the Apple queue provider.

Application domain data, repositories, backend contracts, credentials, and
business authorization remain outside DataLoom.

## Executable evidence

The candidate test matrix covers:

- immutable identity comparison while mutable queue state changes;
- first admission and repeated same admission;
- same-ID different-work conflict;
- current state after leasing or completion;
- separation from ordinary enqueue-call evidence;
- Android concurrent transactions producing one accepted result;
- Apple cross-instance contention producing one accepted result;
- Apple restart through a new provider instance;
- bounded result diagnostics that do not render queue IDs; and
- external-consumer compilation of the additive SPI.

The candidate implementation and authoritative JVM/Kotlin-Native ABI baselines
passed the one-time shared, Apple simulator, and external-consumer qualification.
The permanent shared, Android managed-device, Room schema, Apple target,
XCFramework, header, and Swift-smoke workflows must still pass on this exact
human-authored reviewed head before merge.

## Remaining durable refresh work

This prerequisite does not yet claim durable cache refresh admission. The next
runtime slice must:

1. introduce caller-owned stable refresh queue and schedule identities;
2. construct exact immutable accepted continuation work;
3. require `QueueIdempotentAdmissionProvider` before promising durability;
4. admit queue work before scheduling;
5. report first/already/terminal/conflict outcomes truthfully;
6. preserve durable admission when scheduling fails or is cancelled;
7. execute the frozen continuation without current-policy reselection; and
8. prove restart, duplicate, scheduler-failure, Android, KMP Android, and KMP
   iOS behavior.

Issues #102 and #101 remain open. The V1 dashboard remains 10% and NO-GO.
