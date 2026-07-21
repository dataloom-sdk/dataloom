# DataLoom Synchronization Request (DL-005)

`SynchronizationRequest` is an immutable public contract in `dataloom-api`
that describes synchronization intent.

Creating a request does not execute or enqueue synchronization.

## Properties

- `workflowId: WorkflowId`
- `sessionId: SynchronizationSessionId`
- `direction: SynchronizationDirection`
- `mode: SynchronizationMode`
- `priority: WorkflowPriority` (defaults to `NORMAL`)
- `context: ExecutionContext`

## Workflow and session identifiers

`workflowId` identifies the logical workflow, and `sessionId` identifies the
associated synchronization session.

## Direction and mode

- Direction: `PUSH`, `PULL`, or `BIDIRECTIONAL`
- Mode: `FULL` or `DELTA`

These values describe intent only.

## Priority

Priority defaults to `WorkflowPriority.NORMAL`. Scheduler interpretation is
deferred to a runtime issue.

## Execution context

`context` carries correlation, tenant, user, version, locale, and metadata
details through `ExecutionContext`.

## Immutability and non-runtime behavior

`SynchronizationRequest` is a pure data contract:

- It does not start synchronization.
- It does not enqueue itself.
- It does not perform persistence, transport, authentication, or connectivity
  checks.

## Placeholder example

```kotlin
val request = SynchronizationRequest(
    workflowId = WorkflowId("workflow-001"),
    sessionId = SynchronizationSessionId("session-001"),
    direction = SynchronizationDirection.BIDIRECTIONAL,
    mode = SynchronizationMode.DELTA,
    priority = WorkflowPriority.NORMAL,
    context = ExecutionContext(
        executionId = ExecutionId("execution-001"),
        correlationId = CorrelationId("corr-001"),
    ),
)
```
