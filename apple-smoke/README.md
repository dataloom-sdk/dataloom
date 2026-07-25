# DataLoom Swift Smoke Test Fixture

This directory contains a minimal Xcode project that validates Swift
interoperability for the DataLoom XCFramework (DL-036).

## Purpose

The smoke fixture verifies that:

- The `DataLoom` framework can be imported from Swift.
- Public facade symbols (`DataLoom`, `DataLoomBuilder`) are visible.
- Key request and result types are accessible from Swift.
- Provider interface types are visible.
- No JVM-only or internal types leak into the exported API.

## Contents

| Path | Description |
|---|---|
| `DataLoomSwiftSmoke.xcodeproj/` | Minimal Xcode project |
| `Sources/DataLoomSwiftSmoke/DataLoomSwiftSmoke.swift` | Swift type-visibility assertions |
| `DataLoom.xcframework/` | Generated framework (not committed; assembled by CI) |

## Prerequisites

- macOS with Xcode 15 or later
- DataLoom XCFramework assembled via Gradle

## Running the Smoke Test

```bash
# 1. Assemble the XCFramework from the repository root
./gradlew :dataloom-apple:assembleDataLoomReleaseXCFramework

# 2. Copy the XCFramework into this directory
cp -R dataloom-apple/build/XCFrameworks/release/DataLoom.xcframework \
      apple-smoke/DataLoom.xcframework

# 3. Build the smoke fixture (no signing required)
cd apple-smoke
xcodebuild build \
    -scheme DataLoomSwiftSmoke \
    -destination 'generic/platform=iOS Simulator' \
    CODE_SIGNING_ALLOWED=NO \
    SKIP_INSTALL=YES
```

A successful build confirms that the DataLoom public API is importable and
correctly exported from the XCFramework.

## What the Smoke Test Does Not Do

- Does not produce a runnable iOS application.
- Does not access real networking, databases, or keychain.
- Does not include production credentials or personal data.
- Does not start a background `CoroutineScope` or global state.
- Does not test runtime synchronization behavior (covered by Kotlin tests).

## Generated Artifacts

`DataLoom.xcframework/` is generated during the smoke test and must not be
committed to the repository (see `.gitignore`).
