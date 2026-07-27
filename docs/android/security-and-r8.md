# Security and R8 (DL-037)

## Consumer R8 rules

Each Android module ships consumer ProGuard rules that are applied
automatically to consuming applications by the Android build tools. No
manual configuration is required for the DataLoom classes themselves.

| Module | File |
|---|---|
| `dataloom-connectivity-android` | `consumer-rules.pro` |
| `dataloom-scheduler-workmanager` | `consumer-rules.pro` |
| `dataloom-queue-room` | `consumer-rules.pro` |

## What the rules preserve

- Public provider classes and their public API surface.
- Room entity, DAO, and database classes (required for Room's reflection).
- Worker classes (required for WorkManager's class-name-based instantiation).

## Database encryption

`RoomQueueProvider` does not enable database encryption by default. Queue
entries may contain application context that is sensitive according to
application policy.

Host applications that require encryption should configure SQLCipher or
an equivalent encrypted Room support with `SupportSQLiteOpenHelper.Factory`
before passing the database to `RoomQueueProvider`.

## Payload and metadata security

Queue entry payloads and `ExecutionContext` metadata may contain sensitive
data according to the host application's threat model. No DataLoom module
logs, transmits, or caches this data. However, the host application is
responsible for:

- Applying appropriate file-system encryption (e.g., Android File-Based
  Encryption) to protect the database at rest.
- Ensuring that `ExecutionContext.metadata` does not contain credentials,
  tokens, or sensitive personal data (per the DataLoom API contract).

## Diagnostic log safety

No module logs raw SQL details, database paths, exception messages, stack
traces, credentials, or user payloads. All error messages in
`ProviderOperationResult.Failure` are sanitized canonical strings.

## Permissions

Only `dataloom-connectivity-android` declares a manifest permission:

```xml
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
```

This is a normal (non-dangerous) permission. It does not require runtime
user consent.

No other Android module declares any permission.
