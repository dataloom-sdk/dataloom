# DataLoom Connectivity Provider (DL-012)

`dataloom-api` defines a platform-independent connectivity provider SPI that
allows DataLoom to query the current device-level network state without
depending directly on Android ConnectivityManager, Apple NWPathMonitor, or
any other platform networking API.

---

## Contracts

### `ConnectivityRequirement`

**Package:** `io.dataloom.api.connectivity`

Closed set of network connectivity requirements for a scheduled
synchronization request.

| Value       | Meaning |
|-------------|---------|
| `NONE`      | No connectivity is required. The workflow may execute regardless of network state. |
| `AVAILABLE` | Usable connectivity must be reported before execution. |
| `UNMETERED` | Usable, non-metered connectivity must be reported before execution. |

These values are not bound to WorkManager network types or platform network
constants.

#### Evaluation rules (documented only — not implemented in DL-012)

**NONE:** Always satisfied without requiring a connectivity query.

**AVAILABLE:** Satisfied only when the current snapshot reports
`ConnectivityStatus.AVAILABLE`.

**UNMETERED:** Satisfied when `status == AVAILABLE` and `isMetered == false`.
A `null` metering state must not be treated as unmetered.

---

### `ConnectivityStatus`

**Package:** `io.dataloom.api.connectivity`

Closed set of device-level connectivity states.

| Value         | Meaning |
|---------------|---------|
| `UNKNOWN`     | Connectivity cannot currently be determined. |
| `UNAVAILABLE` | No usable connection is currently reported. |
| `AVAILABLE`   | Usable connectivity is currently reported. |
| `LIMITED`     | Connectivity exists but may be restricted, captive, or unvalidated. |

`AVAILABLE` does not prove that a backend endpoint is reachable or that any
specific request will succeed. It describes device-level connectivity only.

`LIMITED` may represent a captive Wi-Fi portal, an unvalidated interface, or
a connection the platform reports as having limited capability.

---

### `ConnectivitySnapshot`

**Package:** `io.dataloom.api.connectivity`

Immutable snapshot of the current device-level connectivity state.

```kotlin
val snapshot = ConnectivitySnapshot(
    status = ConnectivityStatus.AVAILABLE,
    isMetered = false,
)
```

| Property    | Type                 | Default                  |
|-------------|----------------------|--------------------------|
| `status`    | `ConnectivityStatus` | required                 |
| `isMetered` | `Boolean?`           | required (may be `null`) |
| `metadata`  | `DataLoomMetadata`   | `DataLoomMetadata.Empty` |

#### Metering state

`isMetered` is nullable because some platforms or provider implementations
may not be able to determine whether the active connection is metered:

- `true`: the connection is metered (e.g., mobile data)
- `false`: the connection is unmetered (e.g., home Wi-Fi)
- `null`: the provider cannot determine metering state

A `null` metering state must not be treated as unmetered.

#### Privacy restrictions

`ConnectivitySnapshot` must not expose:

- IP addresses
- Interface names
- SSIDs
- Carrier names
- VPN details
- MAC addresses
- Platform network handles

`metadata` must not contain credentials, authentication tokens, personal
data, or sensitive network information.

---

### `ConnectivityCheckRequest`

**Package:** `io.dataloom.api.connectivity`

Immutable request to query the current connectivity state.

```kotlin
val request = ConnectivityCheckRequest(context = executionContext)
```

| Property  | Type             | Default  |
|-----------|------------------|----------|
| `context` | `ExecutionContext` | required |

- Construction does not query network state, read platform connectivity
  APIs, or trigger any platform callbacks.

---

### `ConnectivityProvider`

**Package:** `io.dataloom.api.connectivity`

Platform-independent provider contract for querying the current device-level
network connectivity state.

```kotlin
public interface ConnectivityProvider : DataLoomProvider {

    override val descriptor: ProviderDescriptor

    suspend fun currentConnectivity(
        request: ConnectivityCheckRequest,
    ): ProviderOperationResult<ConnectivitySnapshot>
}
```

- Descriptor type must be `ProviderType.CONNECTIVITY`.
- Does not expose ConnectivityManager, Network, NetworkCapabilities,
  NWPathMonitor, callbacks, `CoroutineScope`, or platform-specific types.
- Does not perform transport requests or test backend reachability.
- Does not automatically retry.
- Preserves coroutine cancellation.

#### Thread safety

Implementations are responsible for documenting and enforcing their own
thread-safety guarantees.

#### Coroutine cancellation

Implementations must preserve coroutine cancellation and must not convert
cancellation exceptions into normal failures.

#### Error handling

- Platform failures must be mapped to canonical `DataLoomError` values.
- Platform exceptions must not escape through the public contract.
- Sensitive context must not be logged automatically.

---

## Future Android Connectivity Boundary

A future Android-specific module may implement the following flow:

```text
ConnectivityManager
      ↓
AndroidConnectivityProvider
      ↓
ConnectivitySnapshot
      ↓
DataLoom Runtime
```

The Android provider may inspect platform capabilities internally using
`NetworkCapabilities` and `ConnectivityManager`. It must expose only the
canonical DataLoom connectivity model and must not surface
`ConnectivityManager`, `Network`, or `NetworkCapabilities` types through the
public API.

---

## Deferred Connectivity Features

The following are deferred to future issues:

- `Flow<ConnectivitySnapshot>` streaming observation
- Callback-based observation
- Connectivity event streams
- Backend reachability checks
- Network quality measurement
- Bandwidth estimation
- Roaming status
- VPN detection
- Wi-Fi-only requirements
- Transport-type exposure
- Captive-portal handling policy
- Network switching events
- Connectivity-triggered runtime execution

---

## Kotlin Multiplatform Boundary

- Shared contracts remain in `dataloom-api`.
- Android connectivity is implemented in an Android-specific module.
- Apple connectivity requires a future Apple-specific adapter.
- KMP does not guarantee identical connectivity evaluation semantics across
  platforms.
- Each platform provider documents its own limitations.
- Provider interfaces are preferred over forcing connectivity behavior
  through `expect`/`actual`.
