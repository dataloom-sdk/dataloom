# Provider Registry (DL-018)

[API reference index](./README.md)

> **Status:** Available immutable registry and lookup foundation. Selection is
> explicit through provider bindings; no plugin discovery is implied.

**Package:** `io.dataloom.core.provider`

## Overview

`ProviderRegistry` is an immutable registry of application-supplied
`DataLoomProvider` instances. It provides deterministic lookup by `ProviderId`
and `ProviderType`, and preserves registration order for deterministic lifecycle
orchestration.

The registry is a pure data structure. It performs no provider initialization,
no provider shutdown, and no automatic service discovery. It does not use global
state, reflection, or service loaders.

---

## Registry purpose

`ProviderRegistry` answers three questions:

1. **All providers**: what is the complete ordered list of registered providers?
2. **Lookup by ID**: is there a provider with a given `ProviderId`?
3. **Lookup by type**: which providers satisfy a given `ProviderType`?

`ProviderRegistry` does not answer:
- Which provider should be used when multiple providers share the same type?
- What is the initialization status of a provider?
- Are providers healthy?

Provider-selection policy is deferred. DL-018 does not choose a default
provider when multiple providers share the same type.

---

## Duplicate ProviderId handling

Each `ProviderId` must be unique within one registry. Attempting to construct
a registry with duplicate `ProviderId` values throws `IllegalArgumentException`
at construction time. The exception message identifies the duplicate IDs.

```kotlin
// OK — unique IDs
val registry = ProviderRegistry(listOf(storageProvider, transportProvider))

// Throws IllegalArgumentException — duplicate IDs
val registry = ProviderRegistry(listOf(storageA, storageB)) // storageA.id == storageB.id
```

---

## Multiple providers per ProviderType

`ProviderType` values are not unique within a registry. Multiple providers of
the same type may be registered. This is required for multi-database, multi-endpoint,
or multi-queue configurations.

```kotlin
val registry = ProviderRegistry(
    listOf(primaryStorageProvider, secondaryStorageProvider, transportProvider)
)

val storageProviders = registry.findByType(ProviderType.STORAGE)
// Returns both: [primaryStorageProvider, secondaryStorageProvider]
```

---

## Deterministic registration order

Providers are stored in the order they appear in the supplied list. The
`providers` property and `findByType()` results always reflect this original
registration order. Order is never derived from:

- `ProviderType` enum ordinal
- provider ID sorting
- class name
- hash-map iteration order
- platform service discovery

Registration order is the source of deterministic lifecycle ordering for
`ProviderLifecycleCoordinator`.

---

## Provider lookup

### Lookup by ProviderId

```kotlin
val provider: DataLoomProvider? = registry.findById(ProviderId("storage.primary"))
```

Returns the provider with that ID, or `null` when no provider is registered
with the given ID.

### Lookup by ProviderType

```kotlin
val providers: List<DataLoomProvider> = registry.findByType(ProviderType.STORAGE)
```

Returns all providers registered with the given type, in registration order.
Returns an empty list when no provider with the given type is registered.

### All providers

```kotlin
val all: List<DataLoomProvider> = registry.providers
```

Returns all registered providers in registration order.

---

## Defensive copying

The `ProviderRegistry` defensively copies the supplied `providers` list at
construction time. Mutations to the original list after construction do not
affect the registry.

```kotlin
val source = mutableListOf<DataLoomProvider>(storageProvider)
val registry = ProviderRegistry(source)

source.add(transportProvider)   // has no effect on registry
assertEquals(1, registry.size)  // still 1
```

Collections returned by `providers` and `findByType()` are typed as read-only
`List<T>`. The underlying defensive copy ensures registry internal state is
protected from external mutation.

---

## Provider registry versus provider selection policy

`ProviderRegistry` provides lookup — it does not implement selection policy.

When multiple providers are registered with the same `ProviderType`,
`findByType()` returns all matching providers. The caller is responsible for
deciding which provider to use. DataLoom does not automatically select a
default provider.

---

## No lifecycle operations

Registry construction does not initialize, shut down, or inspect providers.
The registry is a pure data container. Provider lifecycle management is
delegated to `ProviderLifecycleCoordinator`.

---

## No global state

`ProviderRegistry` does not use process-wide singletons, companion-object
mutable fields, or service locators. Applications create registry instances
explicitly and pass them to `ProviderLifecycleCoordinator` as required.

There is no global provider registry.

---

## KMP compatibility

`ProviderRegistry` uses Kotlin standard-library types only. It does not depend
on Android APIs, JVM-only types, or third-party libraries.

---

## Security restrictions

Do not place credentials, tokens, encryption keys, or personal data in provider
metadata or descriptors. `ProviderDescriptor.metadata` is for operational
configuration metadata only.

---

## Example

```kotlin
val registry = ProviderRegistry(
    listOf(
        storageProvider,
        transportProvider,
        schedulerProvider,
        queueProvider,
    )
)

// Lookup by ID
val storage = registry.findById(ProviderId("storage.sqlite"))

// Lookup by type
val transports = registry.findByType(ProviderType.TRANSPORT)

// All providers in registration order
val all = registry.providers
```

---

## Synchronization-orchestration boundary

`ProviderRegistry` does not implement or trigger synchronization orchestration,
queue processing, retry execution, or any other DataLoom runtime behavior.
Provider lifecycle management is delegated to `ProviderLifecycleCoordinator`.
The current synchronization runtime consumes this registry through explicit
provider bindings and `SynchronizationProviderResolver`.
