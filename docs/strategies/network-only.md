# Network-Only Strategy

> [!WARNING]
> Direct network-only `PUSH`, `PULL`, and `BIDIRECTIONAL` execution is
> implemented with transport-only, plan-aware resolution. Complete V1
> operational events, enriched origin/durability metadata, bounded retry, and
> the full cross-platform qualification matrix remain open.

[Strategy index](./README.md) · [Remote-first](./remote-first.md) ·
[Hybrid](./hybrid.md) · [Adaptive](./adaptive.md)

## Purpose

Choose network-only when the operation must use the remote transport directly
and must make zero DataLoom storage-provider and queue-provider calls.
Successful remote execution is required; unavailable connectivity or a remote
failure produces a typed outcome rather than local fallback or implicit
queueing.

Network-only is deliberately strict. It is not remote-first with fallback
disabled after the fact: storage and queue are absent from the effective plan.

Direction, transfer mode, and trigger remain distinct:

- `PUSH`, `PULL`, and `BIDIRECTIONAL` describe remote transfer direction.
- `FULL` and `DELTA` describe transfer scope.
- A direct or externally delivered platform trigger can execute a network-only
  plan.
- A DataLoom durable-queue trigger is incompatible because acquiring and
  completing that entry necessarily calls `QueueProvider`. Capability
  validation must reject that combination rather than silently changing the
  strategy.

## Current repository

The strategy facade accepts `StrategySynchronizationRequest` with explicit
`StrategyOperationInput.DirectTransport` input. `StrategyProviderBindings`
contains optional provider IDs, and the strategy resolver uses only the
capabilities required by the evaluated plan.

For network-only, the required set contains transport alone. Storage, queue,
scheduler, and connectivity bindings—even invalid extra IDs—are not resolved.
The executor then:

- sends the caller-owned `ChangeSet` directly for `PUSH`;
- returns the canonical `PullChangesResult` directly for `PULL`; and
- performs push before pull for `BIDIRECTIONAL`.

It validates that a successful push acknowledgement belongs to the submitted
change set and accounts for every submitted event. A pull failure after a
successful bidirectional push preserves the completed `PUSH_REMOTE` operation
and exact acknowledgement as partial output.

The legacy `SynchronizationRequest` facade and its
[`SynchronizationProviderBindings`](../api/provider-bindings.md) remain
unchanged for storage-backed direction pipelines. They are not used to
implement network-only.

## V1 required behavior

The V1 rule is:

> Resolve only remote-plan capabilities, invoke transport without any
> DataLoom storage or queue operation, and return the canonical remote outcome.

```mermaid
sequenceDiagram
    participant Caller
    participant Policy as Strategy policy
    participant Runtime as Strategy orchestrator
    participant Connectivity
    participant Transport
    Caller->>Policy: Admit network-only request
    Policy-->>Runtime: Transport-only immutable plan
    opt Connectivity rule configured
        Runtime->>Connectivity: Read one classified snapshot
        Connectivity-->>Runtime: Available, unavailable, constrained, or unknown
    end
    alt Remote execution admitted
        Runtime->>Transport: Execute PUSH, PULL, or BIDIRECTIONAL operation
        Transport-->>Runtime: Canonical remote outcome
        Runtime-->>Caller: Typed result with REMOTE origin
    else Remote execution not admitted
        Runtime-->>Caller: Typed unavailable or policy result
    end
```

The strategy must not invoke storage or queue before, during, or after this
sequence.

### Required plan semantics

1. Validate a transport provider and any explicitly configured connectivity,
   authentication, retry, or policy capabilities.
2. Reject any profile option or trigger that would require storage, cache,
   outbox, checkpoint persistence, or DataLoom queueing.
3. Evaluate and identify the immutable network-only plan.
4. Call transport directly with the operation's explicit input or remote cursor
   contract.
5. Return the canonical remote result and truthful metadata.
6. Permit only bounded in-call retry when explicitly configured; never promise
   restart-safe retry or background completion without changing to a different
   strategy.

For DELTA operations, any continuation/cursor needed after the call must be
supplied by the caller or remote protocol. Network-only cannot persist it
through `StorageProvider`.

## Provider requirements

| Capability | Requirement |
|---|---|
| Transport | Required. |
| Connectivity | Optional as an explicit admission input; remote execution may also provide the canonical unavailable outcome. |
| Authentication/policy | Required when the transport operation depends on them. |
| Clock/randomness | Optional for bounded in-call timeout/retry policy. |
| Storage | Forbidden. |
| Queue / outbox | Forbidden. |
| Scheduler for DataLoom deferred work | Forbidden. An external host may trigger a new call. |
| Cache/checkpoint persistence | Forbidden. |

