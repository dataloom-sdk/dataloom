// Apple process-termination/relaunch CI proof harness (#94).
//
// This module exists solely so a real, launchable iOS Simulator app can link
// against a small, deliberately narrow Objective-C/Swift-visible surface and
// perform one genuine production circuit-breaker write on launch. It is not
// a production distribution module: it is never included in
// dataloom-apple's "DataLoom" XCFramework, and no host application is
// expected to depend on it directly. See docs/apple/process-termination-proof.md.
//
// Why a separate module rather than adding this to dataloom-runtime or
// dataloom-apple:
// - dataloom-apple's XCFramework export list is a deliberately fixed,
//   reviewed production surface (dataloom-model, dataloom-provider-api,
//   dataloom-api, dataloom-runtime only). This harness must not become part
//   of that shipped surface.
// - Keeping this module's own public API to two primitive-typed
//   declarations (ProcessTerminationProofState, a plain data holder of
//   String/Int/Long fields, and AppleCircuitBreakerProcessTerminationProof,
//   a singleton with two methods taking/returning only String and that data
//   holder) keeps the generated Objective-C/Swift surface small and
//   predictable. dataloom-api's and dataloom-runtime's own richer types
//   (CircuitBreakerScope, CircuitBreakerState, sealed CircuitBreakerLoadResult/
//   CircuitBreakerCompareAndSetResult, ProviderOperationResult, ...) are used
//   internally but deliberately never appear in this module's own exported
//   signatures, so this module depends on dataloom-api/dataloom-runtime with
//   `implementation`, not `api` -- nothing about those modules' own types is
//   re-exported into this module's generated Objective-C header.
//
// Real production code exercised: AppleFileCircuitBreakerStateStore
// (dataloom-runtime, src/iosMain/kotlin/io/dataloom/runtime/retry/), the
// exact store CircuitBreakerCoordinator uses in production. This proof
// harness drives it directly rather than through the full
// CircuitBreakerExecutionGate/CircuitBreakerCoordinator pair (unlike
// Android's CircuitBreakerProcessTerminationContentProvider, which drives
// real failures through CircuitBreakerExecutionGate) specifically to keep
// this harness's own Kotlin/Swift-interop surface to the single
// primitive-typed method described above. See the module doc for the exact
// scope reduction and a named follow-up to close that gap.
//
// Targets: iosArm64, iosSimulatorArm64, iosX64 -- matching dataloom-apple's
// target declaration style, and gated identically in settings.gradle.kts
// (macOS host, or -Pdataloom.appleKlibCrossCompile=true for Windows/Linux
// klib cross-compilation verification only -- actual framework *linking*
// still requires a macOS host/toolchain and has not been performed from this
// Windows environment; see the module doc's verification section).
import org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation
import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

@OptIn(ExperimentalAbiValidation::class)
kotlin {
    explicitApi()
    abiValidation()

    val proofXCFramework = XCFramework("DataLoomProcessTerminationProof")

    listOf(
        iosArm64(),          // physical iPhone / iPad devices (unused by the simctl proof, kept for parity)
        iosSimulatorArm64(), // Apple-silicon iOS simulator -- what macos-15 CI runners actually execute
        iosX64(),            // Intel iOS simulator (Rosetta / legacy runner)
    ).forEach { target ->
        target.binaries.framework {
            baseName = "DataLoomProcessTerminationProof"
            binaryOption("bundleId", "io.dataloom.processterminationproof.kmp")
            isStatic = true
            proofXCFramework.add(this)
        }
    }

    sourceSets {
        commonMain {
            dependencies {
                // Not exported -- see the file header. Used only to build
                // the internal circuit-breaker write/read path.
                implementation(project(":dataloom-api"))
                implementation(project(":dataloom-runtime"))
            }
        }
        commonTest {
            dependencies {
                implementation(kotlin("test"))
            }
        }
        matching { it.name == "iosMain" }.configureEach {
            dependencies {
                implementation(libs.kotlinx.coroutines.core)
            }
        }
        matching { it.name == "iosTest" }.configureEach {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.kotlinx.coroutines.test)
            }
        }
    }
}
