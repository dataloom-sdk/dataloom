# DataStore storage provider

> **Audience:** Android developers integrating DataLoom with small key-value synchronization data  
> **Purpose:** Explain the Preferences DataStore-backed `StorageProvider`, its fit,
> schema, limits, and integration guidance  
> **Status:** Reference implementation; Android-only; not a general large-scale
> synchronization storage layer

[← Android overview](README.md) ·
[Room queue provider](room-queue-provider.md) ·
[Security and R8](security-and-r8.md)

## Use-case fit

`DataStoreStorageProvider` is the right choice when the data being synchronized
is **small, bounded, and key-value in nature**:

| Suitable | Not suitable |
|---|---|
| User settings (`darkMode`, `language`) | Large change-set streams |
| Feature flags | Relational or structured entity graphs |
| Small cached preferences | High-frequency writes |
| Boolean / string / numeric configuration | Data requiring SQL queries or joins |

For general-purpose, large-scale, or relationally structured synchronization
data, use the Room-backed provider in `dataloom-queue-room`.
See [GitHub issues #209 and #215](https://github.com/dataloom-sdk/dataloom/issues/209) for that work.

## Module and setup

```kotlin
// app/build.gradle.kts — optional module, not part of core DataLoom
implementation(project(":dataloom-storage-datastore"))
```

Create a Preferences DataStore instance and pass it to `DataStoreStorageProvider`:

```kotlin
// In your DI / application class
val settingsDataStore: DataStore<Preferences> = context.createDataStore(
    fileName = "dataloom_sync.preferences_pb",
)

val storageProvider = DataStoreStorageProvider(dataStore = settingsDataStore)
```

The DataStore instance is owned and lifecycle-managed by the host application.
`DataStoreStorageProvider` does not create, open, or close the DataStore.

## Enqueuing outbound changes

The host application enqueues outbound changes via the `enqueueOutboundChanges`
helper:

```kotlin
val changeSet = ChangeSet(
    id = ChangeSetId("settings-batch-001"),
    events = listOf(
        ChangeEvent(
            id = ChangeEventId("ev-darkMode-001"),
            entity = EntityReference(EntityType("Setting"), EntityId("darkMode")),
            operation = ChangeOperation.UPDATE,
        ),
    ),
)
val result = storageProvider.enqueueOutboundChanges(changeSet)
```

`enqueueOutboundChanges` is not part of the `StorageProvider` contract. It is
provided as a convenience for the application to add outbound work to the queue.
The DataLoom runtime reads from the queue via `readOutboundChanges`.

## Outbound capacity limit

At most **256 pending outbound events** may be stored at one time.

Attempting to enqueue events that would exceed this limit returns
`ProviderOperationResult.Failure` with error code
`DATASTORE_OUTBOUND_LIMIT_EXCEEDED`. Silent degradation does not occur.

This limit exists because Preferences DataStore is designed for small, bounded
data. If your application generates more than 256 concurrent pending events,
use `dataloom-queue-room` instead.

## DataStore schema

All DataLoom-managed entries share a `dl.` prefix. The host application must
not write to these prefixed keys directly.

| Purpose | DataStore key | Value format |
|---|---|---|
| Outbound pending index | `dl.out.pending` | Newline-separated `changeSetId/eventId` entries |
| Outbound event record | `dl.out.ev.<eventId>` | Pipe-delimited compact record |
| Inbound applied record | `dl.in.<entityType>.<entityId>` | Pipe-delimited compact record |
| Checkpoint | `dl.ckpt.<checkpointKey>` | Opaque checkpoint token string |

### Compact event record format

```
v1|<changeSetId>|<eventId>|<entityType>|<entityId>|<entityVersion|NONE>|<operation>|<contentType|NONE>|<hexPayload|NONE>
```

Payload bytes are stored as lowercase hexadecimal. The literal string `NONE`
represents an absent optional field.

**Restriction**: change-set IDs, event IDs, entity types, entity IDs, entity
versions, operation names, and content-type strings must not contain the `|`
character or newline characters. Payload bytes (hex-encoded) are not subject
to this restriction.

## Inbound apply semantics

`applyInboundChanges` stores the most recent inbound event **per entity**
(keyed by entity type and entity ID). If multiple events in a change set target
the same entity, only the last event for that entity is retained. This matches
the DataStore key-value model and is suitable for settings, flags, and
preference-like data where only the current value matters.

## Acknowledgement semantics

| Status | Effect |
|---|---|
| `ACCEPTED` | Removed from the pending index; event record deleted |
| `REJECTED` | Removed from the pending index; event record deleted |
| `RETRY` | Retained in the pending index for the next `readOutboundChanges` |

Events not included in an acknowledgement are retained in the pending queue.

## Thread safety

All DataStore operations are suspend functions. DataStore serialises concurrent
writes internally. No internal dispatcher is applied by this provider; the
caller is responsible for coroutine-context management.

## Error codes

| Error code | When emitted |
|---|---|
| `DATASTORE_OUTBOUND_LIMIT_EXCEEDED` | `enqueueOutboundChanges` called when adding the events would exceed `MAX_OUTBOUND_EVENTS` (256) |
| `DATASTORE_IO_FAILURE` | DataStore throws `IOException` during any operation |

Raw DataStore exceptions do not cross the public API. All failures are returned
as `ProviderOperationResult.Failure` with a canonical `DataLoomError`.

## Dependency scope

`dataloom-storage-datastore` is **opt-in**. It is not a transitive dependency of
`dataloom-core`, `dataloom-api`, or `dataloom-runtime`. Adding it to an
application does not change the behaviour of any other DataLoom module.

The only new dependency introduced is `androidx.datastore:datastore-preferences`,
scoped entirely to this module.
