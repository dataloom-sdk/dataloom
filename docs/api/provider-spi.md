# DataLoom Provider SPI (DL-007)

`dataloom-api` now defines a foundational, platform-independent Service
Provider Interface (SPI) for DataLoom integrations.

This issue introduces contract surfaces only. It does **not** implement
concrete providers, provider registration, provider discovery, retries, runtime
lifecycle orchestration, or platform-specific integrations.

## Purpose of providers

Providers isolate infrastructure concerns behind stable DataLoom contracts so
runtime and host code can remain technology-neutral.

## Provider categories

`ProviderType` defines the currently supported provider categories:

- `STORAGE`
- `TRANSPORT`
- `SCHEDULER`
- `CONNECTIVITY`
- `AUTHENTICATION`
- `SERIALIZATION`
- `ENCRYPTION`
- `COMPRESSION`
- `LOGGING`
- `MONITORING`

These categories are semantic labels only and are not bound to concrete
technologies.

## Provider identifiers

The SPI defines these immutable value types:

- `ProviderId`
- `ProviderName`
- `ProviderVersion`
- `ProviderCapability`

Rules:

- Each wraps a `String`.
- Blank and whitespace-only values are rejected.
- Valid input is preserved exactly as supplied.
- `toString()` returns the wrapped value.

## Provider descriptor

`ProviderDescriptor` declares provider identity and metadata:

- `id: ProviderId`
- `name: ProviderName`
- `type: ProviderType`
- `version: ProviderVersion`
- `capabilities: Set<ProviderCapability>` (defaults to empty set)
- `metadata: DataLoomMetadata` (defaults to empty metadata)

Capabilities are defensively copied and exposed as immutable snapshots.

## Provider capabilities

Capabilities are opaque labels that describe optional features. They are not
automatic behavior toggles and do not trigger provider loading.

## Provider initialization

`ProviderInitializationContext` provides immutable runtime-level setup inputs:

- `runtimeVersion: RuntimeVersion?`
- `configurationVersion: ConfigurationVersion?`
- `metadata: DataLoomMetadata`

This context is distinct from synchronization `ExecutionContext`.

## Provider results

`ProviderOperationResult<T>` is a provider-scoped result contract:

- `Success<T>(value: T)`
- `Failure(error: DataLoomError)`

It does not implement retries, callbacks, mutable completion state, or logging.

## DataLoomProvider

`DataLoomProvider` is the common provider contract:

- `descriptor: ProviderDescriptor`
- `initialize(context): ProviderOperationResult<Unit>`
- `health(): ProviderOperationResult<ProviderHealth>`
- `close(): ProviderOperationResult<Unit>`

All operations are `suspend` and remain platform-independent.

## Platform independence

Provider SPI contracts in `dataloom-api` use Kotlin standard-library and
existing DataLoom API types only. They do not expose Android, JVM-only,
Apple-specific, or third-party API types.

## Thread-safety expectations

The SPI does not enforce a threading model. Concrete providers are responsible
for documenting and enforcing their own thread-safety guarantees and additional
concurrency constraints.

## Cancellation expectations

Provider implementations must preserve coroutine cancellation and must not
swallow cancellation signals.

## Sensitive-data restrictions

Do not place credentials, tokens, encryption keys, personal data, or full
payloads in provider metadata, health details, or error messages.

## Placeholder example test provider

```kotlin
private class ExampleProvider(
    override val descriptor: ProviderDescriptor = ProviderDescriptor(
        id = ProviderId("example.provider"),
        name = ProviderName("Example Provider"),
        type = ProviderType.STORAGE,
        version = ProviderVersion("1.0.0"),
        capabilities = setOf(ProviderCapability("batch-read")),
    ),
) : DataLoomProvider {
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
}
```

## Deferred specialized provider contracts

The following contracts are planned follow-up issues and are not implemented in
DL-007:

- Implement storage-provider contracts
- Implement transport-provider contracts
- Implement scheduler and connectivity-provider contracts
- Implement provider registry
- Implement provider lifecycle orchestration
- Add Android platform module
- Add WorkManager provider
- Add Room provider
- Add Retrofit provider
- Add future KMP provider integrations
