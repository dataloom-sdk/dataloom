# DataLoom Execution Context (DL-005)

[API reference index](./README.md)

> **Status:** Available public contract. Complete trace propagation, tenant
> enforcement, and governance policy are not supplied by this model alone.

`ExecutionContext` is an immutable public contract in `dataloom-api` for
carrying caller-provided synchronization context.

Creating an `ExecutionContext` does not execute synchronization and does not
perform context propagation.

## Properties

Required:

- `executionId: ExecutionId`
- `correlationId: CorrelationId`

Optional:

- `traceId: TraceId?`
- `requestId: RequestId?`
- `tenantId: TenantId?`
- `userId: UserId?`
- `localeTag: LocaleTag?`
- `runtimeVersion: RuntimeVersion?`
- `configurationVersion: ConfigurationVersion?`
- `metadata: DataLoomMetadata` (defaults to empty immutable metadata)

## Identifier ownership

| Type | Expected owner |
|---|---|
| `ExecutionId` | DataLoom runtime or host integration |
| `CorrelationId` | Request initiator or integration boundary |
| `TraceId` | Observability integration |
| `RequestId` | Request initiator or host integration |
| `TenantId` | Host application or enterprise integration |
| `UserId` | Host authentication/domain layer |
| `RuntimeVersion` | DataLoom runtime |
| `ConfigurationVersion` | Configuration source or host integration |
| `LocaleTag` | Host application or request initiator |

## Correlation and trace

- `correlationId` links related work across integration boundaries.
- `traceId` is optional and used when an observability system already defines
  trace propagation.

## Tenant and user context

`tenantId` and `userId` are optional and may be supplied when host
authorization or data-partitioning context is relevant.

## Runtime and configuration versions

`runtimeVersion` and `configurationVersion` are optional immutable labels for
diagnostics and reproducibility.

## Locale tag

`localeTag` is a caller-provided tag. Values are preserved exactly and are not
normalized automatically.

## Metadata rules

`DataLoomMetadata` contains optional string key-value attributes:

- Keys must be non-blank.
- Values are preserved exactly (including empty string values).
- Metadata input is defensively copied.
- Metadata is exposed as an immutable snapshot.

## Sensitive-data restrictions

Do not store credentials, tokens, encryption keys, personal data, or full
payloads in `ExecutionContext` or `DataLoomMetadata`.

## Placeholder example

```kotlin
val context = ExecutionContext(
    executionId = ExecutionId("execution-001"),
    correlationId = CorrelationId("corr-001"),
    requestId = RequestId("request-001"),
    tenantId = TenantId("tenant-001"),
    userId = UserId("user-001"),
    localeTag = LocaleTag("en-US"),
    runtimeVersion = RuntimeVersion("runtime-1.0.0"),
    configurationVersion = ConfigurationVersion("config-2026-07-21"),
    metadata = DataLoomMetadata.of(
        mapOf(
            "channel" to "manual",
            "source" to "host-app",
        ),
    ),
)
```

Context propagation and runtime execution behavior are intentionally not
implemented in DL-005.
