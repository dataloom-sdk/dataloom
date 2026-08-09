# dataloom-transport-graphql

An optional, independently consumable reference
[`TransportProvider`](../docs/api/transport-provider.md) implementation for
[DataLoom](../README.md), backed by
[Apollo Kotlin](https://www.apollographql.com/docs/kotlin/) 4.x.

> **This is a reference implementation.** Applications are expected to extend
> or fork it to match their own GraphQL schema and business requirements. It is
> not the only supported way to integrate DataLoom with a GraphQL backend.

---

## Platform support

| Consumer path          | Status                                              |
|------------------------|-----------------------------------------------------|
| Native Android         | ✅ JVM/Android target                               |
| KMP → Android          | ✅ JVM/Android target                               |
| KMP → iOS              | ✅ iOS targets (macOS host / cross-compile enabled) |
| Swift / XCFramework    | via KMP iOS targets                                 |

---

## Design

A DataLoom push maps to a **GraphQL mutation**; a pull maps to a
**GraphQL query**.  Both operations are plain request-in / result-out suspend
functions, which map naturally onto Apollo Kotlin's `ApolloCall.execute()` API
without any streaming or subscription contract.

The module is intentionally **schema-agnostic**.  The concrete GraphQL schema,
code-generated operation types, and response adapters are owned by the
application.  `ApolloGraphQLTransportProvider` exposes two extension points
(`executePush`, `executePull`) that the application fills in with its own
Apollo operations, plus helper methods (`adaptMutationResponse`,
`adaptQueryResponse`) that handle error mapping from Apollo response objects to
canonical `DataLoomError` values.

---

## Dependency

Add to your module's `build.gradle.kts`:

```kotlin
dependencies {
    implementation("io.dataloom:dataloom-transport-graphql:<version>")
}
```

`dataloom-transport-graphql` transitively brings in:

* `dataloom-api` (and `dataloom-model`)
* `com.apollographql.apollo:apollo-runtime`

---

## Quickstart

### 1 – Define your GraphQL schema and operations

Place your schema and operation documents where the Apollo Gradle plugin can
find them (typically `src/commonMain/graphql/`).

```graphql
# schema.graphqls (excerpt)
type Mutation {
    pushChanges(input: PushChangesInput!): PushChangesPayload!
}

type Query {
    pullChanges(cursor: String, limit: Int): PullChangesPayload!
}

# ... your full schema
```

```graphql
# PushChangesMutation.graphql
mutation PushChanges($input: PushChangesInput!) {
    pushChanges(input: $input) {
        changeSetId
        events {
            eventId
            status
        }
    }
}
```

```graphql
# PullChangesQuery.graphql
query PullChanges($cursor: String, $limit: Int) {
    pullChanges(cursor: $cursor, limit: $limit) {
        hasMore
        nextCursor
        events {
            entityType
            entityId
            operation
            payload
        }
    }
}
```

### 2 – Implement `ApolloGraphQLTransportProvider`

```kotlin
import com.apollographql.apollo.ApolloClient
import io.dataloom.api.identifier.ChangeSetId
import io.dataloom.api.provider.ProviderDescriptor
import io.dataloom.api.provider.ProviderHealth
import io.dataloom.api.provider.ProviderHealthStatus
import io.dataloom.api.provider.ProviderInitializationContext
import io.dataloom.api.provider.ProviderName
import io.dataloom.api.provider.ProviderId
import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.api.provider.ProviderType
import io.dataloom.api.provider.ProviderVersion
import io.dataloom.api.synchronization.ChangeSetAcknowledgement
import io.dataloom.api.transport.PullChangesRequest
import io.dataloom.api.transport.PullChangesResult
import io.dataloom.api.transport.PushChangesRequest
import io.dataloom.transport.graphql.ApolloGraphQLTransportProvider

// Replace these with your Apollo-generated operation types:
import com.example.app.graphql.PushChangesMutation
import com.example.app.graphql.PullChangesQuery

class MyGraphQLTransportProvider(
    override val apolloClient: ApolloClient,
) : ApolloGraphQLTransportProvider() {

    override val descriptor = ProviderDescriptor(
        id = ProviderId("my-graphql-transport"),
        name = ProviderName("My GraphQL Transport"),
        type = ProviderType.TRANSPORT,
        version = ProviderVersion("1.0.0"),
    )

    // -- Push -----------------------------------------------------------

    override suspend fun executePush(
        request: PushChangesRequest,
    ): ProviderOperationResult<ChangeSetAcknowledgement> {
        val mutation = PushChangesMutation(
            input = PushChangesMutation.PushChangesInput(
                changeSetId = request.changeSet.id.value,
                events = request.changeSet.events.map { event ->
                    PushChangesMutation.EventInput(
                        eventId = event.id.value,
                        entityType = event.entity.type.value,
                        entityId = event.entity.id.value,
                        operation = event.operation.name,
                    )
                },
            ),
        )
        val response = apolloClient.mutation(mutation).execute()
        return adaptMutationResponse(response) { data ->
            data.pushChanges.toChangeSetAcknowledgement(request.changeSet.id)
        }
    }

    // -- Pull -----------------------------------------------------------

    override suspend fun executePull(
        request: PullChangesRequest,
    ): ProviderOperationResult<PullChangesResult> {
        // The checkpoint token is treated as opaque: pass it through
        // to the GraphQL cursor argument without interpretation.
        val cursor = request.checkpoint?.token?.value
        val query = PullChangesQuery(
            cursor = cursor,
            limit = request.maxEvents,
        )
        val response = apolloClient.query(query).execute()
        return adaptQueryResponse(response) { data ->
            val page = data.pullChanges
            if (page.events.isEmpty()) {
                PullChangesResult.NoChanges(
                    nextCheckpoint = page.nextCursor?.toCheckpoint(),
                )
            } else {
                PullChangesResult.Changes(
                    changeSet = page.events.toChangeSet(),
                    hasMore = page.hasMore,
                    nextCheckpoint = page.nextCursor?.toCheckpoint(),
                )
            }
        }
    }

    // -- Lifecycle ------------------------------------------------------

    override suspend fun initialize(
        context: ProviderInitializationContext,
    ): ProviderOperationResult<Unit> = ProviderOperationResult.Success(Unit)

    override suspend fun health(): ProviderOperationResult<ProviderHealth> =
        ProviderOperationResult.Success(ProviderHealth(status = ProviderHealthStatus.HEALTHY))
}
```

> **Checkpoint / cursor mapping** — `PullChangesRequest.checkpoint` is
> intentionally opaque: this provider passes its `token.value` directly to
> the GraphQL cursor argument.  The server-issued next cursor is wrapped in a
> `SynchronizationCheckpoint` and returned as `nextCheckpoint`; DataLoom
> persists it only after the inbound changes have been applied successfully.

### 3 – Configure authentication

Use Apollo Kotlin's interceptor API — **never** pass tokens through
`PushChangesRequest` or `PullChangesRequest`:

```kotlin
val client = ApolloClient.Builder()
    .serverUrl("https://api.example.com/graphql")
    .addHttpInterceptor(AuthInterceptor(tokenProvider))
    .build()

val transport = MyGraphQLTransportProvider(apolloClient = client)
```

---

## Error mapping

| Failure                     | `ErrorCode`                           | `Recoverability`   |
|-----------------------------|---------------------------------------|--------------------|
| Network / IO failure        | `GRAPHQL_TRANSPORT_NETWORK_FAILURE`   | `RECOVERABLE`      |
| HTTP 5xx or 429             | `GRAPHQL_TRANSPORT_HTTP_ERROR`        | `RECOVERABLE`      |
| HTTP 4xx (except 429)       | `GRAPHQL_TRANSPORT_HTTP_ERROR`        | `NON_RECOVERABLE`  |
| GraphQL `errors[]` response | `GRAPHQL_TRANSPORT_GRAPHQL_ERROR_RESPONSE` | `UNKNOWN`      |
| Null data (no error)        | `GRAPHQL_TRANSPORT_NULL_DATA`         | `NON_RECOVERABLE`  |

Raw Apollo exceptions and GraphQL response bodies are never surfaced through
the public API.  `CancellationException` is always re-thrown so that
structured concurrency is preserved.

---

## Testing

Use Apollo Kotlin's `QueueTestNetworkTransport` to test your subclass without
a live server:

```kotlin
import com.apollographql.apollo.testing.QueueTestNetworkTransport
import com.apollographql.apollo.testing.enqueueTestResponse

val transport = QueueTestNetworkTransport()
val client = ApolloClient.Builder()
    .networkTransport(transport)
    .build()

val provider = MyGraphQLTransportProvider(apolloClient = client)

// Enqueue a success response
client.enqueueTestResponse(
    PushChangesMutation(...),
    PushChangesMutation.Data { /* build test data */ },
)

val result = provider.pushChanges(myRequest)
```

---

## Limitations and follow-up work

* **Schema ownership** — the actual GraphQL schema and generated operations
  are application-owned.  This module provides no default schema.
* **Streaming / subscriptions** — real-time subscriptions require a new
  DataLoom subscription-style capability (tracked separately; not part of
  this module).
* **Retry** — retry timing and execution are DataLoom runtime responsibilities;
  this provider does not retry internally.
* **Authentication** — credentials must be configured on the `ApolloClient`
  via interceptors; this provider does not manage tokens.
