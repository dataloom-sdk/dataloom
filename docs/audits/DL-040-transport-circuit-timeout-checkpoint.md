# DL-040 Transport Circuit and Timeout Checkpoint

## Decision

Transport provider lifecycle, push, and pull may be protected by an independent
cooperative provider timeout and explicit durable circuit adapter while
preserving remote-operation and later circuit-recording evidence separately.

This checkpoint advances the transport-provider portion of FR-RETRY-007,
FR-RETRY-009, and FR-RETRY-010. It does not complete transport pipeline assembly,
DL-040, or DataLoom V1.

## Implemented boundary

- stable transport lifecycle/push/pull operation identities;
- provider and operation scope validation before state/provider access;
- cooperative provider timeout for initialize, health, close, push, and pull;
- push timeout classified as availability failure while retaining unknown replay
  safety;
- complete `CircuitBreakerExecutionResult` preservation;
- at-most-once provider invocation after permission;
- no automatic retry or replay after an executed push; and
- side-effect-free timeout/circuit construction.

## Push ambiguity rule

A timed-out push may have committed remotely. Its canonical error therefore has
`Recoverability.UNKNOWN`. The stable timeout code still opens transport
availability state, but the caller must reconcile or use an independently proven
idempotency contract before replay. The circuit adapter never hides that the
operation ran.

## Required evidence

The review branch must prove:

- exact stable operation identities;
- initialization success and accepted record evidence;
- open-circuit rejection before provider invocation;
- provider success plus failed record persistence remains `Executed`;
- zero timeout prevents delegate invocation and records transport unavailability;
- push timeout is unknown-recoverability and pull timeout is recoverable;
- provider and operation scope mismatch fail before store/provider access;
- caller cancellation propagates without circuit recording;
- runtime construction performs no provider/store/clock work;
- external consumers compile for JVM and all current iOS targets;
- exact JVM and Kotlin/Native ABI baselines are generated and checked;
- Apple XCFramework and public-boundary checks pass; and
- permanent JVM, Android, and Apple validation pass on one clean final head.

## Excluded from this slice

- direct pipeline and `DataLoomBuilder` transport assembly;
- protocol-specific connection/request/idle timeout enforcement;
- storage provider circuit/timeout adaptation;
- KMP iOS durable circuit state;
- observability and administrative operations; and
- multi-process, restart, contention, and full `AC-FUNC-004` qualification.
