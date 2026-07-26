# DataLoom Platform Strategy (DL-006)

## Approved product statement

> DataLoom is an Android-first, Jetpack-style offline synchronization SDK with
> a Kotlin Multiplatform-ready shared core.

Android-first and Kotlin Multiplatform-ready are complementary:

- Android is the primary adoption and reference platform.
- Shared contracts and orchestration are designed for reuse across approved
  Kotlin Multiplatform targets.

DataLoom is not Android-only, not KMP-first, and not an official AndroidX
library.

## Platform strategy rules

1. Android is the primary reference and adoption platform.
2. Android receives the first complete production-quality vertical slice.
3. Shared contracts and orchestration remain in Kotlin Multiplatform modules
   where technically appropriate.
4. Android APIs must not appear in shared modules.
5. Platform behavior must be provided through dedicated platform modules or
   provider interfaces.
6. KMP support must influence architecture but must not delay the first usable
   Android release.
7. New platform targets require explicit issues and compatibility testing.
8. Platform support is not claimed until the relevant adapter and qualification
   tests exist.
9. Avoid `expect`/`actual` when a provider interface creates a clearer extension
   boundary.
10. Do not force platform-specific lifecycle or scheduling semantics into common
    APIs.

## Shared Kotlin Multiplatform modules

### `dataloom-api`

- Stable public models
- Public configuration contracts
- Runtime-facing contracts
- Provider and plugin interfaces
- Platform-independent error contracts

### `dataloom-core`

- Internal shared foundations
- Validation
- Shared policy calculations
- Shared retry calculations
- Shared state and lifecycle foundations
- Platform-independent utilities

### `dataloom-runtime`

- Synchronization orchestration
- Workflow coordination
- Queue coordination
- Retry coordination
- Policy evaluation
- Conflict workflow coordination
- Provider coordination

Platform-specific execution must be delegated through interfaces.

### `dataloom-testing`

- Shared fake providers
- Controlled clocks
- Controlled schedulers
- Test fixtures
- Failure injection
- Deterministic testing support

Production modules must not depend on `dataloom-testing`.

## Android-first modules (planned)

### `dataloom-android`

- Android initialization
- Application and process lifecycle integration
- Connectivity integration
- Android-specific runtime configuration
- Platform diagnostics
- Android-specific dependency-injection integration hooks where useful

It must not contain Room, Retrofit, or WorkManager implementations directly.

### `dataloom-workmanager`

- WorkManager-based scheduling
- Durable Android background execution
- Constraint mapping
- Worker-to-runtime integration
- Process restart recovery coordination

WorkManager remains an optional Android integration artifact.

### `dataloom-room`

- Optional Room-backed storage provider
- Queue and checkpoint persistence support where defined by later issues
- Database migration guidance

Room must not become mandatory for the DataLoom core.

### `dataloom-retrofit`

- Optional Retrofit transport integration
- Request and response adaptation
- Error mapping into DataLoom error contracts

Retrofit must not become mandatory for the DataLoom core.

### `sample-android`

- Reference Android integration
- Offline-first example
- WorkManager scheduling example
- Room and Retrofit provider example
- Synchronization state observation
- Failure and retry demonstration

## Future KMP modules (roadmap only)

- `dataloom-ktor`: Future multiplatform transport provider.
- `dataloom-sqldelight`: Future multiplatform storage provider.
- `dataloom-apple`: Future Apple lifecycle, connectivity, and scheduling integration.
- `sample-kmp`: Future shared Android and iOS reference application.

These modules are roadmap items and are not part of the current release.

## Jetpack-style principles for DataLoom

In DataLoom, Jetpack-style means:

- Clear and focused public APIs
- Kotlin-first design
- Coroutines and Flow for asynchronous state where appropriate
- Lifecycle-aware Android integrations
- Sensible defaults
- Optional advanced configuration
- Stable Maven artifacts
- Semantic versioning
- Strong documentation
- Sample applications
- Dedicated testing utilities
- Predictable error behavior
- Modular integrations
- Backward compatibility
- Deprecation before removal

DataLoom is not an official AndroidX library and must not use the
`androidx.*` namespace. The approved package namespace remains `io.dataloom`.

## Provider strategy

Provider interfaces are the primary abstraction for:

- Storage
- Transport
- Scheduling
- Connectivity
- Authentication
- Serialization
- Encryption
- Compression
- Logging
- Monitoring

Use platform modules when integration depends directly on platform lifecycle or
operating-system APIs.

Avoid `expect`/`actual` for infrastructure integrations when provider
interfaces are more extensible and testable.

## Initial delivery sequence

Shared public contracts
        ↓
Shared runtime foundations
        ↓
Provider contracts
        ↓
Queue and retry foundations
        ↓
Android platform adapter
        ↓
WorkManager scheduler integration
        ↓
Room storage provider
        ↓
Retrofit transport provider
        ↓
Android reference application
        ↓
Android developer preview
        ↓
Additional KMP integrations

## Initial Android vertical slice (roadmap target)

Android repository creates a synchronization request
        ↓
DataLoom validates the request
        ↓
Work is stored in a durable queue
        ↓
WorkManager schedules execution
        ↓
TransportProvider exchanges data
        ↓
StorageProvider persists synchronized state
        ↓
Retry policy handles recoverable failures
        ↓
Flow exposes synchronization state
        ↓
Pending work survives process restart

This flow is a roadmap target only and is not implemented in DL-006.

## Follow-up implementation issues (expected)

- Define provider SPI contracts
- Implement shared runtime lifecycle
- Implement durable queue contracts
- Add Android platform module ✅ (DL-037)
- Add WorkManager scheduler module ✅ (DL-037)
- Add Room provider ✅ (DL-037)
- Add Retrofit provider
- Create Android reference application
- Add future KMP platform integrations

## Android modules (DL-037)

Three independently consumable Android modules were introduced in DL-037:

| Module | Purpose |
|---|---|
| `dataloom-connectivity-android` | `ConnectivityProvider` backed by `ConnectivityManager` |
| `dataloom-scheduler-workmanager` | `SchedulerProvider` backed by WorkManager, plus CoroutineWorker bridge |
| `dataloom-queue-room` | `QueueProvider` backed by Room and SQLite |

Each module is optional. An application using only Room does not require
WorkManager or the connectivity module.

See [docs/android/README.md](../android/README.md) for integration details.
