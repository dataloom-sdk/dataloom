# Swift XCFramework smoke fixture

> **Audience:** Maintainers reproducing the Apple compile-smoke check
> **Purpose:** Verify that selected current symbols are importable from the
> locally assembled `DataLoom` XCFramework
> **Status:** Compile-only fixture; not an executable application or production
> Swift qualification

[Apple platform guide](../docs/apple/README.md) ·
[XCFramework integration](../docs/apple/xcframework-integration.md) ·
[Swift interoperability](../docs/apple/swift-interop.md)

## What it checks

The Xcode project imports `DataLoom` and references selected symbols from the
builder, synchronization contracts, worker/submission capabilities, provider
protocols, observer contract, models, and current runtime dependency surface.

The framework currently exports `dataloom-core`, and the fixture deliberately
references some core types. A successful build therefore records the present
surface; it does not prove that internal implementation types have been
removed.

## Contents

| Path | Purpose |
|---|---|
| `DataLoomSwiftSmoke.xcodeproj/` | Minimal compile-only Xcode project |
| `Sources/DataLoomSwiftSmoke/DataLoomSwiftSmoke.swift` | Selected type-visibility assertions |
| `DataLoom.xcframework/` | Generated input artifact; ignored by Git |

The project currently targets iOS 15 and Swift 5.9.

## Prerequisites

- macOS with Xcode command-line tools and an iOS Simulator SDK.
- JDK 17 or newer for Gradle.
- Repository checkout with the Gradle Wrapper executable.

## Run locally

From the repository root:

```bash
./gradlew :dataloom-apple:assembleDataLoomReleaseXCFramework

cp -R dataloom-apple/build/XCFrameworks/release/DataLoom.xcframework \
    apple-smoke/DataLoom.xcframework

cd apple-smoke
xcodebuild build \
    -scheme DataLoomSwiftSmoke \
    -destination 'generic/platform=iOS Simulator' \
    CODE_SIGNING_ALLOWED=NO \
    SKIP_INSTALL=YES
```

A successful command confirms only that the selected source compiles against
the assembled simulator framework.

## What it does not check

- No runnable iOS application is produced.
- No synchronization operation executes.
- No real network, database, Keychain, filesystem, or background API is used.
- No provider lifecycle, cancellation, process termination, or relaunch is
  exercised.
- No generated-header compatibility diff or complete internal-type audit runs.
- No KMP iOS consumer path is exercised.
- No production credentials or personal data are present.

The fixture cannot qualify offline-first, remote-first, cache-first,
network-only, hybrid, or adaptive behavior. Native Swift distribution remains
optional and separate from mandatory KMP iOS support.

## Generated artifact policy

`apple-smoke/DataLoom.xcframework/` is generated and ignored. Do not commit it.
