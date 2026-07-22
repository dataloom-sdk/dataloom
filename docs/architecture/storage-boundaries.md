# DataLoom Storage Boundaries

## Overview

This document defines the storage boundaries in DataLoom. It explains what
application code owns, what DataLoom owns, and how the two interact through the
`StorageProvider` SPI.

---

## Application Repository Ownership

The host application owns all domain data and domain queries. The application
repository is the authoritative API through which UI and business logic read
and modify domain data.

```text
UI / ViewModel
      ↓
Application Repository
      ↓
Room / SQLDelight / DataStore / custom storage
```

DataLoom does not replace, wrap, or proxy the application repository.
DataLoom does not provide general-purpose query methods such as `getCustomer`,
`observeOrders`, `readEntity`, `queryTable`, or `executeSql`.

---

## Application Domain Storage

The application continues to own:

- Domain models
- Database schemas
- DAOs and queries
- Repository implementations
- Mapping between domain entities and DataLoom payloads
- Transactions
- Encryption policy
- Secure-storage policy

DataLoom does not prescribe storage technology, schema structure, or query
patterns for domain data.

---

## DataLoom Synchronization Adapter

DataLoom interacts with application storage through the `StorageProvider` SPI:

```text
DataLoom Runtime
      ↓
StorageProvider
      ↓
Application-controlled storage adapter
      ↓
Room / SQLDelight / custom storage
```

`StorageProvider` is a **synchronization adapter**, not a general-purpose
database API. Its role is limited to:

- Reading outbound change sets for DataLoom to push to the remote
- Applying inbound change sets received from the remote

---

## Why DataLoom Does Not Provide Domain Queries

Domain queries belong to the host application for several reasons:

- The application owns entity models, schemas, and relationships.
- Query complexity, performance, and caching strategies are business concerns.
- Exposing general queries through DataLoom would couple the SDK to
  application-specific data shapes.
- Applications using Room, SQLDelight, DataStore, or custom storage each have
  their own idiomatic query patterns.

DataLoom remains neutral to these choices.

---

## Future DataLoom Infrastructure Storage

DataLoom may later introduce its own internal storage for SDK infrastructure
concerns, separate from application domain storage. Examples include:

- Durable synchronization queue persistence
- Retry records
- Idempotency records
- Checkpoint state

These are deferred to later issues and will not share schemas or DAOs with
application domain storage.

---

## Room Guidance

Room is appropriate for:

- Android application domain data
- Complex local queries
- Transactional application of remote changes
- Future durable DataLoom queue persistence (via a dedicated `dataloom-room`
  module, deferred)

A later `dataloom-room` module may provide reusable integration support for
implementing `StorageProvider` with Room.

Do not claim that a concrete Room provider exists. None is provided by DL-009.

---

## SQLDelight Guidance

SQLDelight is appropriate for:

- Kotlin Multiplatform persistence
- Android and Apple shared storage implementations
- Future KMP provider integration

Applications using SQLDelight may implement `StorageProvider` using
SQLDelight-generated queries inside the adapter, keeping SQLDelight types
outside the shared DataLoom public surface.

---

## DataStore Guidance

DataStore is appropriate primarily for:

- Small configuration values
- Lightweight checkpoints
- Runtime preferences
- Small opaque state

DataStore should not be presented as the default storage for large change sets
or durable synchronization queues. Applications may use DataStore inside a
`StorageProvider` adapter for appropriate use cases.

---

## SharedPreferences Guidance

SharedPreferences is supported only through application-defined or legacy
adapters where appropriate.

It should not be recommended for complex synchronization state or large change
sets. New applications should prefer DataStore for preference-like storage.

---

## Custom Storage

Applications may implement `StorageProvider` using any technology that
satisfies the contract. The `StorageProvider` SPI imposes no requirement on
the underlying storage engine.

---

## Encryption Boundary

`StorageProvider` receives and delivers opaque `DataLoomPayload` values.

- Payloads may be plaintext or already encrypted according to application
  policy.
- Storage-provider implementations must not assume plaintext.
- DataLoom core does not automatically encrypt or decrypt payloads.
- Encryption-provider contracts will be defined separately in a later issue.
- Applications remain responsible for key management and secure-storage policy.

Do not implement encryption in `StorageProvider` implementations unless the
application explicitly applies it before passing payloads to DataLoom.

---

## Boundary Summary

| Concern | Owner |
|---|---|
| Domain models, schemas, DAOs | Host application |
| Application queries (domain reads) | Host application |
| Application repository | Host application |
| Mapping domain entities to DataLoom payloads | Host application |
| Transactions, encryption, key management | Host application |
| Outbound change-read adapter | `StorageProvider` implementation |
| Inbound change-apply adapter | `StorageProvider` implementation |
| Synchronization orchestration | DataLoom runtime |
| Future DataLoom queue persistence | DataLoom (deferred) |

---

## Related Documentation

- [`StorageProvider` API](../api/storage-provider.md)
- [Provider SPI](../api/provider-spi.md)
- [Transport Boundaries](./transport-boundaries.md)
- [Platform Strategy](./platform-strategy.md)
- [Modules](./modules.md)
