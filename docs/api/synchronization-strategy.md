# Synchronization Strategy API

[API index](./README.md) ·
[Strategy guide](../strategies/README.md) ·
[ADR-0002](../adr/ADR-0002-v1-artifact-and-foundation-architecture.md)

DataLoom evaluates synchronization strategy before provider operations. The
evaluation is deterministic: the same immutable profile, direction, mode,
evidence, and identifiers produce the same decision and plan.

## Contract flow

```mermaid
flowchart LR
    P[Versioned profile]
    D[Direction and mode]
    E[Bounded runtime evidence]
    I[Caller-supplied decision and plan IDs]
    V{Built-in evaluator}
    A{Adaptive?}
    C[Selected concrete profile]
    R[Typed disposition]
    O[Ordered operations]
    Q[Required provider capabilities]
    X[Persisted decision identity]

    P --> V
    D --> V
    E --> V
    I --> V
    V --> A
    A -->|No| C
    A -->|Yes| C
    C --> R
    C --> O
    O --> Q
    R --> X
    C --> X

    style V fill:#DCCCFF,stroke:#874FFF
    style R fill:#FFECBD,stroke:#FFC943
    style X fill:#C2E5FF,stroke:#3DADFF
```

Evaluation performs no provider call, I/O, clock read, identifier generation,
random selection, or mutation. Provider health, connectivity, cache state, and
pending-local-work observations are captured before evaluation and supplied as
`StrategyRuntimeEvidence`.

## Profiles

Every profile has:

- a stable `StrategyProfileId`;
- a positive `StrategyConfigurationVersion`; and
- one `BuiltInSynchronizationStrategy`.

| Profile | Configuration frozen by the contract |
|---|---|
| `OfflineFirstStrategyProfile` | Durable queue requirement and online reconciliation |
| `RemoteFirstStrategyProfile` | Typed fallback allowlist, remote-result persistence, unknown-connectivity policy |
| `CacheFirstStrategyProfile` | Stale policy, fresh-hit refresh, durable refresh |
| `NetworkOnlyStrategyProfile` | Unknown-connectivity handling with queue-backed deferral prohibited |
| `HybridStrategyProfile` | Different primary/fallback sources, persistence, reconciliation, unknown-connectivity policy |
| `AdaptiveStrategyProfile` | Unique finite concrete candidates and optional explicit safe default |

Adaptive candidates cannot contain another adaptive profile. This bounds the
decision graph and prevents recursive or platform-dependent selection.

## Evaluation input

```kotlin
val evaluation = BuiltInSynchronizationStrategyEvaluator().evaluate(
    StrategyEvaluationRequest(
        decisionId = StrategyDecisionId("decision-42"),
        planId = StrategyPlanId("plan-42"),
        profile = CacheFirstStrategyProfile(
            id = StrategyProfileId("timeline-cache"),
            configurationVersion = StrategyConfigurationVersion(3),
            staleCachePolicy = StaleCachePolicy.SERVE_STALE_AND_REFRESH,
            requireDurableRefresh = true,
        ),
        direction = SynchronizationDirection.PULL,
        mode = SynchronizationMode.DELTA,
        evidence = StrategyRuntimeEvidence(
            connectivity = StrategyConnectivity.AVAILABLE,
            cacheState = StrategyCacheState.STALE,
            storageHealth = StrategyProviderHealth.HEALTHY,
            transportHealth = StrategyProviderHealth.HEALTHY,
            queueHealth = StrategyProviderHealth.HEALTHY,
            isBackgroundExecutionAvailable = true,
        ),
    ),
)
```

The caller supplies decision and plan IDs. The evaluator never generates them,
which keeps evaluation replayable and testable.

## Typed output

`StrategyEvaluationResult` contains:

- the exact `StrategyDecisionId`;
- one immutable `StrategyExecutionPlan`; and
- stable, non-sensitive reason codes.

The plan records requested and effective strategy separately. They differ only
when adaptive policy selects a concrete profile.

```mermaid
stateDiagram-v2
    [*] --> Execute: provider operations may begin
    [*] --> ServeAndRefresh: return local state and retain refresh promise
    [*] --> Defer: persist work without consuming retry
    [*] --> Reject: no provider side effect

    Defer --> Execute: durable work is reacquired
    ServeAndRefresh --> Execute: refresh work is reacquired
    Execute --> [*]
    Reject --> [*]
```

`DEFER` is a constraint decision, not a failed retry. The retry attempt must not
be incremented when a plan is deferred for connectivity or background
availability.

## Capability derivation

Provider requirements are derived from ordered plan operations:

