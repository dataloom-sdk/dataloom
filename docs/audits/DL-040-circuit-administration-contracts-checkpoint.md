# DL-040 circuit-administration contracts checkpoint

## Scope

This checkpoint records the common authorized open/close/reset command model
and coordination foundation for DL-040. It does not claim production platform
execution, complete retry/circuit observability, AC-FUNC-004 acceptance, or V1
readiness.

## Implemented boundary

- bounded command, principal, authorization, and reason identities;
- immutable exact circuit scope, action, request time, and optional open
  deadline;
- host-owned authorizer, durable compare-and-set command store, and idempotent
  platform executor SPIs;
- durable authorization denial, policy rejection, execution rejection/failure,
  success, and versioned resulting circuit-state evidence;
- exact command replay and conflicting command-ID reuse detection;
- positive bounded contention and fail-closed clock-regression behavior;
- unconfirmed terminal-audit evidence after executor completion; and
- external JVM/Apple consumer construction and coordinator invocation.

## Safety invariants

1. The automatic circuit failure/access state machine is unchanged.
2. Authorization receives the complete immutable command.
3. `OPEN` requires a bounded future deadline; an elapsed deadline is durably
   rejected before executor invocation.
4. `CLOSE` and `RESET` cannot carry an open deadline.
5. No executor call occurs before durable authorization evidence exists.
6. Executor redelivery is idempotent by command ID and cannot return a state for
   another scope.
7. Authorization, command, result, and canonical failure evidence exclude
   payloads, secrets, exception text, stack traces, and arbitrary metadata.
8. Cancellation and unexpected exceptions propagate unchanged.

## Focused evidence

Common tests cover authorized open and terminal replay, exact request and
authorization forwarding, expired-open policy rejection, durable denial,
conflicting immutable input, unconfirmed final recording without duplicate
mutation, clock regression, action/deadline validation, bounded reason fields,
state/status invariants, and positive contention configuration.

The final pull-request head must additionally pass exact JVM/Kotlin-Native ABI,
external JVM and Apple consumer compilation, common/JVM/iOS tests, Android
validation, public-boundary validation, XCFramework assembly, header audit, and
Swift smoke compilation.

## Remaining DL-040 work

- production Android and Apple command-state persistence;
- atomic circuit mutation plus durable command receipts on both platforms;
- operations facade assembly;
- retry/circuit events, bounded metrics, logs, traces, health, exporters, and
  an operational read model;
- executable relaunch, multi-process, contention, and fault injection; and
- complete Book 2 AC-FUNC-004 qualification on mandatory consumer paths.