Circuit and operational state may be provided by dedicated policy/operations
foundations if they do not call `StorageProvider` or `QueueProvider` on behalf
of the operation. Such use must be declared; it cannot introduce local data
fallback, deferred payload work, or a false restart guarantee.

## Guarantees

- Every successful result comes from transport.
- The plan performs zero `StorageProvider` and zero `QueueProvider` calls.
- There is no implicit cache read, local persistence, outbox, checkpoint write,
  fallback, or queue admission.
- Offline, constrained, unknown, or remote-unavailable behavior is typed and
  explicit.
- Cancellation is propagated and does not enqueue work.
- The result never claims durable completion beyond the transport's declared
  acknowledgement boundary.

Network-only does not guarantee operation survival across application process
death. The caller or an external idempotent trigger must admit a new operation.

## Failure and fallback semantics

| Condition | Required behavior |
|---|---|
| Transport provider missing or incompatible | Reject before execution with a typed capability error. |
| Connectivity unavailable or constrained | Return typed unavailable/policy result; do not enqueue or read local state. |
| Connectivity unknown or check failure | Follow an explicit network-only rule, such as attempt transport or reject; never infer a cache fallback. |
| Transient transport failure | Return it or perform only explicitly bounded in-call retry. No durable reschedule. |
| Authentication, authorization, validation, integrity, or policy denial | Return the canonical failure or required-user-action result. |
| Partial remote effect | Return partial/indeterminate state and remote idempotency evidence when available; do not fabricate local recovery. |
| Cancellation or process death | Current call ends; no DataLoom durable work is created. |
| Storage or queue option configured | Reject the invalid profile/trigger combination. |

Network-only has no fallback branch. A requirement for fallback means the
caller should select [Remote-first](./remote-first.md) or
[Hybrid](./hybrid.md).

## Persistence and restart

Network-only creates no per-operation DataLoom storage or queue record. For a
direct call, decision and event evidence may be delivered to configured
observers/exporters, but that does not turn the operation into durable work.

If the host or operating system persistently triggers a later invocation, the
trigger must carry the complete non-sensitive strategy/configuration identity
and an idempotency key. Each delivery is a new network-only admission and still
makes zero queue calls. The result must not promise that a killed in-flight
call will resume.

## Result and event metadata

The current typed result includes the evaluated decision/plan, completion time,
transport output or canonical error, whether transport was attempted, provider
resolution failures, and completed-operation/partial-output evidence.

The complete V1 result contract must additionally include:

- effective strategy `NETWORK_ONLY`;
- origin `REMOTE`;
- transport attempted/not-attempted;
- connectivity and remote outcome classification;
- remote acknowledgement/idempotency evidence where available;
- decision, plan, configuration, trigger, direction, and mode; and
- `durable=false`, `queued=false`, `localPersisted=false`, and
  `fallbackActivated=false` or equivalent typed fields.

Required events still to be integrated include network-only plan selected,
connectivity decision, remote attempt started/completed, bounded in-call retry
when applicable, and terminal outcome. There must be no cache-served,
local-persisted, queued, deferred, or background-refresh event for an executed
network-only call.

## Platform parity

Native Android, KMP Android, and KMP iOS must make the same forbidden calls and
map equivalent connectivity/transport outcomes identically. Platform
networking stacks may differ, but no platform may add an implicit local cache,
queue, WorkManager job, or iOS background task.

An unavailable platform transport capability returns an explicit unsupported
result instead of selecting remote-first or offline-first.

## Acceptance gates

| Gate | Current status |
|---|---|
| Plan resolves transport without resolving storage, queue, scheduler, or connectivity | Implemented and locally tested |
| Transport success returns the exact acknowledgement or pull result | Implemented and locally tested |
| Bidirectional push precedes pull; pull failure preserves the completed push | Implemented and locally tested |
| Missing transport and durable-queue trigger reject before transport | Implemented and locally tested |
| JVM plus iOS Arm64, simulator Arm64, and simulator x64 compilation | Passing locally |
| Complete offline/constrained/timeout/auth/integrity/cancellation outcome matrix | Pending |
| Complete lifecycle/progress/retry/terminal event dispatch | Pending |
| Enriched origin/durable/queued/local-persisted/fallback metadata | Pending |
| Bounded in-call retry contract and tests | Pending |
| Native Android and Apple runtime qualification in repository CI | Pending publication of this slice |

## Related documentation

- [Strategy decision guide](./README.md)
- [ADR-0002 V1 architecture](../adr/ADR-0002-v1-artifact-and-foundation-architecture.md)
- [Provider Bindings](../api/provider-bindings.md)
- [Transport Boundaries](../architecture/transport-boundaries.md)
- [Connectivity Provider](../api/connectivity-provider.md)
- [Error Model](../api/error-model.md)
- [GitHub issue #102: strategy implementation gate](https://github.com/dataloom-sdk/dataloom/issues/102)
