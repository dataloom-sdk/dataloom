# File Storage Provider (`dataloom-storage-file`)

> **Audience:** Developers evaluating DataLoom, building demos, or integrating
> DataLoom for the first time  
> **Purpose:** Explain the file-backed reference `StorageProvider`, its
> on-disk format, durability model, and limitations  
> **Status:** Reference implementation — suitable for low-volume and
> getting-started use; **not** designed for high-throughput production datasets

## Overview

`dataloom-storage-file` provides a reference [`StorageProvider`](../api/storage-provider.md)
backed by plain files on the local filesystem. It requires **no database, no
ORM, and no third-party dependency** beyond `kotlinx.coroutines.core` and
standard Kotlin/JVM or Kotlin/Native file I/O APIs.

This makes it the simplest path to a working `StorageProvider` — ideal for
demos, tutorials, unit tests, and getting-started integrations where volume
and throughput are low.

> **Important — scalability expectation:** This provider serializes every
> operation through a single in-process mutex and rewrites the outbound index
> file on every acknowledgement. It is **not** designed for large change-set
> volumes or high-concurrency write workloads. For production datasets, prefer
> the Room provider (`dataloom-queue-room`) on Android or the SQLDelight
> provider on Kotlin Multiplatform.

## Targets

| Target | Status |
|--------|--------|
| JVM / Android | ✓ Supported |
| iOS (iosArm64, iosSimulatorArm64, iosX64) | ✓ Supported (host-gated) |

## Quickstart

### Gradle (KMP commonMain or single-platform)

```kotlin
// settings.gradle.kts — add the module
include(":dataloom-storage-file")

// build.gradle.kts (KMP)
kotlin {
    sourceSets {
        commonMain {
            dependencies {
                implementation(project(":dataloom-storage-file"))
            }
        }
    }
}

// build.gradle.kts (Android-only)
dependencies {
    implementation(project(":dataloom-storage-file"))
}
```

### Creating a provider

```kotlin
import io.dataloom.storage.file.FileStorageProvider

// Use an absolute, application-private directory.
// On Android, this would typically be:
//   context.filesDir.resolve("dataloom-storage").absolutePath
val storageProvider = FileStorageProvider(
    baseDir = "/data/user/0/com.example/files/dataloom-storage"
)
```

### Initializing

```kotlin
val initResult = storageProvider.initialize(ProviderInitializationContext())
if (initResult is ProviderOperationResult.Failure) {
    // Directory could not be created — check permissions and path.
}
```

### Storing outbound changes

`FileStorageProvider` exposes one extra method — `storeOutboundChangeSet` —
for the application to register outbound changes. Call this whenever the app
makes a domain change that should be pushed to the remote:

```kotlin
val changeSet = ChangeSet(
    id = ChangeSetId("cs-invoice-001"),
    events = listOf(
        ChangeEvent(
            id = ChangeEventId("evt-001"),
            entity = EntityReference(EntityType("invoice"), EntityId("inv-9999")),
            operation = ChangeOperation.CREATE,
            payload = DataLoomPayload(
                contentType = PayloadContentType("application/json"),
                bytes = """{"amount":99.99}""".encodeToByteArray(),
            ),
        ),
    ),
)

val result = storageProvider.storeOutboundChangeSet(changeSet)
```

### Reading, acknowledging, and applying

The `StorageProvider` contract methods work exactly as documented in
[`docs/api/storage-provider.md`](../api/storage-provider.md):

```kotlin
// Read outbound changes for the runtime to push.
val readResult = storageProvider.readOutboundChanges(
    OutboundChangeReadRequest(
        request = synchronizationRequest,
        maxEvents = 50,
    )
)

// After successful push, acknowledge the result.
val ackResult = storageProvider.acknowledgeOutboundChanges(
    OutboundChangeAcknowledgementRequest(synchronizationRequest, serverAcknowledgement)
)

// Apply inbound changes received from the remote.
val applyResult = storageProvider.applyInboundChanges(
    InboundChangeApplyRequest(synchronizationRequest, inboundChangeSet)
)

// Checkpoint — write only after all inbound changes are applied.
val writeResult = storageProvider.writeCheckpoint(
    CheckpointWriteRequest(synchronizationRequest, newCheckpoint)
)
```

