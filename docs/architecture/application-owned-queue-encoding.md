# Application-Owned Queue Encoding (DL-034)

## Overview

DL-034 introduces the queue-submission capability for DataLoom. A fundamental
design decision is that **DataLoom does not define a mandatory serialization
format for queue payloads**. The host application owns the encoding of
`QueuedSynchronizationWork` into the persistent queue entry, and the decoding
back into `QueuedSynchronizationWork` during execution.

---

## Problem

DataLoom is a synchronization coordinator, not an application data layer. It
orchestrates transport, queuing, retry, and coordination without depending on
the host application's domain models, API contracts, or serialization format.

Mandating a serialization format would:

- Couple DataLoom to a specific wire format (JSON, Protocol Buffers, etc.).
- Introduce serialization dependencies into the SDK.
- Prevent applications from using domain-specific encodings.
- Restrict encryption, compression, and content-type negotiation options.

---

## Solution: Application-Owned Encoder and Resolver

DataLoom defines a symmetric pair of application-supplied contracts:

| Role | Contract | Direction |
|------|----------|-----------|
| Submission encoding | `QueuedSynchronizationWorkEncoder` | Work → Queue entry |
| Entry resolution | `QueuedSynchronizationWorkResolver` | Queue entry → Work |

The **encoder** converts `QueuedSynchronizationWork` (request + bindings) into
a `QueueEnqueueRequest` before persistence. The **resolver** reconstructs
`QueuedSynchronizationWork` from an acquired `QueueEntry` before execution.

DataLoom invokes the encoder during submission and the resolver during worker
execution. It does not inspect, interpret, or validate the encoded bytes.

---

## Encoding boundary

```
Application
    → DataLoomQueueSubmission.submit(submission)
    → QueuedSynchronizationWorkEncoder.encode(submission)
    → QueueEnqueueRequest
    → validate structural correspondence
    → QueueProvider.enqueue(request)
    → QueueSubmissionResult
```

The encoder receives the exact `QueuedSynchronizationSubmission` and is
responsible for:

- Preserving `submission.queueEntryId` as `QueueEntry.id`.
- Preserving `submission.availableAt` as `QueueEntry.availableAt`.
- Setting `QueueEntry.state = PENDING` (required for enqueue).
- Setting `QueueEntry.enqueuedAt` to a meaningful instant.
- Encoding `submission.work` (request + bindings) into the entry's payload
  representation.

---

## Resolution boundary

```
QueueWorkerCoordinator.run()
    → QueueProvider.acquire()
    → QueuedSynchronizationWorkResolver.resolve(entry)
    → QueuedSynchronizationWork
    → SynchronizationExecutionCoordinator.execute(request, bindings)
```

The resolver is the application's inverse of the encoder. It receives the
exact `QueueEntry` returned by `QueueProvider.acquire` and must reconstruct
the `QueuedSynchronizationWork` (request + bindings) from it.

---

## Encoder and resolver compatibility

DataLoom does not verify payload round-trip compatibility during build or
submission. Applications are responsible for ensuring compatibility:

```
encode(submission.work)
    → durable queue storage
    → resolver.resolve(queueEntry)
    → equivalent QueuedSynchronizationWork
```

**Recommendation**: Test encoder and resolver together using deterministic
fakes before deploying to production.

---

## Supported encoding strategies

The following strategies are all valid. DataLoom does not mandate any of them:

- **Application-managed serialization**: application serializes
  `SynchronizationRequest` and provider IDs using its own JSON or binary
  encoder into `QueueEntry.metadata` or a custom field.
- **Reference-based resolution**: encode only a workflow or entity reference,
  then resolve the full request at execution time from application state.
- **Pre-serialized bytes**: encode a byte payload with a content type, pass
  it as `SynchronizationRequest` context or entry metadata.

---

## Structural validation

DataLoom validates structural correspondence between the submission and the
encoded request before calling `QueueProvider.enqueue`:

| Check                                    | Effect on mismatch    |
|------------------------------------------|-----------------------|
| Encoded `QueueEntryId` matches submission | `ContractViolation`   |
| Encoded `availableAt` matches submission  | `ContractViolation`   |
| Encoded entry state is `PENDING`          | `ContractViolation`   |
| No lease in encoded entry                 | `ContractViolation`   |
| No retry attempt in encoded entry         | `ContractViolation`   |

DataLoom does not inspect payload bytes, does not silently correct encoder
output, and does not substitute different values.

---

## Performance

Each `DataLoomQueueSubmission.submit` call performs at most:

- One encoder invocation
- One validation pass
- One `QueueProvider.enqueue` operation

DataLoom avoids payload copying, decoding, serialization, reflection, and
background work. Providers are resolved once at build time, not on each
submit call.

---

## Security

DataLoom does not expose:

- Encoded payload bytes
- Synchronization payloads
- Encoder state
- Resolver state
- Exception messages or stack traces

Safe diagnostics include only: `QueueEntryId`, `QueueEntryState`, `ErrorCode`,
and result variant.

---

## No automatic worker wake-up

Successful enqueue does not automatically:

- Call `QueueWorkerCoordinator`.
- Call `SchedulerProvider`.
- Create a worker schedule request.
- Start a coroutine.
- Process the queued entry.

The host application is responsible for triggering the queue worker after
submission. This separation prevents hidden background behavior.

---

## Module boundary

All submission contracts are in the `dataloom-runtime` module under package
`io.dataloom.runtime.submission`. No production module depends on
`dataloom-testing`. No circular module dependencies are introduced.

---

## KMP compatibility

All contracts use Kotlin standard-library and DataLoom API types only. No
Android API, JVM-only API, reflection, `ServiceLoader`, or DI framework is
used.

---

## Related documents

- [Queue Submission API (DL-034)](../api/queue-submission.md)
- [Queue Provider SPI (DL-015)](../api/queue-provider.md)
- [Queued Synchronization Execution (DL-027)](../api/queued-synchronization-execution.md)
- [Queue Worker Coordinator (DL-032)](../api/queue-worker-coordinator.md)
- [DataLoom Facade (DL-033)](../api/dataloom-facade.md)
