# DataLoom XCFramework Integration (DL-036)

## Overview

The DataLoom SDK is distributed to Apple platforms as a single XCFramework.

| Property | Value |
|---|---|
| Framework name | `DataLoom` |
| Bundle identifier | `io.dataloom.sdk` |
| Linkage | Static |
| Module | `dataloom-apple` |
| Targets | `iosArm64`, `iosSimulatorArm64`, `iosX64` |

## Static Linkage

The XCFramework uses **static linkage** (`isStatic = true`).

Rationale:
- Host applications do not need to embed or copy a separate dynamic framework.
- Each slice is self-contained.
- Static linking avoids dynamic loader complexity in single-framework integrations.
- No Kotlin runtime duplication occurs because the SDK is delivered as a single
  XCFramework that includes all exported modules.

## Exported Modules

The XCFramework exports:

- `dataloom-api` — public contracts, identifiers, models, provider interfaces
- `dataloom-core` — runtime dependency container, provider registry, lifecycle
- `dataloom-runtime` — synchronization facade, pipelines, queue, retry, observer

`dataloom-testing` is intentionally **not exported** so that test utilities
(`InMemoryQueueProvider`, `FixedDataLoomClock`, etc.) are absent from the
production framework.

## Umbrella Module

`dataloom-apple` is the umbrella module:

```
dataloom-apple/
  build.gradle.kts          ← XCFramework and export configuration
  src/commonMain/kotlin/
    io/dataloom/apple/
      DataLoomAppleModule.kt  ← Minimal internal marker (required by Kotlin/Native)
```

The umbrella module:
- Uses `api()` dependencies so that exported declarations appear in generated headers.
- Contains no synchronization logic.
- Contains no provider implementation.
- Contains no global singleton or service locator.

## Local Assembly

Assemble the release XCFramework from the repository root on a macOS machine:

```bash
./gradlew :dataloom-apple:assembleDataLoomReleaseXCFramework
```

The output path:
```
dataloom-apple/build/XCFrameworks/release/DataLoom.xcframework/
```

The debug XCFramework:
```bash
./gradlew :dataloom-apple:assembleDataLoomDebugXCFramework
```

Output:
```
dataloom-apple/build/XCFrameworks/debug/DataLoom.xcframework/
```

## XCFramework Structure

A successfully assembled release XCFramework contains:

```
DataLoom.xcframework/
  Info.plist
  ios-arm64/
    DataLoom.framework/     ← physical device slice
  ios-arm64_x86_64-simulator/
    DataLoom.framework/     ← combined simulator slice
```

The simulator slice merges `iosSimulatorArm64` (ARM64) and `iosX64` (x86_64)
into one fat binary using `lipo`.

## Gradle Task Name

The exact Gradle task generated for the release XCFramework:

```bash
./gradlew :dataloom-apple:assembleDataLoomReleaseXCFramework
```

This task name follows Kotlin Multiplatform's `assemble<FrameworkName><BuildType>XCFramework`
naming convention where `FrameworkName = DataLoom` and `BuildType = Release`.

## Generated Artifacts

Generated XCFramework binaries must **not** be committed to the repository.
The following paths are listed in `.gitignore`:

```
*.xcframework/
*.xcarchive/
dataloom-apple/build/XCFrameworks/
apple-smoke/DataLoom.xcframework/
```

## Including in an Xcode Project

1. Assemble the XCFramework locally.
2. Drag `DataLoom.xcframework` into your Xcode project.
3. Embed as "Do Not Embed" (static linking).
4. `import DataLoom` in Swift files.

## Not Yet Available in DL-036

- SwiftPM remote package (requires separate issue)
- CocoaPods podspec (requires separate issue)
- GitHub release publication (requires separate issue)
- Apple signing or provisioning (requires separate issue)
