# DataLoom Ktor Transport Provider (ADOPT-002)

[API reference index](./README.md)

> **Status:** Available optional reference module. This module demonstrates one
> Ktor-backed `TransportProvider` implementation; applications may use it,
> fork it, or replace it with another provider.

`dataloom-transport-ktor` is an optional Kotlin Multiplatform module that
implements `TransportProvider` with real Ktor HTTP calls while keeping
DataLoom payloads, endpoint selection, authentication, and serialization
application-owned.

## Module boundary

- Depends on `dataloom-api` plus Ktor client artifacts only.
- Compiles with the same shared targets as the rest of the KMP graph: JVM and,
  on supported hosts, `iosArm64`, `iosSimulatorArm64`, and `iosX64`.
- Does not add Ktor to `dataloom-model`, `dataloom-provider-api`,
  `dataloom-api`, `dataloom-core`, or `dataloom-runtime`.
- Does not inspect `DataLoomPayload` bytes directly; the injected codec owns all
  encoding and decoding decisions.

## Public types

- `KtorTransportProvider` — reference `TransportProvider` implementation.
- `KtorTransportCodec` — application-owned boundary for endpoint resolution,
  authentication headers, request encoding, and response decoding.
- `KtorTransportHttpRequest` / `KtorTransportHttpResponse` — safe HTTP envelope
  models that redact header values and body content from `toString()`.
- `KtorTransportHttpConfiguration` — optional client timeout and redirect
  configuration.

## Error mapping

The provider never returns raw Ktor exceptions or raw HTTP response types.
Transport failures are mapped to canonical `DataLoomError` values and, for
remote HTTP failures, to `ClassifiedStrategyRemoteError` where applicable.

| Condition | Canonical shape |
|---|---|
| Client/request/connect/socket timeout | `ClassifiedStrategyRemoteError` with `remoteOutcome = TIMEOUT` |
| `401` | `ClassifiedStrategyRemoteError` with `remoteOutcome = AUTHENTICATION_FAILURE` |
| `403` | `ClassifiedStrategyRemoteError` with `remoteOutcome = AUTHORIZATION_FAILURE` |
| `409` | `ClassifiedStrategyRemoteError` with `remoteOutcome = CONFLICT` |
| `429` | `ClassifiedStrategyRemoteError` with `remoteOutcome = RATE_LIMITED` and a normalized retry hint when `Retry-After` is numeric seconds |
| `502` / `503` | `ClassifiedStrategyRemoteError` with `remoteOutcome = UNAVAILABLE` |
| Other `5xx` | `ClassifiedStrategyRemoteError` with `remoteOutcome = SERVER_FAILURE` |
| Other `4xx` | `ClassifiedStrategyRemoteError` with `remoteOutcome = VALIDATION_FAILURE` |
| Codec encode/decode failure | Canonical `DataLoomError` with `SERIALIZATION` category |
| Invalid request configuration | Canonical `DataLoomError` with `CONFIGURATION` category |

Cancellation still propagates as `CancellationException`.

## Safe diagnostics

`KtorTransportHttpRequest` and `KtorTransportHttpResponse` intentionally omit
header values and body content from `toString()`. This helps prevent accidental
logging of credentials, bearer tokens, cookies, or opaque application payloads.

## Quickstart

