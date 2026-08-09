# DataLoom Transport Provider (DL-010, DL-011)

[API reference index](./README.md)

> **Status:** Available remote-transport adapter contract with one optional
> reference Ktor implementation in
> [`dataloom-transport-ktor`](./ktor-transport-provider.md). Authentication,
> streaming assets, and complete V1 policy remain separate work.

`dataloom-api` defines a platform-independent transport-provider SPI for moving
synchronization changes between DataLoom runtime coordination and
application-controlled remote integrations.

`dataloom-api` defines the transport SPI only. Concrete integrations remain
separate optional modules so the shared runtime does not take a mandatory
network-client dependency. The current repository includes optional reference
implementations for Ktor (documented in
[`Ktor transport provider`](./ktor-transport-provider.md)), JVM/Android
Retrofit, and gRPC, with GraphQL in progress — every protocol integration is
an equally-valid, independently consumable module outside `dataloom-api`.

## API changes introduced by DL-011

- `pushChanges` return type changed from
  `ProviderOperationResult<Unit>` to
  `ProviderOperationResult<ChangeSetAcknowledgement>`.
- `PullChangesRequest` adds an optional `checkpoint: SynchronizationCheckpoint?`.
- `PullChangesResult.NoChanges` changed from a `data object` to a
  `data class` with an optional `nextCheckpoint`.
- `PullChangesResult.Changes` adds an optional `nextCheckpoint`.

See [Acknowledgement Contracts](./acknowledgement-contracts.md) and
[Checkpoint Contracts](./checkpoint-contracts.md) for details.

## Purpose of `TransportProvider`

`TransportProvider` is the adapter boundary between DataLoom and the host
application's remote communication architecture.

```text
DataLoom Runtime
      ↓
TransportProvider
      ↓
Application-controlled transport adapter
      ↓
REST / GraphQL / gRPC / WebSocket / custom protocol
```

The shared SPI remains protocol-independent. Concrete providers may adapt
DataLoom change contracts to application-owned protocol clients and DTOs.

## Push operations

Push synchronization uses:

```text
StorageProvider.readOutboundChanges()
        ↓
TransportProvider.pushChanges()
```

`PushChangesRequest` contains:

- `request: SynchronizationRequest`
- `changeSet: ChangeSet`

Construction is declarative only. It does not perform transport I/O,
serialization, authentication, payload inspection, or retries.

A successful `pushChanges()` result returns a `ChangeSetAcknowledgement`
describing how the remote participant responded to each pushed event (see
[Acknowledgement Contracts](./acknowledgement-contracts.md)). Transport
success does **not** by itself define durable local acknowledgement,
remote business completion, or queue deletion. The current
`OutboundPushSynchronizationPipeline` validates the acknowledgement against
the pushed batch and then passes it to
`StorageProvider.acknowledgeOutboundChanges()`. A successful provider
operation may still contain event-level `RETRY` or `REJECTED` statuses within
the acknowledgement; the pipeline records them but does not perform
event-level queue reconciliation or automatic re-push.

## Pull operations

Pull synchronization uses:

```text
TransportProvider.pullChanges()
        ↓
StorageProvider.applyInboundChanges()
```

`PullChangesRequest` contains:

- `request: SynchronizationRequest`
- `entityTypes: Set<EntityType>`
- `maxEvents: Int?`
- `checkpoint: SynchronizationCheckpoint?`

Rules:

- `entityTypes` defaults to an empty set.
- An empty set means no explicit entity-type restriction.
- `entityTypes` is defensively copied.
- `maxEvents`, when supplied, must be greater than zero.
- `checkpoint` defaults to `null`, meaning no prior checkpoint is supplied.
- The transport provider treats the checkpoint's token as opaque unless it
  owns the token format (see [Checkpoint Contracts](./checkpoint-contracts.md)).

`TransportProvider` must not decide synchronization direction on its own. The
DataLoom runtime coordinates push, pull, or bidirectional workflows according
to policy.

## Pull result and no-change result

`PullChangesResult` is a sealed result contract:

- `NoChanges(nextCheckpoint: SynchronizationCheckpoint? = null)`
- `Changes(changeSet, hasMore, nextCheckpoint: SynchronizationCheckpoint? = null)`

`NoChanges` represents a successful remote response containing no inbound
changes. It may still carry a next checkpoint.

`Changes` contains:

- `changeSet: ChangeSet` — a non-empty inbound change set
- `hasMore: Boolean` — `true` when another pull may return more changes
- `nextCheckpoint: SynchronizationCheckpoint?` — optional next checkpoint

The result does not expose response bodies, status codes, headers, sockets,
streams, or other protocol-specific details. It also does not automatically
apply inbound changes or persist the returned checkpoint. See
[Checkpoint Contracts](./checkpoint-contracts.md) for the critical
apply-before-advance rule.

> **API change:** `PullChangesResult.NoChanges` changed from a `data object`
> to a `data class` carrying an optional `nextCheckpoint`.

## Batching through `maxEvents` and `hasMore`

