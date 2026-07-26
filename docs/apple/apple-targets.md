# DataLoom Apple Targets (DL-036)

## Supported Targets

DataLoom declares three explicit Apple targets in every relevant KMP module:

| Target | Architecture | Purpose |
|---|---|---|
| `iosArm64` | ARM64 | Physical iPhone and iPad devices |
| `iosSimulatorArm64` | ARM64 | Apple-silicon iOS simulator |
| `iosX64` | x86_64 | Intel iOS simulator (Rosetta / legacy runners) |

These targets are declared explicitly.  Deprecated shortcuts such as `ios()`,
`presets`, or manual source-set duplication are not used.

## iosX64 Inclusion

`iosX64` is included because:

- Kotlin 2.4.10 supports `iosX64` on the macOS CI runner.
- It enables Intel-simulator compatibility for developers on Intel Macs and
  for CI runners that use Rosetta.
- The Apple SDK and LLVM toolchain available on `macos-15` runners cover the
  `iosX64` target.

If `iosX64` becomes unsupported by a future Kotlin or Xcode version, document
the reason and remove it from all module declarations.

## Source-Set Hierarchy

Kotlin 2.4.10 applies the default hierarchy template automatically when
`iosArm64`, `iosSimulatorArm64`, and `iosX64` are declared.  The resulting
shared source sets are:

```
commonMain
└── nativeMain
    └── appleMain
        └── iosMain         ← shared by all three iOS targets
            ├── iosArm64Main
            ├── iosSimulatorArm64Main
            └── iosX64Main

commonTest
└── nativeTest
    └── appleTest
        └── iosTest         ← shared test source set
            ├── iosArm64Test
            ├── iosSimulatorArm64Test
            └── iosX64Test
```

## Affected Modules

Apple targets are added to:

| Module | Role |
|---|---|
| `dataloom-api` | Stable public contracts |
| `dataloom-core` | Platform-independent runtime foundations |
| `dataloom-runtime` | Synchronization runtime and facade |
| `dataloom-testing` | Test utilities (not exported to XCFramework) |
| `dataloom-apple` | Apple umbrella/export module |

## Host Restrictions

Apple targets are declared **only on macOS hosts** because:

- Kotlin/Native linking for Apple targets requires the Apple SDK (`ld`,
  `xcrun`, `lipo`) which is only available on macOS.
- iOS simulator test execution requires macOS.
- XCFramework assembly requires Xcode's `xcodebuild`.

On Linux and Windows, the convention plugin declares only the JVM target.
The macOS CI job validates all Apple-specific compilation and testing.

## Common-Code Compatibility

All production `commonMain` source files were audited for DL-036-scoped
JVM-only API usage.  No `java.*`, `javax.*`, `java.time`, `System.currentTimeMillis()`,
`UUID.randomUUID()`, JVM reflection, JVM synchronization primitives, Android
classes, or Android annotations were found in any `commonMain` source set.

Existing common abstractions (`DataLoomClock`, `IdentifierGenerator`) are
used throughout for clock reads and identifier generation, making the common
code platform-independent.

## Convention Plugin

Apple targets are configured in:

```
build-logic/src/main/kotlin/io.dataloom.kotlin.multiplatform-library.gradle.kts
```

The plugin detects the host operating system and declares iOS targets only on
macOS.  The JVM target is always declared on all hosts.
