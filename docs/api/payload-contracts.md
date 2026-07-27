# DataLoom Payload Contracts (DL-008)

[API reference index](./README.md)

> **Status:** Available opaque-payload contract. Streaming, chunking, resume,
> integrity, quota, and temporary-file safety are mandatory V1 asset gaps.

This document defines the payload-related public contracts introduced in
`dataloom-api` by DL-008.

## Why DataLoom uses opaque payloads

DataLoom is a synchronization coordinator, not an application data layer.
It orchestrates the transport, queuing, retry, and coordination of data
changes without depending on the host application's domain models, API
contracts, or serialization format.

To preserve this boundary, DataLoom represents synchronized content as an
**opaque immutable payload**. DataLoom may transport, queue, persist, and
coordinate a payload, but it must not inspect or interpret the application's
domain model.

## Application ownership

The host application owns:

- Domain models and their structure
- Serialization and deserialization of payload bytes
- Choice of wire format (JSON, Protocol Buffers, custom binary, etc.)
- Encryption and compression before passing bytes to DataLoom
- Secure storage policies for payload data

DataLoom does not serialize, deserialize, encode, decode, compress, or
inspect payload content. These responsibilities belong entirely to the host
application or a configured serializer provider (introduced in a later issue).

## PayloadContentType

`PayloadContentType` is an immutable value type that identifies the format
of a payload's byte content.

```kotlin
val contentType: PayloadContentType = PayloadContentType("application/json")
```

**Package:** `io.dataloom.api.payload`

**Rules:**

- Wraps a single non-blank `String`.
- Blank or whitespace-only values are rejected with `IllegalArgumentException`.
- The exact input value is preserved without normalization.
- `toString()` returns the underlying string value.
- Value-based equality and hash code are provided by the Kotlin value class.

**Ownership:** payload producer.

**Examples** (illustrative only — DataLoom does not validate MIME type syntax):

```kotlin
PayloadContentType("application/json")
PayloadContentType("application/octet-stream")
PayloadContentType("application/pdf")
PayloadContentType("image/jpeg")
PayloadContentType("application/vnd.example.entity")
```

These are examples only. Complete MIME-type parsing or validation is not
performed.

## DataLoomPayload

`DataLoomPayload` is an immutable opaque payload carrying application-defined
byte content alongside a content type.

**Package:** `io.dataloom.api.payload`

### Construction

```kotlin
val payload: DataLoomPayload = DataLoomPayload(
    contentType = PayloadContentType("application/json"),
    bytes = byteArrayOf(/* placeholder bytes */),
)
```

### Exposed members

| Member | Type | Description |
|---|---|---|
| `contentType` | `PayloadContentType` | Content type of the payload bytes. |
| `size` | `Int` | Number of bytes in the payload. |
| `isEmpty` | `Boolean` | `true` when the payload contains no bytes. |
| `copyBytes()` | `ByteArray` | Returns a defensive copy of the bytes. |

### Defensive byte-array copying

The constructor performs a **defensive copy** of the supplied byte array.
Mutating the source array after construction does not affect the payload.

`copyBytes()` returns a **new defensive copy** each time it is called.
Mutating the returned array does not affect the payload.

The internal byte array is never directly exposed through a public property.

```kotlin
val source: ByteArray = byteArrayOf(1, 2, 3)
val payload: DataLoomPayload = DataLoomPayload(PayloadContentType("application/octet-stream"), source)
source[0] = 99 // does not affect payload
val copy: ByteArray = payload.copyBytes()
copy[0] = 42  // does not affect payload
```

### Equality behavior

Two `DataLoomPayload` instances are equal when both:

- `contentType` values are equal, **and**
- byte content is equal (byte-by-byte comparison).

Array identity is not used for equality. Two separate arrays containing
the same bytes produce equal payloads.

Hash codes are derived from the content type and byte content.

### Safe `toString()`

`toString()` returns a diagnostic string containing the content type and byte
count only. Raw payload bytes are **never** included in the string
representation to prevent accidental logging of sensitive application data.

```
DataLoomPayload(contentType=application/json, size=42)
```

### Sensitive-data restrictions

Payload bytes may contain application-sensitive data including personal
information, credentials, or proprietary content. DataLoom does not
automatically log or inspect payload content.

Host applications are responsible for:

- Applying encryption before passing bytes to DataLoom.
- Applying decryption after receiving bytes from DataLoom.
- Controlling access to decrypted payload content.
- Defining and enforcing secure storage policies.

Content type is not treated as proof that payload content is safe or valid.

### Empty payloads

Empty byte arrays are accepted. `size` returns `0` and `isEmpty` returns
`true`.

```kotlin
val empty: DataLoomPayload = DataLoomPayload(PayloadContentType("application/json"), byteArrayOf())
check(empty.isEmpty) // true
check(empty.size == 0) // true
```

### No serialization

`DataLoomPayload` does not implement serialization. It does not include
`toJson`, `fromJson`, `encode`, `decode`, `serialize`, `deserialize`, or
similar helpers. Serialization-provider contracts will be introduced in a
separate issue.

## Serialization ownership

The host application or a configured serializer provider (future issue) is
responsible for:

- Converting domain models to `ByteArray` before constructing `DataLoomPayload`.
- Converting `ByteArray` from `DataLoomPayload.copyBytes()` back to domain
  models when consuming a payload.

## Encryption and compression ownership

The host application is responsible for:

- Encrypting payload bytes before passing them to DataLoom when required.
- Compressing payload bytes before passing them to DataLoom when required.
- Decrypting and decompressing bytes received from DataLoom.

DataLoom does not automatically apply, detect, or remove encryption or
compression.

## Related contracts

- [`EntityVersion`](./change-model.md#entityversion) — optional entity version
  value type used in [`EntityReference`](./change-model.md#entityreference).
- [`ChangeEvent`](./change-model.md#changeevent) — carries an optional
  `DataLoomPayload` for a change operation.

## Follow-up issues

- Serialization-provider contracts
- Storage-provider contracts
