# DL-040 circuit state-machine validation checkpoint

## Scope

This checkpoint records the first deterministic circuit-breaker state-machine
slice delivered after retry timeout separation.

Implemented in this slice:

- explicit global, provider, provider-operation, tenant-provider-operation, and
  workflow circuit scopes;
- immutable `CLOSED`, `OPEN`, and `HALF_OPEN` durable state contracts;
- strict phase invariants and bounded operational evidence;
- atomic versioned compare-and-set persistence SPI;
- deterministic failure threshold and inclusive failure-window behavior;
- open-deadline rejection evidence;
- one controlled half-open probe per generation;
- successful-probe close and failed-probe reopen;
- stale-probe rejection;
- fail-closed persisted clock-regression handling;
- bounded compare-and-set contention retries; and
- restart recovery from a shared persisted state record.

## Focused evidence

The one-time evidence lane completed successfully before the clean final head:

- exact `dataloom-api`, `dataloom-runtime`, and `dataloom-apple` JVM/KLib ABI
  generation;
- API and runtime JVM tests;
- external-consumer compilation for JVM, `iosArm64`, `iosSimulatorArm64`, and
  `iosX64`;
- Apple XCFramework assembly; and
- public ABI assertions for the scope, persistence, coordinator, permission,
  probe, and result contracts.

The temporary helper and workflow were removed, and the permanent pull-request
workflow was restored byte-for-byte before this checkpoint commit.

## Remaining V1 gates

This checkpoint does not claim the full circuit-breaker gate is complete.
Production Android Room and KMP iOS state stores, retry/provider execution
integration, manual administrative operations, observability, multi-process and
high-contention qualification, and Book 2 AC-FUNC-004 evidence remain required.
