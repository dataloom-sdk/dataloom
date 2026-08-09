# SQLDelight Storage Provider (Reference)

[API reference index](./README.md)

> **Status:** Optional reference implementation module (`dataloom-storage-sqldelight`,
> JVM + iOS). Production `dataloom-model`, `dataloom-api`, `dataloom-core`, and
> `dataloom-runtime` remain free of SQLDelight dependencies. The Android driver
> lives in a separate `dataloom-storage-sqldelight-android` module — see
> [Android overview](../android/README.md) — because AGP 9.0+ does not allow
> the classic `com.android.library` plugin in the same module as
> `org.jetbrains.kotlin.multiplatform`.

## Purpose

`dataloom-storage-sqldelight` is an optional Kotlin Multiplatform reference
`StorageProvider` backed by SQLDelight.

Applications can choose:

- this SQLDelight reference provider;
- the Room reference provider where applicable; or
- a custom provider implementation.

Use exactly one implementation for a given `StorageProvider` role in a runtime
binding. Do not run both SQLDelight and Room providers for the same storage
role.

## What it implements

`SqlDelightStorageProvider` implements the full `StorageProvider` contract:

- `readOutboundChanges`
- `applyInboundChanges`
- `acknowledgeOutboundChanges`
- `readCheckpoint`
- `writeCheckpoint`

The implementation follows `StorageProvider` batching semantics (`maxEvents` /
`hasMore`) and checkpoint apply-before-advance usage expectations.

## Quickstart (Android)

Requires the separate `dataloom-storage-sqldelight-android` module (see
[Android overview](../android/README.md)) in addition to
`dataloom-storage-sqldelight`.

```kotlin
import io.dataloom.storage.sqldelight.android.createAndroidSqlDelightStorageDatabase

val database = createAndroidSqlDelightStorageDatabase(
    context = applicationContext,
    databaseName = "dataloom-storage.db",
)

val storageProvider = SqlDelightStorageProvider(database)
```

## Quickstart (iOS)

```kotlin
val database = createIosSqlDelightStorageDatabase(
    databaseName = "dataloom-storage.db",
)

val storageProvider = SqlDelightStorageProvider(database)
```

## Building a database handle for another platform driver

`SqlDelightStorageDatabase` hides the generated SQLDelight database type
behind an `internal` constructor so it never leaks into public API surfaces.
Platform-driver code outside this module (such as
`dataloom-storage-sqldelight-android`) constructs a handle from an
already-configured `app.cash.sqldelight.db.SqlDriver` instead:

```kotlin
val database = SqlDelightStorageDatabase.fromDriver(driver)
```

`fromDriver` does not run schema creation — the driver must already be
configured for `DataLoomStorageDatabase.Schema` before calling it.

## Optional outbound staging helper

The provider exposes `persistOutboundChanges(changeSet)` as a generic helper so
applications can write outbound change sets into the SQLDelight-backed store
without exposing SQLDelight APIs through their app-level synchronization code.

## Schema and migrations

The module ships SQLDelight schema definitions under `src/commonMain/sqldelight`
and follows SQLDelight's migration model (`*.sqm` versioned migrations) for
future schema evolution.

## Error behavior

Raw SQLDelight/SQLite exceptions are not returned directly through the public
provider API. Provider operations return `ProviderOperationResult.Failure` with
canonical `DataLoomError` values.
