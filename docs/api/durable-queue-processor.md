# Durable Queue Execution Processor (DL-026)

## Overview

`DurableQueueExecutionProcessor` is the bounded processing-cycle foundation
between queue acquisition and synchronization/retry orchestration.

It executes one cycle per `process(request)` call:

1. Acquire entries exactly once from `QueueProvider`.
2. Validate the acquisition result before any handler invocation.
3. Execute entries sequentially in acquisition-result order.
4. Persist exactly one queue transition per entry outcome.
5. Return a structured `QueueProcessingResult`.

---

## Public runtime contracts

Package: `io.dataloom.runtime.queue`

- `QueueEntryExecutionHandler`
- `QueueEntryExecutionOutcome`
- `QueueProcessingRequest`
- `QueueProcessingSummary`
- `QueueProcessingFailureStage`
- `QueueProcessingResult`
- `DurableQueueExecutionProcessor`

---

## Core guarantees

- Acquisition occurs exactly once per processing cycle.
- `QueueProcessingRequest` preserves the exact caller-supplied
  `QueueAcquireRequest`.
- Acquisition validation runs before execution.
- Duplicate `QueueEntryId` and consumer-identity mismatch are rejected as
  `QueueProcessingResult.QueueContractViolation`.
- Entries execute sequentially and each entry reaches the handler at most once.
- Transition mapping is 1:1 with outcomes:
  - `Completed` -> `QueueProvider.complete`
  - `Reschedule` -> `QueueProvider.reschedule`
  - `Failed` -> `QueueProvider.fail`
  - `Cancelled` -> `QueueProvider.cancel`
- Lease and entry identities are preserved in transitions; replacement leases are
  not generated.
- Transition failure stops later entry execution and preserves:
  - exact `DataLoomError`
  - correct `QueueProcessingFailureStage`
  - affected `QueueEntryId`
  - truthful partial summary

---

## Cancellation semantics

- Thrown `CancellationException` from acquisition, handler, or transition
  propagates unchanged.
- Explicit `QueueEntryExecutionOutcome.Cancelled` is a business outcome and is
  distinct from thrown cancellation.
- Thrown cancellation does not create a queue cancellation transition.

---

## Summary invariants

`QueueProcessingSummary` enforces:

- all counters are non-negative
- `executed <= acquired`
- `(completed + rescheduled + failed + cancelled) <= executed`

---

## Scope boundaries

The processor does not invoke:

- synchronization coordinators or pipelines
- retry policy/orchestrator
- scheduler/connectivity providers
- storage/transport/conflict/lifecycle/observer/event operations

The processor does not expose queue payloads, credentials, provider internals,
or stack traces.

---

## Reliability model

DL-026 provides **at-least-once** execution semantics, not exactly-once.

If transition persistence fails after handler execution, work may remain leased
until lease expiration. Recovery is handled by queue lease-recovery flow
(`QueueProvider.recoverExpiredLeases`) outside this processor.
