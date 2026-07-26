# Android Connectivity Provider (DL-037)

`AndroidConnectivityProvider` implements the DataLoom `ConnectivityProvider`
contract using the Android `ConnectivityManager` API.

## Module

`dataloom-connectivity-android`

## Required permission

The host application must declare `ACCESS_NETWORK_STATE` in its manifest:

```xml
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
```

The module manifest already includes this permission. Android merges it
automatically; no additional step is required.

## Status mapping

| Platform state | `ConnectivityStatus` |
|---|---|
| `ConnectivityManager` unavailable | `UNKNOWN` |
| No active network | `UNAVAILABLE` |
| Active network but `NetworkCapabilities` unavailable | `UNKNOWN` |
| `NET_CAPABILITY_INTERNET` and `NET_CAPABILITY_VALIDATED` | `AVAILABLE` |
| `NET_CAPABILITY_INTERNET` but not validated | `LIMITED` |
| No `NET_CAPABILITY_INTERNET` capability | `LIMITED` |

Validated internet connectivity (`NET_CAPABILITY_VALIDATED`) is required before
reporting `AVAILABLE`. The presence of an active network alone is not
sufficient.

## Privacy guarantees

- Does not read SSID, BSSID, carrier name, signal strength, IP address, or
  MAC address.
- Does not use `WifiInfo` or `TelephonyManager`.
- Does not require any location permission.
- Does not log any network characteristics.

## Behaviour contract

- Performs one bounded current-state query per `check()` call.
- Does not poll.
- Does not register a `NetworkCallback`.
- Does not return a `Flow`.
- Does not hold a background coroutine scope.
- Does not cache results between calls.

## Usage

```kotlin
val provider = AndroidConnectivityProvider(context)
val result: ProviderOperationResult<ConnectivitySnapshot> = provider.currentConnectivity(
    ConnectivityCheckRequest(requirement = ConnectivityRequirement.AVAILABLE)
)
```

## Testing

Unit tests mock `ConnectivityManager` and `NetworkCapabilities` to cover
all status mapping branches without requiring a real device or emulator.
