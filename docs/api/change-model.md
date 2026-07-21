# DataLoom Change Model (DL-008)

This document defines the change-model public contracts introduced in
`dataloom-api` by DL-008.

These contracts represent synchronization change data only. Storage,
transport, conflict resolution, queueing, retry, and synchronization
execution are **not implemented** in this issue. Each of those concerns will
be addressed in dedicated follow-up issues.

## EntityVersion

`EntityVersion` is an immutable value type representing an application-defined
version of a domain entity.

**Package:** `io.dataloom.api.payload`

The version value may represent a revision number, ETag, sequence counter,
content hash, or any other version identifier meaningful to the host
application or remote system. DataLoom treats this value as opaque and does
not interpret, compare, or order version semantics.

```kotlin
val version: EntityVersion = EntityVersion("etag-abc123")
val revision: EntityVersion = EntityVersion("rev-42")
val hash: EntityVersion = EntityVersion("sha256-placeholder")
```

**Rules:**

- Wraps a single non-blank `String`.
- Blank or whitespace-only values are rejected with `IllegalArgumentException`.
- The exact input value is preserved without normalization.
- No automatic version generation is performed.
- `toString()` returns the underlying string value.
- Value-based equality and hash code are provided by the Kotlin value class.

**Ownership:** host application or remote system.

## EntityReference

`EntityReference` is an immutable model that identifies a domain entity by
type, ID, and optional version.

**Package:** `io.dataloom.api.change`

```kotlin
val ref: EntityReference = EntityReference(
    type = EntityType("invoice"),
    id = EntityId("entity-001"),
    version = EntityVersion("v1"), // optional
)
```

### Members

| Member | Type | Required | Description |
|---|---|---|---|
| `type` | `EntityType` | Yes | Type label for the domain entity. |
| `id` | `EntityId` | Yes | Unique identifier for the domain entity. |
| `version` | `EntityVersion?` | No | Optional entity version at the time of reference. |

`version` is `null` when no version context is available or applicable.

### Rules

- Entity type and ID are required.
- Version is optional.
- Equal references (same type, ID, and version) compare as equal.
- No entity loading, validation, or persistence is performed.
- No identifier or version generation is performed.

**Ownership of fields:**

| Field | Owner |
|---|---|
| `type` | Host application or domain model |
| `id` | Host application or domain model |
| `version` | Host application or remote system |

## ChangeEvent

`ChangeEvent` is an immutable record of a single synchronization change
intent for a domain entity.

**Package:** `io.dataloom.api.change`

```kotlin
val event: ChangeEvent = ChangeEvent(
    id = ChangeEventId("event-001"),
    entity = EntityReference(EntityType("invoice"), EntityId("entity-001")),
    operation = ChangeOperation.UPDATE,
    payload = DataLoomPayload(PayloadContentType("application/json"), byteArrayOf(/* placeholder */)),
    metadata = DataLoomMetadata.of(mapOf("source" to "host-app")),
)
```

### Members

| Member | Type | Required | Description |
|---|---|---|---|
| `id` | `ChangeEventId` | Yes | Unique identifier for this event. |
| `entity` | `EntityReference` | Yes | Reference to the affected domain entity. |
| `operation` | `ChangeOperation` | Yes | Semantic intent of the change. |
| `payload` | `DataLoomPayload?` | No | Optional opaque payload. Defaults to `null`. |
| `metadata` | `DataLoomMetadata` | No | Optional contextual attributes. Defaults to empty. |

### Payload optionality

Payload may be absent for operations such as `DELETE` where no content body
is required. Payload presence rules for individual operations are not
enforced by this contract; that enforcement is deferred to a later issue.

### Change-operation semantics

`ChangeOperation` describes semantic intent and is not bound to HTTP methods,
SQL operations, or any other protocol:

| Value | Meaning |
|---|---|
| `CREATE` | New entity or record is being introduced. |
| `UPDATE` | Existing entity or record is being modified. |
| `DELETE` | Existing entity or record is being removed. |
| `MERGE` | Multiple sources are being consolidated into a unified state. |
| `RESTORE` | A previously removed or superseded state is being restored. |

