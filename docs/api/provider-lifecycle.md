# DataLoom Provider Lifecycle and Health (DL-007)

[API reference index](./README.md)

> **Status:** Available provider contracts plus aggregate runtime lifecycle
> coordination. Fleet health, automatic recovery, and operational policy remain
> V1 gaps.

This document defines lifecycle and health semantics for provider contracts in
`dataloom-api`.

`ProviderLifecycleState` describes provider-level lifecycle semantics. The
current runtime also supplies `ProviderLifecycleCoordinator`, which implements
deterministic aggregate initialization, rollback, and shutdown. It uses its own
`ProviderLifecycleCoordinatorState`; it does not mutate or enforce each
provider's `ProviderLifecycleState` value.

## Lifecycle states

`ProviderLifecycleState` defines:

- `CREATED`: the provider exists but initialization has not started.
- `INITIALIZING`: initialization is in progress.
- `READY`: the provider is available for its declared operations.
- `DEGRADED`: the provider remains partially usable with reduced capability or
  reliability.
- `FAILED`: the provider cannot currently perform its responsibilities.
- `CLOSING`: shutdown or resource release is in progress.
- `CLOSED`: shutdown is complete and new operations must not be accepted.

## Health statuses

`ProviderHealthStatus` defines:

- `UNKNOWN`: health has not been evaluated or cannot be determined.
- `HEALTHY`: the provider is operating normally.
- `DEGRADED`: the provider remains partially usable.
- `UNHEALTHY`: the provider is not currently able to perform reliably.

`ProviderHealth` carries:

- `status: ProviderHealthStatus`
- `error: DataLoomError?`
- `details: DataLoomMetadata`

## Lifecycle and health are distinct

Lifecycle state describes where the provider is in its operational lifecycle.
Health status describes current operating condition.

These signals are related but not equivalent and must not be treated as
interchangeable.

## Conceptual transitions

Typical lifecycle path:

```text
CREATED
   ↓
INITIALIZING
   ↓
READY
   ↓
CLOSING
   ↓
CLOSED
```

Exceptional paths may include:

```text
INITIALIZING → FAILED
READY → DEGRADED
DEGRADED → READY
DEGRADED → FAILED
FAILED → INITIALIZING
FAILED → CLOSING
```

The provider-level enum does not implement transitions. Aggregate lifecycle
coordination exists, but automatic provider recovery, health-driven
transitions, and retry timing do not.

## Initialization

Initialization uses `DataLoomProvider.initialize(context)` with
`ProviderInitializationContext`.

Initialization context is runtime-level setup input and is intentionally
separate from synchronization request execution context.

## Health checks

Health queries use `DataLoomProvider.health()` and return
`ProviderOperationResult<ProviderHealth>`.

The SPI does not prescribe polling, scheduling, thresholds, or alerting
behavior.

## Closing

Shutdown uses `DataLoomProvider.close()` and returns
`ProviderOperationResult<Unit>`.

The SPI itself does not define close ordering across providers.
`ProviderLifecycleCoordinator` closes successfully initialized providers in
reverse initialization order and reports ordered failures.

## Failure behavior

Failures are represented through `ProviderOperationResult.Failure` with
`DataLoomError`. The SPI does not expose provider-specific exception contracts.

## Runtime orchestration status

`ProviderLifecycleCoordinator` is available in `dataloom-core` and:

- initializes providers in registry order;
- rolls back prior successful initializations in reverse order after failure;
- shuts down successfully initialized providers in reverse order;
- preserves primary and rollback/shutdown failures structurally; and
- propagates coroutine cancellation.

It does not provide concurrent-call serialization, automatic restart,
continuous health polling, fleet health aggregation, policy-controlled
degradation, or enterprise operational controls.

See [Provider Registry](./provider-registry.md),
[Provider Bindings](./provider-bindings.md), and
[DataLoom Facade](./dataloom-facade.md) for assembly and admission behavior.
