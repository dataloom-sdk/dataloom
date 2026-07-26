# DataLoom Swift Interoperability (DL-036)

## Integration Path

The V1 Swift integration path uses the conventional Kotlin/Native
framework interoperability (Objective-C/Swift bridging).

Experimental Swift export is **not** required for the production V1
integration path and is not enabled in DL-036.

## Importing DataLoom

After integrating the XCFramework (see [xcframework-integration.md](xcframework-integration.md)):

```swift
import DataLoom
```

## Accessing the Facade and Builder

```swift
// DataLoomBuilder is visible and constructible.
// Real provider implementations are required; see provider contracts below.
let _: DataLoomBuilder.Type = DataLoomBuilder.self
```

A complete DataLoom instance requires provider implementations supplied
by the host application.  No production providers are included in DL-036.

## Exported Public Types

The following public types are exported from the DataLoom XCFramework:

### Facade and Builder

| Kotlin Type | Swift Name |
|---|---|
| `DataLoom` (interface) | `DataLoom` |
| `DataLoomBuilder` (class) | `DataLoomBuilder` |
| `DataLoomQueueWorker` (interface) | `DataLoomQueueWorker` |
| `DataLoomQueueSubmission` (interface) | `DataLoomQueueSubmission` |

### Requests and Results

| Kotlin Type | Swift Name |
|---|---|
| `SynchronizationRequest` | `SynchronizationRequest` |
| `SynchronizationExecutionResult` | `SynchronizationExecutionResult` |
| `SynchronizationProviderBindings` | `SynchronizationProviderBindings` |
| `ProviderLifecycleResult` | `ProviderLifecycleResult` |

### Models

| Kotlin Type | Swift Name |
|---|---|
| `SynchronizationDirection` | `SynchronizationDirection` |
| `SynchronizationMode` | `SynchronizationMode` |
| `DataLoomError` | `DataLoomError` |
| `DataLoomInstant` | `DataLoomInstant` |
| `RuntimeDependencies` | `RuntimeDependencies` |
| `RuntimeIdentifierGenerators` | `RuntimeIdentifierGenerators` |

### Provider Interfaces

| Kotlin Type | Swift Name |
|---|---|
| `ConnectivityProvider` | `ConnectivityProvider` |
| `StorageProvider` | `StorageProvider` |
| `TransportProvider` | `TransportProvider` |
| `QueueProvider` | `QueueProvider` |
| `SchedulerProvider` | `SchedulerProvider` |
| `SynchronizationObserver` | `SynchronizationObserver` |

## Suspend Functions and Coroutines

DataLoom facade operations use Kotlin coroutines (`suspend` functions).
From Swift, Kotlin/Native generates callback-based wrappers for suspend
functions using the conventional interoperability bridge.

Callers receive completion handlers rather than `async`/`await` natively.
A full Swift concurrency adapter may be introduced in a later issue.

Example of calling a suspend function from Swift:
```swift
// suspend fun synchronize(request: SynchronizationRequest): SynchronizationExecutionResult
// becomes a callback in Swift:
dataLoom.synchronize(request: req) { result, error in
    // handle result
}
```

This pattern is documented honestly.  Do not assume `async`/`await` is
available without a Swift concurrency adapter.

## Cancellation Behavior

| Scenario | Observable Swift Behavior |
|---|---|
| Provider initialization cancelled | `ProviderLifecycleResult` representing cancellation |
| Synchronization cancelled | `SynchronizationExecutionResult` representing cancellation |
| Queue-worker cancelled | Result type appropriate to the cancellation boundary |
| Queue-submission cancelled | `QueueSubmissionResult` representing cancellation |
| Unexpected Kotlin exception | Mapped to a `DataLoomError` with appropriate category |

Kotlin `CancellationException` is **not** silently converted to a
successful result.  Structured DataLoom result contracts are preserved.

Stack traces are not exposed through public result diagnostics.

## Known Limitations in DL-036

- No native Swift `async`/`await` integration (callback bridge only).
- No SwiftPM remote package.
- No CocoaPods podspec.
- No Apple connectivity provider.
- No Apple background scheduler.
- No Apple persistent queue provider.
- Sealed-class matching is by `is`-cast in Swift (no Swift enum sugar).
- Kotlin `Long` maps to `Int64` in Swift.
- Kotlin `ByteArray` maps to `KotlinByteArray` in Swift.

## Security

The DataLoom framework does not expose through generated Apple APIs:

- Payload bytes
- Queue payloads
- Request metadata values
- Credentials or authorization headers
- Checkpoint tokens or encryption keys
- Personal data
- Provider implementation state
- Stack traces

Testing utilities (`InMemoryQueueProvider`, `FixedDataLoomClock`, etc.)
are absent from the production XCFramework.

## Smoke Test

See [../../../apple-smoke/README.md](../../../apple-smoke/README.md) for the
Swift compile-time visibility fixture.