### Construction rules

- Construction performs no runtime action.
- No ID or timestamp is generated automatically.
- No synchronization is executed.
- No persistence or transport is initiated.

**Ownership:**

| Field | Owner |
|---|---|
| `id` | Change producer |
| `entity.type` | Host application or domain model |
| `entity.id` | Host application or domain model |
| `entity.version` | Host application or remote system |
| `operation` | Change producer |
| `payload` | Host application or serializer provider |
| `metadata` | Change producer or integration boundary |

## ChangeSet

`ChangeSet` is an immutable ordered collection of `ChangeEvent` instances
representing a logical unit of synchronization work.

**Package:** `io.dataloom.api.change`

```kotlin
val changeSet: ChangeSet = ChangeSet(
    id = ChangeSetId("changeset-001"),
    events = listOf(event1, event2),
    metadata = DataLoomMetadata.of(mapOf("channel" to "manual")),
)
```

### Members

| Member | Type | Required | Description |
|---|---|---|---|
| `id` | `ChangeSetId` | Yes | Unique identifier for this change set. |
| `events` | `List<ChangeEvent>` | Yes | Ordered read-only list of events. |
| `metadata` | `DataLoomMetadata` | No | Optional contextual attributes. Defaults to empty. |

### Event ordering

Events preserve the order in which they were supplied. The read-only
`events` collection reflects the original declared order. DataLoom does
not automatically reorder, split, or merge events.

### Empty-collection rejection

An empty event list is rejected at construction time with
`IllegalArgumentException`. An empty synchronization response must not be
represented by an invalid empty change set. Empty-response semantics will be
defined in a later transport or synchronization-result issue.

### Immutability guarantees

The supplied event list is **defensively copied** on construction. Mutating a
caller-supplied mutable list after construction does not affect the change
set.

```kotlin
val events: MutableList<ChangeEvent> = mutableListOf(event1)
val changeSet: ChangeSet = ChangeSet(ChangeSetId("set-001"), events)
events.add(event2) // does not affect changeSet
check(changeSet.events.size == 1) // true
```

The exposed `events` property is read-only.

### Construction rules

- Construction performs no runtime action.
- No ID is generated automatically.
- No synchronization is executed.
- No splitting or merging of events is performed.
- No retry or conflict behavior is applied.

**Ownership:**

| Field | Owner |
|---|---|
| `id` | Change-set producer |
| `events` | Change producers |
| `metadata` | Change-set producer or integration boundary |

## Identifier ownership summary

| Identifier | Owner |
|---|---|
| `ChangeEventId` | Change producer |
| `ChangeSetId` | Change-set producer |
| `EntityId` | Host application or domain model |
| `EntityType` | Host application or domain model |
| `EntityVersion` | Host application or remote system |

## Metadata usage

`DataLoomMetadata` is an optional bag of string key–value pairs attached to
`ChangeEvent` and `ChangeSet`. It carries contextual attributes such as
source system labels, request channel identifiers, or integration tags.

Do not place credentials, tokens, encryption keys, personal data, or full
payload content in metadata. See
[Foundational Contracts](./foundational-contracts.md) for metadata rules.

## Not implemented in this issue

The following concerns are intentionally deferred to future issues:

- Storage-provider contracts
- Synchronization-result contracts
- Conflict models and conflict resolution
- Durable queue models
- Retry models
- Runtime execution
- Scheduler integration
- Serialization-provider contracts

## Follow-up issues

```
Implement storage-provider contracts
Implement synchronization-result contracts
Implement scheduler and connectivity-provider contracts
Implement durable queue models
Implement conflict contracts
Implement serialization-provider contracts
```

## Related contracts

- [`PayloadContentType`](./payload-contracts.md#payloadcontenttype)
- [`DataLoomPayload`](./payload-contracts.md#dataLoomPayload)
- [`ChangeOperation`](./foundational-contracts.md#change-operations)