| Operation | Required capability |
|---|---|
| `READ_LOCAL`, `READ_CHECKPOINT`, `ACCEPT_LOCAL`, `SERVE_LOCAL`, `PERSIST_REMOTE` | Storage |
| `PUSH_REMOTE`, `PULL_REMOTE` | Transport |
| `ENQUEUE_DURABLE_WORK` | Queue |
| `SCHEDULE_REFRESH` | Queue and scheduler |
| `RECONCILE` | Storage, transport, and conflict state |

Network-only plans are validated at construction. They cannot contain local,
queue, persistence, refresh, or reconciliation operations, and cannot require
storage or queue capabilities.

## Fallback safety

Remote-first fallback is allowlisted by `StrategyRemoteOutcome`. These outcomes
can never be configured as fallback triggers:

- cancellation;
- authentication failure;
- authorization failure;
- validation failure;
- integrity failure; and
- conflict.

Fallback is not inferred from arbitrary exceptions, missing providers,
registration order, or platform name.

## Durable replay

When a plan creates durable work, DataLoom carries the accepted
`PersistedStrategyDecision` beside the encoded work:

```text
decision ID
plan ID
requested strategy
effective profile ID and concrete strategy
configuration version
disposition
```

Queue-submission preflight rejects a changed, dropped, or invented decision
before timeout, circuit, or queue-provider policy. In-memory, Android Room, and
Apple file-backed queues preserve the exact identity through retry, non-retry
deferral, lease recovery, reopen, and migration. Legacy work remains explicitly
unplanned (`null`) rather than receiving current configuration.

The next execution gate must reconstruct or load the immutable accepted plan
from this identity. It must not re-evaluate current policy after retry, restart,
or platform rescheduling. An authorized migration is required to replace an
accepted plan.

## Current integration boundary

The profile contracts, deterministic planner, fail-closed durable admission,
plan-aware provider resolution, direct network-only execution, direct provider-
backed remote-first execution, and bounded strategy-decision queue persistence
are implemented in common Kotlin. Room and Apple stores preserve the same
bounded identity; complete native Android, KMP Android, and KMP iOS reference
qualification remains open.

`StrategyProviderBindings` makes every provider role optional. After policy
evaluation, `StrategyProviderResolver` resolves only the capabilities in the
immutable plan. An unused storage, queue, connectivity, or scheduler binding is
not looked up and cannot block a network-only call.

```mermaid
sequenceDiagram
    participant Caller
    participant Facade as DataLoom
    participant Policy as Strategy evaluator
    participant Resolver as Plan-aware resolver
    participant Transport

    Caller->>Facade: StrategySynchronizationRequest
    Facade->>Policy: Evaluate immutable profile + evidence
    Policy-->>Facade: Concrete plan + finite fallback branch
    Facade->>Resolver: Resolve required capabilities only
    Resolver-->>Facade: Only providers admitted by the plan
    Facade->>Transport: Execute primary remote operation
    Transport-->>Facade: Canonical output or classified failure
    Facade-->>Caller: Executed, fallback, failed, deferred, or rejected
```

Direct network-only calls use `StrategyOperationInput.DirectTransport`.
Outbound changes are caller-owned for `PUSH` and `BIDIRECTIONAL`; pull filters,
limits, and checkpoints remain caller/remote-owned. The runtime performs no
storage, queue, scheduler, or connectivity-provider operation on this path.

For a bidirectional call, push completes before pull starts. If pull then
fails, `StrategySynchronizationExecutionResult.Failed` records
`PUSH_REMOTE` in `completedOperations` and returns the exact push
acknowledgement in `partialOutput`. Callers can therefore avoid blindly
repeating a completed remote effect.

The legacy `DataLoom.synchronize(SynchronizationRequest)` pipeline remains
available and continues to use `SynchronizationProviderBindings`.

Remote-first uses `StrategyOperationInput.ProviderBacked`. A pull can skip
local persistence through `persistRemoteResult = false`; otherwise the
canonical inbound pipeline reads the checkpoint, pulls, applies, and writes the
checkpoint. A possible local fallback requires the selected storage adapter to
implement `StrategyLocalFallbackProvider`. The fallback contract reports
availability and freshness only—application repositories still own domain
reads.

Remote-first durable triggers, offline-first atomic admission/execution,
cache-first, hybrid, and adaptive runtime execution, immutable accepted-plan
reconstruction/replay, conflict application, complete strategy event enrichment,
and full native Android/KMP Android/KMP iOS reference qualification remain
separate integration gates. Unsupported plans and triggers are rejected rather
than silently executed through the legacy pipeline.
