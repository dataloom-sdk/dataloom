# DataLoom Apple Testing (DL-036)

## Strategy

Shared `commonTest` files are reused across all configured Apple targets.
Apple-specific tests belong in `iosTest` only when they cover Apple-only
behavior, which is not the case in DL-036.

## iOS Simulator Target

Shared tests are run on `iosSimulatorArm64`:

```bash
./gradlew :dataloom-api:iosSimulatorArm64Test
./gradlew :dataloom-core:iosSimulatorArm64Test
./gradlew :dataloom-runtime:iosSimulatorArm64Test
./gradlew :dataloom-testing:iosSimulatorArm64Test
```

These tasks run the same `commonTest` suite on the Apple-silicon iOS
simulator, validating that the shared Kotlin code behaves identically on
Kotlin/Native as it does on JVM.

## Test Scope (DL-036)

The following behaviors are validated by the shared test suite running on iOS:

| Test Area | Coverage |
|---|---|
| dataloom-api compilation | All `commonTest` tests |
| dataloom-core compilation | All `commonTest` tests |
| dataloom-runtime compilation | All `commonTest` tests |
| DataLoom facade construction | `DataLoomRuntimeModuleTest` and related |
| Provider lifecycle | `ProviderLifecycleCoordinatorTest` |
| Outbound synchronization | `OutboundPushPipelineConfiguration` tests |
| Inbound synchronization | `InboundPullPipelineConfiguration` tests |
| Bidirectional synchronization | `BidirectionalSynchronizationPipeline` tests |
| Retry behavior | `SynchronizationRetryEvaluatorTest` and related |
| Queue processing | `DurableQueueExecutionProcessor` tests |
| Event delivery | `SynchronizationEventDispatcherTest` |
| Connectivity preflight | `SynchronizationConnectivityPreflightTest` |
| Queue submission | `DefaultDataLoomQueueSubmission` tests |

## Test Requirements

All Apple-platform tests must remain:

- **Deterministic** — no random identifiers, no system clock
- **Isolated** — no shared mutable state between tests
- **Repeatable** — same result on every run
- **Independent of production services** — no real network, database, scheduler,
  keychain, or background task APIs

The DL-035 testing utilities (`InMemoryQueueProvider`, `FixedDataLoomClock`,
`SequenceIdentifierGenerator`, etc.) are used in iOS simulator tests via the
`dataloom-testing` module.

## Host Restriction

iOS simulator tests run **only on macOS** (see [apple-targets.md](apple-targets.md)).
On Linux, the convention plugin does not declare iOS targets, so no iOS test
tasks exist.

## macOS CI

The macOS CI job (`.github/workflows/apple-validation.yml`) runs all Apple
tests automatically on pull requests and pushes to `main`.

## What Tests Do NOT Use

- Real network connections
- Real databases (SQLite, Core Data)
- Real schedulers (BGTaskScheduler)
- Keychain APIs
- Background task APIs
- Random identifiers (use `SequenceIdentifierGenerator`)
- System clock (use `FixedDataLoomClock` or `MutableDataLoomClock`)
- Production credentials or personal data
