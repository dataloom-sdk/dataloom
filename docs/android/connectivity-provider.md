# Android Connectivity Provider (DL-037)

`AndroidConnectivityProvider` implements DataLoom's `ConnectivityProvider`
contract using Android `ConnectivityManager`.

## Module

`dataloom-connectivity-android`

## Required permission

The module manifest declares `ACCESS_NETWORK_STATE`, which is merged into the
host application automatically:

```xml
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
```

## Status mapping

| Platform state | `ConnectivityStatus` |
|---|---|
| `ConnectivityManager` unavailable | `UNKNOWN` |
| No active network | `UNAVAILABLE` |
| Active network but capabilities unavailable | `UNKNOWN` |
| `NET_CAPABILITY_INTERNET` and `NET_CAPABILITY_VALIDATED` | `AVAILABLE` |
| Internet capability without validation | `LIMITED` |
| No internet capability | `LIMITED` |

On API 21–22, where validated-network capability is unavailable, a connected
legacy network is reported conservatively as `LIMITED`.

## Privacy and behavior

Each `currentConnectivity()` call performs one bounded current-state query.
The provider does not poll, register callbacks, expose a `Flow`, cache
results, or hold a background coroutine scope. It does not read or log SSID,
BSSID, carrier, signal, IP, MAC, location, credentials, or payload data.

## Usage

```kotlin
val provider = AndroidConnectivityProvider(context)
val result: ProviderOperationResult<ConnectivitySnapshot> =
    provider.currentConnectivity(
        ConnectivityCheckRequest(context = executionContext),
    )
```

The request carries the DataLoom `ExecutionContext`; the connectivity
requirement belongs to scheduling or synchronization policy, not to
`ConnectivityCheckRequest`.
