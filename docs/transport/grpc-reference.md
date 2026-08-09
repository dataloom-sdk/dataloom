# DataLoom gRPC Reference Transport Provider

`dataloom-transport-grpc` is an **optional** reference module providing a
[`TransportProvider`](../api/transport-provider.md) implementation backed by
[grpc-kotlin](https://github.com/grpc/grpc-kotlin) unary calls.

---

## ⚠️ Platform scope: JVM and native Android only

> **This module is JVM and native Android only. It does not support iOS.**
>
> Google's `grpc-kotlin` is built on `grpc-java` and has no Kotlin/Native
> (Apple) target. There is no equivalently mature Kotlin/Native gRPC client
> available today. Do **not** add this module as a dependency of any KMP
> module that targets iOS.
>
> iOS support is a separate follow-up issue, pending a viable Kotlin/Native
> gRPC client. Until then, consider the
> [Ktor REST reference provider](./ktor-reference.md) (where applicable) or
> build a custom `TransportProvider` for iOS.

---

## Overview

`GrpcTransportProvider` is an abstract base class. It handles:

- Calling your application's unary gRPC RPCs through the abstract methods
  `executePushUnary` and `executePullUnary`.
- Mapping `StatusException` and `StatusRuntimeException` to canonical
  `DataLoomError` — raw `io.grpc.*` types never cross the `TransportProvider`
  public API boundary.
- Propagating coroutine cancellation (`CancellationException` is always
  re-thrown, never swallowed).
- Sanitising error messages — no credentials, tokens, or call metadata appear
  in diagnostics or `toString()`.

Your application subclass:

- Supplies its own generated gRPC stubs (built from its own `.proto` files
  using the `protoc` + `grpc-kotlin` code generator).
- Implements `executePushUnary` and `executePullUnary`, performing the mapping
  between DataLoom request/result types and proto request/response types.

---

## Gradle setup

```kotlin
// app/build.gradle.kts (Android or JVM application)
dependencies {
    implementation("io.dataloom:dataloom-transport-grpc:<version>")
    // grpc-okhttp is a runtimeOnly dependency of dataloom-transport-grpc —
    // declare it explicitly if you want to override the transport.
}
```

`dataloom-transport-grpc` is **not** included in `dataloom-core`,
`dataloom-runtime`, or any other shared DataLoom module. It is strictly opt-in.

---

## Quickstart

### 1. Define your `.proto` service

```proto
// sync_service.proto
syntax = "proto3";
package com.example.sync;

service SyncService {
    rpc PushChanges(PushRequest) returns (PushResponse);
    rpc PullChanges(PullRequest) returns (PullResponse);
}

message PushRequest { /* application-defined fields */ }
message PushResponse { /* application-defined fields */ }
message PullRequest  { /* application-defined fields */ }
message PullResponse { /* application-defined fields */ }
```

### 2. Generate Kotlin stubs

Use the `protoc` + `grpc-kotlin` code generator. Refer to the
[grpc-kotlin quickstart](https://github.com/grpc/grpc-kotlin#quick-start)
for the Gradle plugin setup. Generated code is entirely application-owned and
must **not** be submitted to the DataLoom repository.

### 3. Subclass `GrpcTransportProvider`

```kotlin
import io.dataloom.api.provider.ProviderDescriptor
import io.dataloom.api.provider.ProviderId
import io.dataloom.api.provider.ProviderName
import io.dataloom.api.provider.ProviderType
import io.dataloom.api.provider.ProviderVersion
import io.dataloom.api.synchronization.ChangeAcknowledgementStatus
import io.dataloom.api.synchronization.ChangeEventAcknowledgement
import io.dataloom.api.synchronization.ChangeSetAcknowledgement
import io.dataloom.api.synchronization.SynchronizationCheckpoint
import io.dataloom.api.identifier.CheckpointKey
import io.dataloom.api.identifier.CheckpointToken
import io.dataloom.api.transport.PullChangesRequest
import io.dataloom.api.transport.PullChangesResult
import io.dataloom.api.transport.PushChangesRequest
import io.dataloom.transport.grpc.GrpcTransportProvider
import io.grpc.ManagedChannel

class MySyncGrpcTransportProvider(
    channel: ManagedChannel,
    private val stub: SyncServiceGrpcKt.SyncServiceCoroutineStub =
        SyncServiceGrpcKt.SyncServiceCoroutineStub(channel),
) : GrpcTransportProvider(channel) {

    override val descriptor = ProviderDescriptor(
        id = ProviderId("com.example.grpc.transport"),
        name = ProviderName("Example gRPC Transport"),
        type = ProviderType.TRANSPORT,
        version = ProviderVersion("1.0.0"),
    )

    // Map DataLoom push request → proto request, call the unary RPC,
    // and map the proto response back to a ChangeSetAcknowledgement.
    override suspend fun executePushUnary(
        request: PushChangesRequest,
    ): ChangeSetAcknowledgement {
        val grpcRequest = PushRequest.newBuilder()
            // populate from request.changeSet / request.request
            .build()

        val grpcResponse = stub.pushChanges(grpcRequest)   // unary RPC

        // Map the proto response to DataLoom acknowledgement types.
        return ChangeSetAcknowledgement(
            changeSetId = request.changeSet.id,
            events = grpcResponse.eventsList.map { protoAck ->
                ChangeEventAcknowledgement(
                    eventId = /* ... */,
                    status = ChangeAcknowledgementStatus.ACCEPTED,
                )
            },
        )
    }

    // Map DataLoom pull request → proto request, call the unary RPC,
    // and map the proto response back to a PullChangesResult.
    override suspend fun executePullUnary(
        request: PullChangesRequest,
    ): PullChangesResult {
        val grpcRequest = PullRequest.newBuilder()
            // populate from request.checkpoint / request.maxEvents etc.
            .build()

        val grpcResponse = stub.pullChanges(grpcRequest)   // unary RPC

        if (grpcResponse.eventsList.isEmpty()) {
            return PullChangesResult.NoChanges(
                nextCheckpoint = grpcResponse.nextCursor
                    .takeIf { it.isNotBlank() }
                    ?.let { SynchronizationCheckpoint(
                        key = CheckpointKey("orders"),
                        token = CheckpointToken(it),
                    ) },
            )
        }

        return PullChangesResult.Changes(
            changeSet = /* build ChangeSet from grpcResponse.eventsList */,
            hasMore = grpcResponse.hasMore,
            nextCheckpoint = /* ... */,
        )
    }
}
```

### 4. Build and supply the channel

```kotlin
val channel = ManagedChannelBuilder
    .forAddress("sync.example.com", 443)
    .useTransportSecurity()
    .build()

val transportProvider = MySyncGrpcTransportProvider(channel)
// Register transportProvider with your DataLoom configuration.
// Shut down the channel when the application exits.
```

---

## Security restrictions

- No credential, token, call-metadata, or header value may appear in
  `GrpcTransportProvider` logs, diagnostics, or `toString()` output.
- Subclasses must observe the same restriction: do not pass authentication
  headers or tokens through `DataLoomError.message` or `DataLoomError.cause`.
- This module does **not** implement authentication itself — use
  `CallCredentials` or `ClientInterceptor` on the `ManagedChannel` before
  supplying it to `GrpcTransportProvider`.

---

## gRPC status code mapping

| gRPC status            | `ErrorCategory`  | `Recoverability`  |
|------------------------|------------------|-------------------|
| `UNAUTHENTICATED`      | AUTHENTICATION   | NON_RECOVERABLE   |
| `PERMISSION_DENIED`    | AUTHORIZATION    | NON_RECOVERABLE   |
| `INVALID_ARGUMENT`     | VALIDATION       | NON_RECOVERABLE   |
| `FAILED_PRECONDITION`  | VALIDATION       | NON_RECOVERABLE   |
| `OUT_OF_RANGE`         | VALIDATION       | NON_RECOVERABLE   |
| `UNAVAILABLE`          | NETWORK          | RECOVERABLE       |
| `DEADLINE_EXCEEDED`    | NETWORK          | RECOVERABLE       |
| `RESOURCE_EXHAUSTED`   | NETWORK          | RECOVERABLE       |
| `ABORTED`              | NETWORK          | RECOVERABLE       |
| `CANCELLED`            | NETWORK          | RECOVERABLE       |
| `NOT_FOUND`            | NETWORK          | NON_RECOVERABLE   |
| `ALREADY_EXISTS`       | NETWORK          | NON_RECOVERABLE   |
| `UNIMPLEMENTED`        | NETWORK          | NON_RECOVERABLE   |
| `INTERNAL`             | INTERNAL         | NON_RECOVERABLE   |
| `DATA_LOSS`            | INTERNAL         | NON_RECOVERABLE   |
| `UNKNOWN`              | NETWORK          | UNKNOWN           |

Raw `io.grpc.StatusException` and `io.grpc.StatusRuntimeException` types never
cross the `TransportProvider` public API boundary. Callers always receive
`DataLoomError`.

---

## Cancellation

`CancellationException` is always re-thrown. Structured concurrency is never
broken. The base class does not use `withContext`, `launch`, or any other
mechanism that could interfere with the caller's `CoroutineScope`.

---

## This is a reference implementation

Applications are expected to:

- Fork or replace this module entirely for production use.
- Add authentication (`CallCredentials`, `ClientInterceptor`).
- Add connection management, keepalive, retry policies, and TLS configuration
  appropriate for their environment.
- Add their own metrics, tracing, and logging — without leaking sensitive data.

DataLoom does **not** provide production-ready gRPC channel management, TLS
configuration, or credential management. This module demonstrates how to
bridge the `TransportProvider` contract to `grpc-kotlin`; production concerns
are the application's responsibility.

---

## Related

- [`TransportProvider` API reference](../api/transport-provider.md)
- [Transport boundaries](../architecture/transport-boundaries.md)
- [Ktor REST reference provider](./ktor-reference.md) — alternative for
  REST/HTTP transports including iOS
