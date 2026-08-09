# Retrofit transport provider (reference)

> **Audience:** Android/JVM developers integrating DataLoom with existing Retrofit APIs
> **Purpose:** Show how to wire the optional `dataloom-transport-retrofit` reference `TransportProvider`
> **Status:** Reference implementation for source checkout usage; not a claim that other transport references are unavailable

[← Android overview](README.md) ·
[Transport provider contract](../api/transport-provider.md)

`dataloom-transport-retrofit` is an **optional**, independently consumable
reference module. It targets the JVM only, which in this repository covers
native Android consumption. It does not provide Kotlin/Native (iOS) binaries.

## Module

```kotlin
implementation(project(":dataloom-transport-retrofit"))
```

Published V1 Maven coordinates are not available yet.

## What this module does

- Executes push and pull operations through Retrofit suspend calls.
- Maps Retrofit/OkHttp failures and non-2xx HTTP results into canonical
  `DataLoomError` values.
- Preserves coroutine cancellation (`CancellationException` is rethrown).

## What your app still owns

- Retrofit service interface shape and endpoint contracts.
- DTO/request/response models and mapping.
- Converter choice (Moshi, Gson, kotlinx.serialization, or custom).
- Authentication and header/token injection.

This adapter is intentionally reference code. You may fork it, replace it, or
build your own `TransportProvider` implementation for REST, GraphQL, gRPC, or
other protocols.

## Quickstart

```kotlin
interface ContactsSyncService {
    @POST("sync/push")
    suspend fun push(@Body body: PushDto): PushResponseDto

    @POST("sync/pull")
    suspend fun pull(@Body body: PullDto): PullResponseDto
}

val retrofit = Retrofit.Builder()
    .baseUrl("https://api.example.com/")
    .addConverterFactory(MoshiConverterFactory.create(moshi))
    .client(okHttpClient)
    .build()

val service = retrofit.create(ContactsSyncService::class.java)

val transportProvider = RetrofitTransportProvider(
    descriptor = ProviderDescriptor(
        id = ProviderId("provider.transport.retrofit.contacts"),
        name = ProviderName("Contacts Retrofit Transport"),
        type = ProviderType.TRANSPORT,
        version = ProviderVersion("1.0.0"),
    ),
    pushRequestMapper = { request -> request.toPushDto() },
    pullRequestMapper = { request -> request.toPullDto() },
    pushCall = service::push,
    pullCall = service::pull,
    pushResponseMapper = { request, response ->
        response.toChangeSetAcknowledgement(changeSetId = request.changeSet.id)
    },
    pullResponseMapper = { _, response -> response.toPullChangesResult() },
)
```

The mapping functions keep domain DTOs and protocol details in the application,
while DataLoom receives only canonical transport contracts.
