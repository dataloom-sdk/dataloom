# SQLDelight Storage Provider (Reference)

[API reference index](./README.md)

> **Status:** Optional reference implementation module (`dataloom-storage-sqldelight`).
> Production `dataloom-model`, `dataloom-api`, `dataloom-core`, and
> `dataloom-runtime` remain free of SQLDelight dependencies.

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

```kotlin
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