`maxEvents` is an optional caller hint for pull batching. It lets a provider ask
for a limited number of events from the remote system without requiring a
protocol-specific pagination contract in the shared API.

`hasMore` lets a provider report that another pull may return additional
changes.

Continuation tokens, delta tokens, transport cursors, and other
protocol-specific paging state are not modeled by this shared contract.
Providers keep those details behind the SPI and expose only `hasMore` and the
optional `nextCheckpoint`; DataLoom does not currently provide a generic
continuation-token API.

## Protocol independence

Concrete transport providers may adapt DataLoom changes to technologies such as:

- REST
- GraphQL
- gRPC
- WebSocket
- MQTT
- Bluetooth
- NFC
- Local IPC
- Custom enterprise protocols

The shared SPI must not expose technology-specific APIs, client types,
annotations, request builders, headers, HTTP methods, query documents, service
definitions, topics, frames, or sockets.

## Error mapping

Provider implementations must map protocol-specific failures into
`DataLoomError`.

Examples include:

- Connectivity failure
- Authentication failure
- Authorization failure
- Serialization failure
- Validation failure
- Provider failure
- Security failure

The public contract must not expose Retrofit, Ktor, HTTP, GraphQL, gRPC,
socket, or platform exceptions directly.

## Thread-safety expectations

`TransportProvider` does not impose a threading model or dispatcher choice.
Implementations are responsible for their own thread-safety guarantees and any
additional concurrency constraints.

## Coroutine-cancellation expectations

Transport operations are `suspend` functions. Implementations must preserve
coroutine cancellation and must not swallow cancellation signals.

## Authentication boundary

Authentication remains application-owned.

- Transport providers may integrate with application-controlled token sources
  and authentication systems.
- `SynchronizationRequest`, `ExecutionContext`, and `DataLoomMetadata` must not
  contain raw credentials or access tokens.
- DataLoom does not refresh tokens automatically in DL-010.
- Authentication failures must be mapped to `DataLoomError`.
- Credentials must not be logged.

## Serialization boundary

`DataLoomPayload` remains opaque.

- Transport providers may use application-controlled serializers.
- DataLoom shared APIs do not assume JSON, XML, protobuf, CBOR, or any other
  format.
- Payload content type describes the caller-declared representation only.
- Content type does not prove that payload bytes are valid.

Serialization-provider contracts are deferred to a later issue.

## TLS and encryption boundary

Transport security remains outside the shared SPI.

- TLS, certificate pinning, and mTLS are configured by the application or a
  concrete transport provider.
- Payload-level encryption may be added later through a dedicated
  `EncryptionProvider`.
- Transport providers must not assume payloads are plaintext.
- DataLoom core does not manage certificates or private keys.
- Secrets, credentials, and key material must not be placed in metadata.

## Placeholder provider example

```kotlin
private class ExampleTransportProvider(
    override val descriptor: ProviderDescriptor = ProviderDescriptor(
        id = ProviderId("provider.transport.example"),
        name = ProviderName("Example Transport Provider"),
        type = ProviderType.TRANSPORT,
        version = ProviderVersion("1.0.0"),
    ),
) : TransportProvider {
    override suspend fun initialize(
        context: ProviderInitializationContext,
    ): ProviderOperationResult<Unit> = ProviderOperationResult.Success(Unit)

    override suspend fun health(): ProviderOperationResult<ProviderHealth> {
        return ProviderOperationResult.Success(
            ProviderHealth(status = ProviderHealthStatus.HEALTHY),
        )
    }

    override suspend fun close(): ProviderOperationResult<Unit> =
        ProviderOperationResult.Success(Unit)

    override suspend fun pushChanges(
        request: PushChangesRequest,
    ): ProviderOperationResult<ChangeSetAcknowledgement> {
        val acknowledgement = ChangeSetAcknowledgement(
            changeSetId = request.changeSet.id,
            events = request.changeSet.events.map { event ->
                ChangeEventAcknowledgement(
                    eventId = event.id,
                    status = ChangeAcknowledgementStatus.ACCEPTED,
                )
            },
        )
        return ProviderOperationResult.Success(acknowledgement)
    }

    override suspend fun pullChanges(
        request: PullChangesRequest,
    ): ProviderOperationResult<PullChangesResult> {
        return ProviderOperationResult.Success(PullChangesResult.NoChanges())
    }
}
```

This example is illustrative only. It is not a concrete network provider.

## Current orchestration and remaining transport gaps

The current outbound pipeline validates and records transport
acknowledgements. The current inbound pipeline reads the stored checkpoint,
passes it to `pullChanges()`, applies returned changes, and writes the next
checkpoint only after successful application. These are runtime
responsibilities; a transport provider must not update storage itself.

The following behavior is still absent from this transport boundary or remains
incomplete for V1:

- Queue deletion rules
- Remote idempotency keys
- Streaming subscriptions
- Persistent connections
- Upload progress
- Download progress
- Large-file transfer
- Multipart transfer
- Resumable transfer
- Rate-limit contracts
- Server-directed retry timing
- Authentication-provider contracts
- Serialization-provider contracts
- Encryption-provider contracts
- Compression-provider contracts