## On-disk layout

```
{baseDir}/
  outbound/
    {eventId}.evt         — one file per pending outbound event
  outbound.idx            — ordered list of pending event IDs
  rejected/
    {eventId}.evt         — REJECTED events (kept for application inspection)
  inbound/
    {changeSetId}.batch   — one file per applied inbound batch
  checkpoint/
    {encodedKey}.chk      — one file per checkpoint key (URL-safe Base64 name)
```

## File formats

### Event file (`DATALOOM_EVT_V1`)

```
DATALOOM_EVT_V1
changeSetId=<changeSetId>
eventId=<eventId>
entityType=<entityType>
entityId=<entityId>
entityVersion=<entityVersion or empty if none>
operation=<CREATE|UPDATE|DELETE|MERGE|RESTORE>
payloadContentType=<contentType or empty if no payload>
payloadBase64=<standard Base64 payload bytes or empty if no payload>
meta.<key>=<value>        (zero or more metadata entries)
```

### Checkpoint file (`DATALOOM_CKPT_V1`)

```
DATALOOM_CKPT_V1
key=<checkpointKey>
token=<checkpointToken>
meta.<key>=<value>        (zero or more metadata entries)
```

### Outbound index (`DATALOOM_OUTBOUND_IDX_V1`)

```
DATALOOM_OUTBOUND_IDX_V1
<eventId1>
<eventId2>
...
```

The index is the source of truth for pending events. Files are written
before the index is updated so a crash mid-write leaves the index
unchanged. A missing or malformed event file listed in the index is
treated as a bounded corruption: it is skipped and the index is repaired
on the next write.

## Durability model

Every write follows the **write-temp-then-rename** idiom:

1. Write content to `{target}.tmp`.
2. Atomically rename `.tmp` over the target file.

On the JVM this uses `java.nio.file.Files.move(ATOMIC_MOVE)`. On iOS it
uses POSIX `fsync` followed by `rename(2)` with a parent-directory fsync.

This guarantees that a process crash mid-write cannot corrupt an
already-committed record. The worst outcome is an orphaned `.tmp` file
which is cleaned up automatically on the next write.

### Crash scenarios

| Scenario | Outcome |
|----------|---------|
| Crash during event-file write | `.tmp` file is left; index unchanged; event is not visible to next read |
| Crash after event-file write but before index update | Event file exists as an orphan; index unchanged; event is not returned on read |
| Crash during index write | `.tmp` file is left; index unchanged; next read sees the old index |
| Corrupt event file in index | Skipped; index is repaired on the next read with orphan removal |

## Thread safety

All operations are serialized by a single in-process `Mutex`. Multiple
coroutines calling the same `FileStorageProvider` instance concurrently
are safe. Concurrent access from **multiple processes** to the same
`baseDir` is **not** supported — use a database-backed provider if you
need multi-process access.

## Error handling

No raw filesystem path, exception message, `errno` value, or OS detail
is exposed through the public API. All errors map to a canonical
`DataLoomError` with an `STORAGE` category and a stable error code
prefixed `dataloom.storage.file.*`.

## Known limitations

- **Not suitable for large datasets.** The index is a flat file and grows
  linearly with the number of pending events. Each read scans the full
  index.
- **No multi-process safety.** Concurrent access from multiple OS
  processes to the same base directory is undefined behavior.
- **No compression.** Payload bytes are stored Base64-encoded, which
  increases on-disk size by ~33%.
- **No encryption.** Application payloads are stored in plain text on
  disk. Apply encryption at the payload layer before handing off to
  DataLoom if at-rest encryption is required.

## Relationship to other storage providers

| Provider | Backing | Suitable for |
|----------|---------|--------------|
| `FileStorageProvider` | Plain files | Demos, getting-started, low-volume |
| Room provider (Android) | SQLite via Room | Android production workloads |
| SQLDelight provider (KMP) | SQLite via SQLDelight | Cross-platform production workloads |
