# Android security and R8

> **Audience:** Android security reviewers, application integrators, and SDK
> maintainers
> **Purpose:** Record current shrinker rules, permissions, sensitive-data
> boundaries, and known gaps
> **Status:** Accurate for the three current Android adapter modules; not a V1
> security certification

[← Android overview](README.md) ·
[Connectivity provider](connectivity-provider.md) ·
[Room queue provider](room-queue-provider.md)

## Consumer R8 rules

Each Android library packages `consumer-rules.pro`; Android build tooling
applies those rules to consuming applications automatically.

| Module | Preserved surface |
|---|---|
| `dataloom-connectivity-android` | `AndroidConnectivityProvider` |
| `dataloom-scheduler-workmanager` | Scheduler provider, worker, worker factory, and worker class name |
| `dataloom-queue-room` | Queue provider, database builder, Room database, DAO, entity, and internal Room members |

No extra DataLoom keep rules are currently documented for host applications.
Consumers should still test their own minified release build because host
serialization, DI, and provider implementations may need separate rules.

## Data at rest

`dataloom-queue-room` stores queue entries in an ordinary Room database. It
does not enable SQLCipher or another encryption layer.

The current `DataLoomDatabaseBuilder` accepts a context and database name only;
it does not expose Room's open-helper factory. Do not claim that encrypted
queue persistence is a drop-in option through the current builder.
Applications that require SDK-managed encrypted queue storage must provide an
alternative `QueueProvider` or wait for a reviewed encrypted construction
boundary.

Android file-based encryption may protect application files at the operating
system level, but its availability and policy are properties of the host
device and application—not a DataLoom guarantee.

## Payload and metadata handling

The Room queue necessarily persists queue payloads, metadata, and selected
sanitized error fields. The current Android adapters do not intentionally log
raw payloads, credentials, tokens, keys, SQL statements, or database paths.

Host applications remain responsible for:

- keeping credentials, authorization headers, personal data, and encryption
  keys out of `ExecutionContext.metadata`;
- deciding whether queued payloads are permitted under the application's
  threat model and retention policy;
- protecting backups, support bundles, crash reports, and device storage;
- testing redaction and minified release behavior.

## Permissions

Only `dataloom-connectivity-android` declares a permission:

```xml
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
```

It is a normal permission and does not require runtime consent.
`dataloom-scheduler-workmanager` and `dataloom-queue-room` declare no
additional permissions.

## V1 security gaps

Passing current Android tests does not qualify the V1 security model. V1 still
requires reviewed encryption/key-reference handling, redaction tests, asset
integrity and cleanup, tenant isolation, audit behavior, migration evidence,
and parity across native Android, KMP Android, and KMP iOS. Optional native
Swift distribution requires a separate Apple API and security review.

## Related documentation

- [Room queue provider](room-queue-provider.md)
- [Security policy](../../SECURITY.md)
- [V1 architecture decision](../adr/ADR-0002-v1-artifact-and-foundation-architecture.md)