```kotlin
import io.dataloom.api.change.ChangeEvent
import io.dataloom.api.change.ChangeSet
import io.dataloom.api.change.EntityReference
import io.dataloom.api.context.ExecutionContext
import io.dataloom.api.identifier.ChangeEventId
import io.dataloom.api.identifier.ChangeSetId
import io.dataloom.api.identifier.CorrelationId
import io.dataloom.api.identifier.EntityId
import io.dataloom.api.identifier.EntityType
import io.dataloom.api.identifier.ExecutionId
import io.dataloom.api.identifier.SynchronizationSessionId
import io.dataloom.api.identifier.WorkflowId
import io.dataloom.api.model.ChangeOperation
import io.dataloom.api.model.SynchronizationDirection
import io.dataloom.api.model.SynchronizationMode
import io.dataloom.api.model.SynchronizationRequest
import io.dataloom.api.payload.DataLoomPayload
import io.dataloom.api.payload.PayloadContentType
import io.dataloom.api.synchronization.ChangeAcknowledgementStatus
import io.dataloom.api.synchronization.ChangeEventAcknowledgement
import io.dataloom.api.synchronization.ChangeSetAcknowledgement
import io.dataloom.api.transport.PushChangesRequest
import io.dataloom.transport.ktor.KtorTransportCodec
import io.dataloom.transport.ktor.KtorTransportHttpMethod
import io.dataloom.transport.ktor.KtorTransportHttpRequest
import io.dataloom.transport.ktor.KtorTransportHttpResponse
import io.dataloom.transport.ktor.KtorTransportProvider

val provider = KtorTransportProvider(
    codec = object : KtorTransportCodec {
        override suspend fun encodePushRequest(request: PushChangesRequest): KtorTransportHttpRequest {
            val body = request.changeSet.id.value.encodeToByteArray()
            return KtorTransportHttpRequest(
                method = KtorTransportHttpMethod.POST,
                url = "https://api.example.test/sync/push",
                headers = mapOf(
                    "Authorization" to listOf("******"),
                ),
                body = body,
                contentType = "application/octet-stream",
            )
        }

        override suspend fun decodePushResponse(
            request: PushChangesRequest,
            response: KtorTransportHttpResponse,
        ): ChangeSetAcknowledgement {
            val acknowledgedEventId = response.copyBody().decodeToString().substringAfter(':')
            return ChangeSetAcknowledgement(
                changeSetId = request.changeSet.id,
                events = listOf(
                    ChangeEventAcknowledgement(
                        eventId = ChangeEventId(acknowledgedEventId),
                        status = ChangeAcknowledgementStatus.ACCEPTED,
                    ),
                ),
            )
        }

        override suspend fun encodePullRequest(request: io.dataloom.api.transport.PullChangesRequest): KtorTransportHttpRequest {
            return KtorTransportHttpRequest(
                method = KtorTransportHttpMethod.POST,
                url = "https://api.example.test/sync/pull",
                headers = mapOf(
                    "Authorization" to listOf("******"),
                ),
                body = (request.checkpoint?.token?.value ?: "none").encodeToByteArray(),
                contentType = "text/plain",
            )
        }

        override suspend fun decodePullResponse(
            request: io.dataloom.api.transport.PullChangesRequest,
            response: KtorTransportHttpResponse,
        ): io.dataloom.api.transport.PullChangesResult {
            return io.dataloom.api.transport.PullChangesResult.NoChanges()
        }
    },
)

val pushRequest = PushChangesRequest(
    request = SynchronizationRequest(
        workflowId = WorkflowId("workflow-1"),
        sessionId = SynchronizationSessionId("session-1"),
        direction = SynchronizationDirection.PUSH,
        mode = SynchronizationMode.DELTA,
        context = ExecutionContext(
            executionId = ExecutionId("execution-1"),
            correlationId = CorrelationId("correlation-1"),
        ),
    ),
    changeSet = ChangeSet(
        id = ChangeSetId("change-set-1"),
        events = listOf(
            ChangeEvent(
                id = ChangeEventId("event-1"),
                entity = EntityReference(
                    type = EntityType("widget"),
                    id = EntityId("widget-1"),
                ),
                operation = ChangeOperation.UPDATE,
                payload = DataLoomPayload(
                    contentType = PayloadContentType("application/octet-stream"),
                    bytes = "application-defined-payload".encodeToByteArray(),
                ),
            ),
        ),
    ),
)

val result = provider.pushChanges(pushRequest)
```

The quickstart uses a trivial byte payload for clarity. Production
applications usually plug in their own JSON, protobuf, encryption, or signing
stack inside `KtorTransportCodec`.

## Validation

From the repository root:

```bash
./gradlew :dataloom-transport-ktor:build
```
