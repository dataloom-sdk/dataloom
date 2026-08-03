# DL-040 Apple retry-administration executor checkpoint

## Scope

This checkpoint records the production Apple queue executor for authorized
administrative retries and the durable queue snapshot migration that supports
atomic command receipts.

The implementation:

- migrates the Apple queue snapshot from the entry-only version 1 format to
  the entry-plus-receipt version 2 format while retaining strict version 1
  read compatibility;
- preserves the historical default queue file name so existing state upgrades
  in place;
- writes the requeued entry and immutable authorization receipt in one
  file replacement protected by the queue provider's process-shared lock;
- replays an identical receipt without applying a second queue mutation;
- rejects command identifier reuse with different immutable input,
  authorization evidence, or effective recoverability;
- validates terminal state, canonical failure evidence, protected-category
  reclassification, clock evidence, and the immutable workflow deadline;
- preserves retry attempts, retry budgets, workflow deadlines, request
  identity, execution context, and safe metadata; and
- propagates caller cancellation without converting it to a retry result.

## Safety boundaries

- Queue mutation and its command receipt share one crash-durable snapshot.
- Unconfirmed receipts are never pruned automatically.
- Snapshots remain bounded to 32 MiB, 10,000 queue entries, and 10,000
  administrative receipts.
- Manual retry does not increment an attempt, reset a retry budget, or extend
  an accepted workflow deadline.
- Payloads, credentials, headers, exception text, stack traces, file-system
  paths, and arbitrary new metadata are not persisted in receipts.
- Invalid UTF-8, malformed records, duplicate identifiers, unsupported
  versions, size/count exhaustion, and inconsistent durable evidence fail
  closed with canonical sanitized errors.

## Qualification evidence

The one-time same-repository macOS evidence run
`DL-040 Apple Retry Administration Executor Evidence #4` completed on source
head `303515ae763a9531cb129581cc21679bd520d6ac` and passed:

- runtime JVM tests;
- iOS Simulator runtime tests;
- external JVM and all supported Apple consumer compilation;
- exact runtime Kotlin/JVM and Kotlin/Native ABI generation and checking;
- runtime public-boundary validation;
- Apple Kotlin/Native ABI checking; and
- release XCFramework assembly.

The evidence lane committed the reviewed runtime ABI declarations and removed
itself in `3f627ef799ee289b3285cd16a7ede71de739ed74`.

The permanent Pull Request, Android, and Apple Platform validation workflows
remain the final merge gate for the documentation-complete head.

## Remaining DL-040 work

- builder/facade and operations API assembly;
- complete retry/circuit administration events, bounded metrics, structured
  logs, traces, and health integration;
- executable process-loss and relaunch evidence;
- App Group multi-process and higher-contention fault injection; and
- complete Book 2 `AC-FUNC-004` reference-flow qualification across mandatory
  platforms.

