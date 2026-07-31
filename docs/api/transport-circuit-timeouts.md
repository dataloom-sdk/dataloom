# Transport provider timeout and circuit boundary

[API reference index](./README.md)

> **Status:** Partial V1 subsystem. Transport lifecycle, push, and pull now have
> cooperative provider-timeout and explicit durable circuit adapters. Direct
> pipeline/builder assembly and protocol-specific connection/request/idle
> enforcement remain open.

## Stable operations

`TransportCircuitOperation` defines:

```text
transport.initialize
transport.health
transport.close
transport.push-changes
transport.pull-changes
```

Provider-bearing scopes must identify the protected transport provider.
Operation-bearing scopes must identify the exact operation. Global and workflow
scopes remain explicit choices. No scope is inferred.

## Provider timeout

`TimeoutEnforcingTransportProvider` applies the shared cooperative provider
boundary to lifecycle, push, and pull operations. `TransportProviderTimeoutRuntime`
assembles it from a `TransportProvider`, `DataLoomClock`, and `SchedulingDelay`.

This is not a protocol-specific connection, request, or idle timeout. A provider
that blocks without coroutine cancellation checkpoints requires a dedicated
platform/protocol adapter.

The canonical timeout code is:

```text
TRANSPORT_PROVIDER_TIMEOUT
```

A push timeout has `Recoverability.UNKNOWN`: the remote mutation may already
have committed. The timeout still contributes to transport circuit availability,
but it must not be automatically replayed merely because the caller did not
receive an acknowledgement. Pull and health timeouts are recoverable.

## Circuit adapter

`CircuitBreakerTransportOperationAdapter` acquires permission, invokes the exact
transport operation at most once, classifies its canonical result, records the
outcome, and returns the complete `CircuitBreakerExecutionResult`.

It deliberately does not implement `TransportProvider`. A plain provider result
cannot preserve both an already-executed push and a later circuit-state
persistence failure without losing replay-critical evidence.

`CircuitBreakerExecutionResult.Executed` always means the transport operation
ran once. Its `operationResult` and `recordResult` must be evaluated separately.
A successful remote operation followed by failed circuit recording is not a
provider failure and must not be repeated automatically.

## Timeout classification

`TransportCircuitBreakerFailureClassifier` treats the stable provider timeout as
an availability failure even when the timeout retains unknown recoverability for
remote-mutation safety. Other errors follow the default circuit classifier.
Authentication, authorization, validation, policy, conflict, security, and
other semantic failures remain non-circuit failures when the dependency
responded.

## Construction boundary

Timeout runtime and circuit adapter construction perform no provider call,
state-store access, clock read, timeout execution, identifier generation,
coroutine launch, or transport operation.

## Remaining work

- integrate exact transport evidence into push, pull, bidirectional, and strategy
  pipelines;
- expose explicit builder configuration;
- implement connection, request, and idle adapters for concrete transports;
- add durable KMP iOS circuit persistence;
- add events, metrics, logs, traces, redaction, and administration; and
- complete restart, contention, fault-injection, and `AC-FUNC-004`
  qualification.
