# Android connectivity provider

> **Audience:** Android developers wiring connectivity-aware DataLoom work
> **Purpose:** Document the exact `AndroidConnectivityProvider` mapping and
> privacy boundary
> **Status:** Current Android adapter foundation; not endpoint reachability or
> complete V1 strategy support

[← Android overview](README.md) ·
[WorkManager scheduler](workmanager-scheduler.md) ·
[Security and R8](security-and-r8.md)

`AndroidConnectivityProvider` implements the shared `ConnectivityProvider`
contract with one bounded query to Android `ConnectivityManager`.

## Module

```kotlin
implementation(project(":dataloom-connectivity-android"))
```

This project dependency is for the source checkout. Published V1 coordinates
are not available yet.

## Permission

The library manifest declares the normal, non-dangerous
`ACCESS_NETWORK_STATE` permission. Manifest merging adds it to the host
application; no runtime permission prompt is required.

```xml
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
```

## Status mapping

| Android observation | `ConnectivityStatus` | `isMetered` |
|---|---|---|
| `ConnectivityManager` unavailable | `UNKNOWN` | `null` |
| No active network | `UNAVAILABLE` | `null` |
| Active network capabilities unavailable | `UNKNOWN` | `null` |
| `INTERNET` and `VALIDATED` capabilities present | `AVAILABLE` | Inverse of `NOT_METERED` |
| Active network lacks either `INTERNET` or `VALIDATED` | `LIMITED` | Inverse of `NOT_METERED` |
| API 21–22 legacy network connected | `LIMITED` | `ConnectivityManager.isActiveNetworkMetered` |
| API 21–22 legacy network disconnected | `UNAVAILABLE` | `null` |

API 21–22 cannot report validated-network capability, so a connected legacy
network is deliberately classified as `LIMITED`.

## Usage

```kotlin
val provider = AndroidConnectivityProvider(context)

val result: ProviderOperationResult<ConnectivitySnapshot> =
    provider.currentConnectivity(
        ConnectivityCheckRequest(context = executionContext),
    )
```

`currentConnectivity` is a suspend function, but the implementation performs a
single current-state platform query. It does not poll, register callbacks,
cache snapshots, expose a `Flow`, or own a coroutine scope.

## Contract boundaries

- A snapshot describes device connectivity at the instant of the query. It
  does not prove that an application backend, DNS name, or authenticated
  endpoint is reachable.
- The provider does not read or expose SSID, BSSID, carrier, signal, IP, MAC,
  VPN, location, credential, or payload data.
- The `ConnectivityCheckRequest` carries `ExecutionContext`; the actual
  connectivity requirement belongs to scheduling or synchronization policy.
- Unexpected platform failures become a canonical provider failure.
  `CancellationException` propagates.

Connectivity is only one input to policy. This adapter does not implement
offline-first, remote-first, cache-first, network-only, hybrid, or adaptive
behavior on its own.

## Related documentation

- [Connectivity provider contract](../api/connectivity-provider.md)
- [Connectivity-aware execution](../api/connectivity-aware-execution.md)
- [Android security and permissions](security-and-r8.md)
