# DataLoom Apple Platform Support (DL-036)

This directory contains documentation for the Apple-platform support
introduced in DL-036.

## Contents

| Document | Description |
|---|---|
| [apple-targets.md](apple-targets.md) | Supported Apple targets and source-set hierarchy |
| [xcframework-integration.md](xcframework-integration.md) | XCFramework configuration and local assembly |
| [swift-interop.md](swift-interop.md) | Swift interoperability, facade access, and known limitations |
| [apple-testing.md](apple-testing.md) | iOS simulator testing strategy |

## What Is Implemented in DL-036

- Explicit `iosArm64`, `iosSimulatorArm64`, and `iosX64` targets in every
  relevant module.
- Shared `iosMain` and `iosTest` source-set hierarchy.
- `dataloom-apple` umbrella module with XCFramework assembly.
- DataLoom XCFramework (module name: `DataLoom`, bundle ID:
  `io.dataloom.sdk`, static linkage).
- Swift smoke-test fixture that validates compile-time visibility of public
  types.
- macOS GitHub Actions CI that validates Apple compilation, simulator tests,
  XCFramework assembly, and Swift import.
- `dataloom-testing` is intentionally absent from the production XCFramework.

## What Is NOT Implemented in DL-036

The following capabilities require separate approved issues:

- Apple connectivity provider (`NWPathMonitor`)
- Apple background scheduler (`BGTaskScheduler`)
- Apple persistent queue provider (Core Data / SQLite)
- iOS production sample application or SwiftUI app
- CocoaPods publication
- Remote Swift Package Manager publication
- App Store packaging or signing
- Experimental Swift export as the mandatory integration path

## Platform Support Summary

| Platform | Target | Status |
|---|---|---|
| Physical iPhone / iPad | `iosArm64` | ✓ DL-036 |
| Apple-silicon iOS Simulator | `iosSimulatorArm64` | ✓ DL-036 |
| Intel iOS Simulator | `iosX64` | ✓ DL-036 |
| Android | `android` | Separate issues |
| JVM | `jvm` | ✓ Existing |
