# DataLoom Provider Lifecycle and Health (DL-007)

This document defines lifecycle and health semantics for provider contracts in
`dataloom-api`.

These semantics are conceptual in DL-007. Runtime lifecycle orchestration and
transition enforcement are not implemented yet.

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

DL-007 does not implement a lifecycle state machine, transition enforcement,
automatic recovery, or retry timing.

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

The SPI does not define close ordering across providers or orchestrated
shutdown behavior.

## Failure behavior

Failures are represented through `ProviderOperationResult.Failure` with
`DataLoomError`. The SPI does not expose provider-specific exception contracts.

## Runtime orchestration status

Provider lifecycle orchestration belongs to a later runtime issue and is not
part of the DL-007 contract surface.
