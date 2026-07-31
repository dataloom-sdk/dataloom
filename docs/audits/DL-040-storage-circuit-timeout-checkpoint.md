# DL-040 Storage Circuit and Timeout Checkpoint

## Decision

Storage lifecycle, reads, mutations, acknowledgements, and checkpoints may be
protected by an independent cooperative provider timeout and explicit durable
circuit adapter while preserving operation and later circuit-recording evidence
separately.

This checkpoint advances the storage-provider portion of FR-RETRY-007,
FR-RETRY-009, and FR-RETRY-010. It does not complete storage pipeline assembly,
DL-040, or DataLoom V1.

## Implemented boundary

- stable storage lifecycle/read/mutation/checkpoint operation identities;
- provider and operation scope validation before state/provider access;
- cooperative provider timeout for every current storage operation;
- unknown-completion protection for apply, acknowledgement, and checkpoint
  writes;
- complete `CircuitBreakerExecutionResult` preservation;
- at-most-once provider invocation after permission;
- no automatic replay after an executed durable mutation; and
- side-effect-free timeout/circuit construction.

## Mutation ambiguity rule

A timed-out apply, acknowledgement, or checkpoint write may have committed. Its
canonical error therefore has `Recoverability.UNKNOWN`. The stable timeout code
still opens storage availability state, but the caller must reconcile or use an
independently proven idempotency contract before replay. The circuit adapter
never hides that an operation ran.

## Required evidence

The review branch must prove:

- exact stable operation identities;
- initialization success and accepted record evidence;
- open-circuit rejection before provider invocation;
- provider success plus failed record persistence remains `Executed`;
- zero timeout prevents delegate invocation and records storage unavailability;
- mutation timeouts are unknown-completion while read timeouts are recoverable;
- provider and operation scope mismatch fail before store/provider access;
- caller cancellation propagates without circuit recording;
- runtime construction performs no provider/store/clock work;
- external consumers compile for JVM and all current iOS targets;
- exact JVM and Kotlin/Native ABI baselines are generated and checked;
- Apple XCFramework and public-boundary checks pass; and
- permanent JVM, Android, and Apple validation pass on one clean final head.

## Excluded from this slice

- direct pipeline and `DataLoomBuilder` storage assembly;
- transport pipeline/builder assembly;
- KMP iOS durable circuit state;
- observability and administrative operations; and
- multi-process, restart, contention, and full `AC-FUNC-004` qualification.
