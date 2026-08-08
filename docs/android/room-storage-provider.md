# Room storage provider

> **Audience:** Android developers integrating a generic `StorageProvider`  
> **Purpose:** Explain the Room-backed reference storage provider, schema, and
> checkpoint/acknowledgement behavior  
> **Status:** Implemented Android reference persistence for opaque change sets
> and checkpoints; not a complete cross-platform V1 storage story

[← Android overview](README.md) ·
[Storage provider contract](../api/storage-provider.md) ·
[Checkpoint contracts](../api/checkpoint-contracts.md)

The `dataloom-storage-room` module contains a production Room-backed reference
implementation of `StorageProvider` over a generic, application-agnostic
schema:

- `RoomStorageProvider` implements outbound reads, inbound apply, outbound
  acknowledgement, and checkpoint read/write.
- `DataLoomStorageDatabaseBuilder` constructs the Room database with schema
  export enabled and destructive migration disabled.
- Payload bytes remain opaque. The module stores `DataLoomPayload` content type
  and bytes without decrypting, decoding, or logging them.

## Module and setup

```kotlin
implementation(project(":dataloom-storage-room"))
```

```kotlin
val database = DataLoomStorageDatabaseBuilder.build(context)
val storageProvider = RoomStorageProvider(database)
```

Hold the database as an application-process singleton. The provider does not
close or own the database lifecycle. Published V1 coordinates are not available
yet.

## Schema

| Property | Current value |
|---|---|
| Default database name | `dataloom-storage.db` |
| Schema version | `1` |
| Schema export | Enabled |
| Committed schema | `dataloom-storage-room/schemas/io.dataloom.storage.room.internal.DataLoomStorageRoomDatabase/1.json` |
| Tables | `outbound_change_sets`, `outbound_change_events`, `inbound_change_sets`, `inbound_change_events`, `storage_checkpoints` |

Persisted enum-like values use stable names, never ordinals. Changing a stored
name is therefore a compatibility change.

## Outbound reads and batching

`readOutboundChanges` reads only one stored outbound change set per call,
preserving event order inside that change set. The `maxEvents` hint limits the
number of returned events from that one change set. `hasMore = true` means that
additional matching eligible events remain either in the same change set or a
later stored change set.

Eligible outbound events are those whose acknowledgement status is:

- absent (never acknowledged); or
- `RETRY`.

Accepted events remain stored for inspection but are not returned again.
Rejected events remain stored and inspectable but are also not returned again.

## Inbound apply and checkpoint timing

`applyInboundChanges` stores inbound change sets generically in Room. The
provider does not interpret business payloads or merge domain models. Replaying
the exact same inbound change set ID and event content is idempotent; replaying
the same change-set ID with different durable content fails with a canonical
state error.

`writeCheckpoint` simply persists the supplied opaque checkpoint. The provider
does not advance checkpoints automatically. The caller must still obey the
apply-before-advance rule:

```text
pull changes
    ↓
apply inbound changes successfully
    ↓
write next checkpoint
```

## Error boundary

Unexpected Room/SQLite failures are wrapped as canonical `DataLoomError`
instances. Raw storage exceptions, SQL text, payload bytes, and metadata values
do not cross the public API.

| Code | Meaning |
|---|---|
| `STORAGE_ROOM_DATABASE_FAILURE` | An unexpected storage database operation failed |
| `STORAGE_ROOM_STATE_CORRUPT` | Stored storage-provider state failed validation |
| `STORAGE_OUTBOUND_ACKNOWLEDGEMENT_TARGET_MISSING` | An acknowledgement referenced a missing outbound event |
| `STORAGE_INBOUND_CHANGESET_CONFLICT` | The same inbound change-set ID was replayed with different durable content |

## Migration policy

Destructive migration fallback is not enabled. Schema version 1 has no historic
migrations yet. When the schema advances beyond version 1, maintainers must add
and test explicit migrations and keep committed JSON schemas synchronized with
generated output.

## Security boundary

This module does not encrypt the Room database. It persists opaque payload
bytes, change metadata, acknowledgement status/error fields, and checkpoint
tokens exactly as supplied. Applications that require encrypted at-rest storage
must provide an encrypted `SupportSQLiteOpenHelper.Factory` through an
alternative database construction boundary or use a different storage-provider
implementation.

## Related documentation

- [Storage provider contract](../api/storage-provider.md)
- [Checkpoint contracts](../api/checkpoint-contracts.md)
- [Acknowledgement contracts](../api/acknowledgement-contracts.md)
- [Storage boundaries](../architecture/storage-boundaries.md)
