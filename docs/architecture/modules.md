# DataLoom Module Architecture

This document describes the DataLoom module structure, responsibilities, and
dependency rules for current shared modules and planned platform modules.

Product positioning:

> DataLoom is an Android-first, Jetpack-style offline synchronization SDK with
> a Kotlin Multiplatform-ready shared core.

---

## Module Overview

DataLoom is currently organized into four shared library modules and one
build-infrastructure included build.

| Component | Type | Purpose |
|---|---|---|
| `dataloom-api` | Library module | Stable public contracts, models, and error types |
| `dataloom-core` | Library module | Internal platform-independent foundation |
| `dataloom-runtime` | Library module | Synchronization runtime and engine coordination |
| `dataloom-testing` | Library module | Testing utilities, fakes, and controlled providers |
| `build-logic` | Build infrastructure | Gradle convention plugins (not a published library) |

All four library modules use Kotlin Multiplatform with an initial JVM target.
Android-specific functionality is intentionally deferred to dedicated Android
integration modules.

---

## Module Responsibilities

### `dataloom-api`

Provides stable public contracts that host applications and production modules
depend on. Future content includes:

- Public API interfaces and models
- Canonical public error types
- Public configuration contracts
- Provider and plugin contracts

Rules:

- Must remain platform-independent.
- Must not depend on any other DataLoom implementation module.
- Must not expose third-party dependency types through its API.
- Must not contain runtime implementations.
- Must not depend on Android APIs.

---

### `dataloom-core`

Provides internal, platform-independent foundations shared across runtime
components. Future content includes:

- Internal utilities used by `dataloom-runtime`
- Shared internal models and helpers

Rules:

- May depend on `dataloom-api`.
- Must not depend on `dataloom-runtime`.
- Must not depend on `dataloom-testing`.
- Internal implementation details must not be exposed as public API.
- Must not depend on Android APIs.

---

### `dataloom-runtime`

Provides the synchronization runtime. Future content includes:

- Synchronization lifecycle coordination
- Workflow orchestration
- Engine coordination

Rules:

- May depend on `dataloom-api` and `dataloom-core`.
- Must not depend on `dataloom-testing`.
- Must not expose internal implementation types publicly.
- Must not depend on Android APIs.

---

### `dataloom-testing`

Provides testing utilities for consumers of DataLoom. Future content includes:

- Fake provider implementations
- Controlled clocks and schedulers
- Test fixtures and builders
- Failure-injection utilities

Rules:

- May depend on `dataloom-api` and `dataloom-core`.
- Must not be a dependency of any production module (`dataloom-runtime` or
  `dataloom-core` production source sets).
- Must not be included in runtime implementation dependencies.

---

### `build-logic`

A Gradle included build that provides reusable convention plugins for all
DataLoom library modules. It is build infrastructure and is not published as
a library.

Current convention plugins:

- `io.dataloom.kotlin.multiplatform-library` — configures Kotlin Multiplatform
  with a JVM target, Java toolchain 17, JVM bytecode target 17, common source
  sets, and reproducible archive output.

---

## Approved Dependency Direction

```
dataloom-api
  (no DataLoom dependencies)

dataloom-core
└── depends on dataloom-api

dataloom-runtime
├── depends on dataloom-api
└── depends on dataloom-core

dataloom-testing
├── depends on dataloom-api
└── depends on dataloom-core
```

---

## Prohibited Dependencies

The following dependencies are explicitly prohibited:

```
dataloom-api     → any DataLoom implementation module
dataloom-core    → dataloom-runtime
dataloom-core    → dataloom-testing
dataloom-runtime → dataloom-testing
production code  → dataloom-testing
```

Circular project dependencies are prohibited.

---

## Platform Strategy Summary

- Android is the primary reference and adoption platform.
- Shared contracts and runtime foundations use Kotlin Multiplatform where
  technically appropriate.
- Shared modules must not depend on Android APIs.
- Platform-specific behavior belongs in dedicated platform modules or provider
  interfaces.
- KMP compatibility must not delay the first complete Android vertical slice.
- New platform targets require an approved issue and compatibility testing.
- Support is not claimed until adapter and qualification tests exist.
- Prefer provider interfaces over `expect`/`actual` when interfaces provide a
  clearer extension boundary.

---

## Platform Independence Rules

- All common source sets (`commonMain`, `commonTest`) must remain
  platform-independent.
- Common source sets must not use Android APIs, JVM-specific APIs, or other
  platform-specific code.
- Platform-specific extensions belong in the appropriate platform source set
  (for example `jvmMain`).
- Do not add iOS, JavaScript, Wasm, Kotlin/Native, Compose, desktop
  application, or server application targets without an approved issue.

---

## Planned Android-First Modules (not implemented in this issue)

| Module | Planned responsibilities | Boundaries |
|---|---|---|
| `dataloom-android` | Android initialization, app/process lifecycle integration, connectivity integration, Android runtime configuration, diagnostics, and DI hooks where useful | Must not contain Room, Retrofit, or WorkManager implementations directly |
| `dataloom-workmanager` | Optional WorkManager scheduling, durable background execution, constraints mapping, worker/runtime integration, process restart recovery coordination | Optional Android integration artifact |
| `dataloom-room` | Optional Room-backed storage provider, queue/checkpoint persistence support, migration guidance | Room must not be mandatory for shared core/runtime |
| `dataloom-retrofit` | Optional Retrofit transport integration, request/response adaptation, DataLoom error mapping | Retrofit must not be mandatory for shared core/runtime |
| `sample-android` | Reference integration sample: offline-first flow, scheduling, providers, state observation, failure/retry demonstration | Reference app only, not a shared runtime dependency |

---

## Planned Future KMP Modules (roadmap only)

| Module | Planned responsibilities | Status |
|---|---|---|
| `dataloom-ktor` | Future multiplatform transport provider | Roadmap only |
| `dataloom-sqldelight` | Future multiplatform storage provider | Roadmap only |
| `dataloom-apple` | Future Apple lifecycle, connectivity, and scheduling integration | Roadmap only |
| `sample-kmp` | Future shared Android and iOS reference application | Roadmap only |

These modules are not part of the current release scope.

---

## Future Module Expansion

New modules may be introduced when explicitly approved through a GitHub issue.
Before adding a module:

1. Define its purpose and responsibility boundary.
2. Document allowed and prohibited dependencies.
3. Confirm it does not introduce circular dependencies.
4. Ensure it does not expose third-party library types through its public API.
5. Create the module using the approved `io.dataloom.kotlin.multiplatform-library`
   convention plugin or an appropriate successor.

Planned future modules are documented in
[Platform Strategy](./platform-strategy.md) and require dedicated approved
implementation issues.
