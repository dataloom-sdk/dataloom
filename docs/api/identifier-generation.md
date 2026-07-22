# DataLoom Identifier Generation (DL-017)

**Package:** `io.dataloom.api.identifier`

## Overview

`IdentifierGenerator<T>` provides a platform-independent abstraction for
producing strongly typed identifiers. Runtime components use it when they need
to create a new identifier for a queue entry, lease, synchronization event,
conflict, or other runtime-owned value.

---

## `IdentifierGenerator<T>`

**Type:** `interface`

```kotlin
public interface IdentifierGenerator<T> {
    public fun generate(): T
}
```

### `generate()`

Produces a new identifier candidate.

- Returns a value of type `T` on each call.
- Does not perform I/O, access the filesystem, database, or network.
- Does not sleep or schedule work.
- Does not expose platform-specific types.

---

## Strongly typed identifiers

The type parameter `T` constrains the generator to a specific DataLoom
identifier type. This prevents accidental interchange of identifier types at
compile time.

```kotlin
// Correct: generator produces the expected type
val generator: IdentifierGenerator<QueueEntryId> = ...
val id: QueueEntryId = generator.generate()

// Compile error: type mismatch caught at compile time
val wrong: QueueLeaseId = generator.generate() // won't compile
```

DataLoom identifiers are strongly typed value objects, not raw strings:

| Identifier | Type | Ownership |
|---|---|---|
| `QueueEntryId` | Value class wrapping `String` | DataLoom runtime |
| `QueueLeaseId` | Value class wrapping `String` | DataLoom runtime |
| `SynchronizationEventId` | Value class wrapping `String` | DataLoom runtime |
| `ConflictId` | Value class wrapping `String` | DataLoom runtime or host |

---

## Runtime-owned versus application-owned identifiers

Some DataLoom identifiers are owned by the DataLoom runtime. The runtime is
responsible for generating these values at the appropriate point in the
synchronization lifecycle:

- `QueueEntryId` — created when enqueuing a new work item
- `QueueLeaseId` — created when a consumer acquires a lease
- `SynchronizationEventId` — created when the runtime emits a lifecycle event
- `ConflictId` — created when a conflict is detected

Other identifiers are owned by the host application or provider:

- `ChangeEventId` — assigned by the change producer
- `ChangeSetId` — assigned by the change producer
- `EntityId` — assigned by the host application

Application-owned identifiers are passed into DataLoom models. The runtime
does not generate them.

---

## Uniqueness limitations

Uniqueness guarantees are implementation-defined. The `IdentifierGenerator<T>`
interface alone:

- Does not guarantee global uniqueness.
- Does not guarantee persistent uniqueness across restarts.
- Does not guarantee lexicographic ordering.
- Does not guarantee monotonically increasing values.

Production implementations must document their uniqueness properties.
Test utilities such as `SequenceIdentifierGenerator` and
`ConstantIdentifierGenerator` make no uniqueness guarantees.

---

## Security limitations

- Cryptographic randomness is not guaranteed by this interface.
- Identifier generation is not authentication.
- Identifier generation is not authorization.
- Generated identifiers must not contain credentials, access tokens,
  encryption keys, personal data, or device identifiers.
- Identifiers must not be treated as secrets or access tokens.
- Security-sensitive code must not assume that identifiers are unpredictable.

---

## Dependency-injection neutrality

`IdentifierGenerator<T>` must be injected into runtime components that require
identifier creation. It must not be accessed through a global singleton.

DataLoom does not depend on Hilt, Dagger, Koin, or any other
dependency-injection framework. Applications may construct and supply generators
using any approach.

```kotlin
val identifiers = RuntimeIdentifierGenerators(
    synchronizationEventIds = eventIdGenerator,
    queueEntryIds = queueEntryIdGenerator,
    queueLeaseIds = queueLeaseIdGenerator,
    conflictIds = conflictIdGenerator,
)
```

---

## Production-generation boundary

Production identifier implementations are deferred. Options under
consideration for future issues include:

```
UUID
ULID
Database-generated sequence
Application-controlled identifier
Platform-specific secure random source
```

These implementations will be introduced in dedicated future issues. Do not
introduce production-generation strategies in DL-017.

Test utilities in `dataloom-testing` provide `IdentifierGenerator<T>`
implementations for deterministic testing. See
[Clock and Identifier Test Utilities](../testing/clock-and-identifiers.md).
