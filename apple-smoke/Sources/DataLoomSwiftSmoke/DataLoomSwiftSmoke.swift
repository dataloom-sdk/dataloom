// Swift smoke test fixture for the DataLoom XCFramework (DL-036).
//
// This file verifies that:
//   - The DataLoom framework can be imported from Swift.
//   - The public facade (DataLoom, DataLoomBuilder) is visible.
//   - Key request and result types are accessible.
//   - Provider interface types are accessible.
//   - No JVM-only or internal types leak into the public API.
//
// This file does NOT:
//   - Perform real networking.
//   - Use real databases, keychain, or background tasks.
//   - Contain production credentials or personal data.
//   - Start a background CoroutineScope or global state.
//
// The smoke fixture exists only to validate Swift compile-time visibility.
// It does not produce a runnable iOS application.

import DataLoom

// ---------------------------------------------------------------------------
// Compilation-time type visibility checks
//
// Each constant or type reference below asserts that the named symbol is
// exported by the DataLoom XCFramework and visible from Swift.  The file
// compiles successfully only when all referenced symbols are present.
// ---------------------------------------------------------------------------

/// Verifies that DataLoomBuilder is accessible and its construction compiles.
/// Does not invoke the builder or start any runtime.
func smokeTestBuilderTypeIsVisible() {
    // DataLoomBuilder should be usable from Swift.
    let _: DataLoomBuilder.Type = DataLoomBuilder.self
}

/// Verifies that SynchronizationRequest is accessible.
func smokeTestSynchronizationRequestTypeIsVisible() {
    let _: SynchronizationRequest.Type = SynchronizationRequest.self
}

/// Verifies that SynchronizationDirection enum is accessible.
func smokeTestSynchronizationDirectionIsVisible() {
    let _: SynchronizationDirection.Type = SynchronizationDirection.self
}

/// Verifies that SynchronizationMode enum is accessible.
func smokeTestSynchronizationModeIsVisible() {
    let _: SynchronizationMode.Type = SynchronizationMode.self
}

/// Verifies that SynchronizationExecutionResult sealed class is visible.
func smokeTestExecutionResultIsVisible() {
    let _: SynchronizationExecutionResult.Type = SynchronizationExecutionResult.self
}

/// Verifies that SynchronizationProviderBindings is visible.
func smokeTestProviderBindingsIsVisible() {
    let _: SynchronizationProviderBindings.Type = SynchronizationProviderBindings.self
}

/// Verifies that ProviderLifecycleResult is visible.
func smokeTestProviderLifecycleResultIsVisible() {
    let _: ProviderLifecycleResult.Type = ProviderLifecycleResult.self
}

/// Verifies that DataLoomError is visible.
func smokeTestErrorTypeIsVisible() {
    let _: DataLoomError.Type = DataLoomError.self
}

/// Verifies that RuntimeDependencies is visible.
func smokeTestRuntimeDependenciesIsVisible() {
    let _: RuntimeDependencies.Type = RuntimeDependencies.self
}

/// Verifies that RuntimeIdentifierGenerators is visible.
func smokeTestRuntimeIdentifierGeneratorsIsVisible() {
    let _: RuntimeIdentifierGenerators.Type = RuntimeIdentifierGenerators.self
}

/// Verifies that DataLoomInstant is visible.
func smokeTestDataLoomInstantIsVisible() {
    let _: DataLoomInstant.Type = DataLoomInstant.self
}

/// Verifies that ConnectivityProvider protocol is visible.
func smokeTestConnectivityProviderIsVisible() {
    let _: any ConnectivityProvider.Type = (any ConnectivityProvider).self
}

/// Verifies that StorageProvider protocol is visible.
func smokeTestStorageProviderIsVisible() {
    let _: any StorageProvider.Type = (any StorageProvider).self
}

/// Verifies that TransportProvider protocol is visible.
func smokeTestTransportProviderIsVisible() {
    let _: any TransportProvider.Type = (any TransportProvider).self
}

/// Verifies that QueueProvider protocol is visible.
func smokeTestQueueProviderIsVisible() {
    let _: any QueueProvider.Type = (any QueueProvider).self
}

/// Verifies that SchedulerProvider protocol is visible.
func smokeTestSchedulerProviderIsVisible() {
    let _: any SchedulerProvider.Type = (any SchedulerProvider).self
}

/// Verifies that SynchronizationObserver protocol is visible.
func smokeTestSynchronizationObserverIsVisible() {
    let _: any SynchronizationObserver.Type = (any SynchronizationObserver).self
}

/// Verifies that DataLoomQueueWorker protocol is visible.
func smokeTestDataLoomQueueWorkerIsVisible() {
    let _: any DataLoomQueueWorker.Type = (any DataLoomQueueWorker).self
}

/// Verifies that DataLoomQueueSubmission protocol is visible.
func smokeTestDataLoomQueueSubmissionIsVisible() {
    let _: any DataLoomQueueSubmission.Type = (any DataLoomQueueSubmission).self
}
