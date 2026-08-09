# SQLDelight storage provider — Android driver

> **Audience:** Android developers using the SQLDelight reference `StorageProvider`
> **Purpose:** Show how to wire the Android SQLite driver for `dataloom-storage-sqldelight`
> **Status:** Reference implementation for source checkout usage; not a claim that other storage references are unavailable

[← Android overview](README.md) ·
[SQLDelight storage provider (shared contract and iOS/JVM quickstarts)](../api/sqldelight-storage-provider.md)

`dataloom-storage-sqldelight-android` is an **optional**, independently
consumable module that supplies the `AndroidSqliteDriver` wiring for the
shared `dataloom-storage-sqldelight` reference `StorageProvider` (JVM + iOS,
Kotlin Multiplatform).

## Why this is a separate module

`dataloom-storage-sqldelight` targets Kotlin Multiplatform (JVM + iOS). AGP
9.0+ does not allow the classic `com.android.library` plugin in the same
module as `org.jetbrains.kotlin.multiplatform`, so the Android driver lives
here instead — a plain `com.android.library` module with no Kotlin
Multiplatform involvement, matching `dataloom-connectivity-android` and
`dataloom-storage-datastore`.

## Module

```kotlin
implementation(project(":dataloom-storage-sqldelight"))
implementation(project(":dataloom-storage-sqldelight-android"))
```

Published V1 Maven coordinates are not available yet.

## Quickstart

```kotlin
import io.dataloom.storage.sqldelight.SqlDelightStorageProvider
import io.dataloom.storage.sqldelight.android.createAndroidSqlDelightStorageDatabase

val database = createAndroidSqlDelightStorageDatabase(
    context = applicationContext,
    databaseName = "dataloom-storage.db",
)

val storageProvider = SqlDelightStorageProvider(database)
```

See the [shared SQLDelight storage provider doc](../api/sqldelight-storage-provider.md)
for the full `StorageProvider` contract, schema/migration model, and error
behavior — this module only supplies the Android driver.
