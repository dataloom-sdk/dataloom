# DL-040 durable workflow timeout checkpoint

## Decision

An accepted workflow timeout must be represented by an immutable absolute
start/deadline pair and persisted with durable queued work. Recomputing the
window after retry, restart, deferral, recovery, or runtime reconfiguration is
not accepted.

## Implementation evidence

This slice adds:

- public `WorkflowTimeoutState` with validated and overflow-safe construction;
- public `WorkflowTimeoutStateExecutor` using the existing cancellation-capable
  timeout executor contract;
- optional `QueueEntry.workflowTimeoutState`;
- optional `QueuedSynchronizationSubmission.workflowTimeoutState`;
- preflight correspondence validation before queue-provider access;
- queued execution enforcement before the synchronization coordinator;
- fail-closed configuration, exact-deadline, and clock-regression outcomes;
- Android Room schema version 4 and non-destructive migration 3 to 4;
- strict all-null/all-present persisted-column validation;
- in-memory and Room transition/reopen preservation tests;
- JVM and Kotlin/Native external-consumer coverage.

## Invariants reviewed

- `deadline >= startedAt`.
- `observedAt >= deadline` is expired; the operation is not invoked.
- `observedAt < startedAt` is clock regression; the operation is not invoked.
- persisted deadline state is never overwritten by queue transitions.
- existing version-3 rows migrate with no invented timeout state.
- an encoder cannot alter or drop timeout evidence after submission acceptance.
- entries without timeout evidence retain historical execution behavior.
- entries with timeout evidence but no executor fail closed.
- timeout errors exclude sensitive payload and provider details.

## Focused qualification

The same-repository macOS evidence lane produced implementation head
`338355dd058fc0bf3b597dfb7c910058441f52a2` and completed:

- DataLoom API, runtime, testing, and Room JVM tests;
- runtime iOS Simulator tests;
- external consumer compilation for JVM, `iosArm64`, `iosSimulatorArm64`, and
  `iosX64`;
- exact API, runtime, testing, and Apple JVM/Kotlin-Native ABI generation;
- public ABI-boundary checks;
- Room schema version 4 generation and identity verification;
- Apple release XCFramework assembly;
- API-index integration;
- removal of the temporary patch script and evidence workflow.

The clean final review head must pass Pull Request, Android managed-device, and
Apple/Swift validation on the same commit before merge.

## Remaining DL-040 work

- protected strategy and queued-execution adoption;
- protocol-specific connection, request, and idle timeout adapters;
- production KMP iOS retry, circuit, and workflow-deadline persistence;
- authorized manual retry, reclassification, and circuit administration;
- complete retry/circuit events, metrics, logs, traces, diagnostics, and health;
- multi-process, process-death, restart, high-contention, failure-injection, and
  Book 2 `AC-FUNC-004` evidence.
