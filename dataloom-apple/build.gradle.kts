// DataLoom Apple umbrella module.
//
// This module is the Apple distribution boundary for the DataLoom SDK.
// It assembles one XCFramework named "DataLoom" that exports the three
// production modules (dataloom-api, dataloom-core, dataloom-runtime).
//
// Rules:
// - Export only dataloom-api, dataloom-core, and dataloom-runtime.
// - Never export dataloom-testing.
// - Contain no synchronization implementation.
// - Contain no provider implementation.
// - Contain no global singleton or service locator.
// - Remain a thin Apple distribution boundary.
//
// XCFramework:
//   Module name : DataLoom
//   Bundle ID   : io.dataloom.sdk
//   Linkage     : static
//   Targets     : iosArm64, iosSimulatorArm64, iosX64
//
// Static linkage is chosen so that host applications do not need to embed
// a separate dynamic framework.  Each slice is self-contained.
//
// Assembly task:
//   ./gradlew :dataloom-apple:assembleDataLoomReleaseXCFramework
//
// This module is only included in the build on macOS hosts (enforced in
// settings.gradle.kts).  See docs/apple/xcframework-integration.md.
import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    val dataLoomXCFramework = XCFramework("DataLoom")

    // Declare explicit Apple targets.  iosX64 covers the Intel iOS simulator
    // for Rosetta compatibility.  All three targets are required for a
    // complete "fat" XCFramework that supports physical devices and simulators.
    listOf(
        iosArm64(),          // physical iPhone / iPad devices
        iosSimulatorArm64(), // Apple-silicon iOS simulator
        iosX64(),            // Intel iOS simulator (Rosetta / legacy runner)
    ).forEach { target ->
        target.binaries.framework {
            baseName = "DataLoom"
            // Stable bundle identifier used across all slices.
            binaryOption("bundleId", "io.dataloom.sdk")
            // Static linkage: host applications link the framework at build
            // time; no dynamic library embedding is required.
            isStatic = true
            dataLoomXCFramework.add(this)

            // Export all public API surface required by Swift consumers.
            // dataloom-testing is intentionally absent so that test utilities
            // are never packaged into the production framework.
            export(project(":dataloom-api"))
            export(project(":dataloom-core"))
            export(project(":dataloom-runtime"))
        }
    }

    sourceSets {
        commonMain {
            dependencies {
                // api() ensures exported declarations appear in generated headers.
                api(project(":dataloom-api"))
                api(project(":dataloom-core"))
                api(project(":dataloom-runtime"))
            }
        }
    }
}
