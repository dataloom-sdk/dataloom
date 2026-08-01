# DL-040 provider-protected strategy execution checkpoint

## Decision

This checkpoint accepts the bounded implementation only after one clean head
passes focused common-code, external-consumer, ABI, Android, and Apple
qualification. It does not close DL-040 or change the V1 NO-GO decision.

## Accepted scope

The slice introduces additive plan-aware provider protection for the existing
built-in strategy coordinator.

Accepted behavior:

1. Historical strategy synchronization remains unchanged through an identity
   provider boundary.
2. The existing evaluator, immutable strategy plan, lifecycle admission,
   capability-aware provider resolution, trigger/input checks, and executors are
   reused.
3. Network-only protects only the resolved transport provider and does not
   resolve or touch storage or queue.
4. Remote-first protects the exact resolved storage and transport providers.
5. Remote-first local fallback preserves `StrategyLocalFallbackProvider`
   capability through a dedicated bridge and independently configured circuit
   boundary.
6. Every protected provider operation is invoked at most once after permission.
7. Ordered bounded provider and post-execution circuit-recording evidence is
   returned with the exact existing strategy result.
8. Provider success followed by unconfirmed circuit recording is fail-closed
   and is not automatically replayed.
9. Missing protection or operation/provider scope mismatch rejects before
   provider invocation.
10. A fresh evidence collector is allocated per call.
11. Construction and plan preparation perform no provider operation, state-store
    access, timeout execution, identifier generation, I/O, or coroutine launch.
12. Cancellation and unexpected programming exceptions propagate unchanged.

## Stable identities

Storage and transport retain their existing stable operation identities.
Application-owned local fallback uses:

```text
strategy.evaluate-local-fallback
```

Names, not enum ordinals, are the operational and persistence boundary.

## Evidence restrictions

`ProviderProtectionOperationEvidence` may contain stable provider/operation
identities, invocation category, canonical error, bounded retry time, rejection
reason, and circuit-recording result.

It must not contain:

- provider return values;
- domain payloads;
- credentials, tokens, or authorization headers;
- checkpoint contents;
- exception text or stack traces;
- provider/store/classifier instances;
- arbitrary metadata.

## Required qualification

The candidate must pass on one unchanged final head:

- runtime JVM tests;
- runtime iOS Simulator tests;
- external consumer compilation for JVM, `iosArm64`,
  `iosSimulatorArm64`, and `iosX64`;
- exact JVM and Kotlin/Native ABI generation and checks;
- public ABI-boundary validation;
- Apple release XCFramework assembly;
- Pull Request Validation;
- Android Validation, including the Room managed-device test;
- Apple Platform Validation, exported-header audit, and Swift smoke compilation;
- zero unresolved review threads.

## Explicitly not accepted by this checkpoint

- complete offline-first, cache-first, hybrid, or adaptive execution;
- durable queued strategy-plan persistence and restart adoption;
- protocol-specific connection/request/idle timeout adapters;
- production KMP iOS retry/circuit/deadline state;
- authorized manual retry, reclassification, or circuit administration;
- complete retry/strategy observability;
- multi-process, restart, contention, failure-injection, and Book 2
  `AC-FUNC-004` evidence;
- complete V1 release readiness.
