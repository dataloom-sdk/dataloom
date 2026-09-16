// Apple process-termination/relaunch CI proof harness (#94).
//
// This module exists solely so real, launchable iOS Simulator apps can link
// against a small, deliberately narrow Objective-C/Swift-visible surface and
// perform one genuine production write on launch: a circuit-breaker write
// (round 31) or a durable retry-budget write (this round, mirroring the
// circuit-breaker proof mechanically the same way the Android retry-budget
// proof extended the Android circuit-breaker proof). It is not a production
// distribution module: it is never included in dataloom-apple's "DataLoom"
// XCFramework, and no host application is expected to depend on it directly.
// See docs/apple/process-termination-proof.md.
//
// Why a separate module rather than adding this to dataloom-runtime or
// dataloom-apple:
// - dataloom-apple's XCFramework export list is a deliberately fixed,
//   reviewed production surface (dataloom-model, dataloom-provider-api,
//   dataloom-api, dataloom-runtime only). This harness must not become part
//   of that shipped surface.
// - Keeping this module's own public API to primitive-typed declarations --
//   ProcessTerminationProofState / RetryBudgetProcessTerminationProofState,
//   plain data holders of String/Int/Long fields, and
//   AppleCircuitBreakerProcessTerminationProof /
//   AppleRetryBudgetProcessTerminationProof, singletons whose methods take
//   and return only String/Boolean and those data holders -- keeps the
//   generated Objective-C/Swift surface small and predictable. dataloom-api's
//   and dataloom-runtime's own richer types (CircuitBreakerScope,
//   CircuitBreakerState, QueueEntry, RetryBudgetState, sealed
//   CircuitBreakerLoadResult/CircuitBreakerCompareAndSetResult/
//   QueueAcquireResult, ProviderOperationResult, ...) are used internally but
//   deliberately never appear in this module's own exported signatures, so
//   this module depends on dataloom-api/dataloom-runtime with
//   `implementation`, not `api` -- nothing about those modules' own types is
//   re-exported into this module's generated Objective-C header.
//
// Real production code exercised:
// - AppleFileCircuitBreakerStateStore (dataloom-runtime, src/iosMain/kotlin/
//   io/dataloom/runtime/retry/), the exact store CircuitBreakerCoordinator
//   uses in production. AppleCircuitBreakerProcessTerminationProof drives it
//   directly rather than through the full CircuitBreakerExecutionGate/
//   CircuitBreakerCoordinator pair (unlike Android's
//   CircuitBreakerProcessTerminationContentProvider, which drives real
//   failures through CircuitBreakerExecutionGate) specifically to keep this
//   harness's own Kotlin/Swift-interop surface to the single primitive-typed
//   method described above. See the module doc for the exact scope
//   reduction and a named follow-up to close that gap.
// - AppleFileQueueProvider (dataloom-runtime, src/iosMain/kotlin/io/dataloom/
//   runtime/queue/), the exact store the real synchronization queue uses to
//   persist QueueEntry.retryAttempt/retryBudgetState in production.
//   AppleRetryBudgetProcessTerminationProof drives its real
//   `enqueue -> acquire -> reschedule -> acquire -> defer` sequence directly
//   -- the same sequence Android's RetryBudgetProcessTerminationContentProvider
//   drives through RoomQueueProvider -- with no further scope reduction to
//   make, since QueueProvider has no coordinator/execution-gate layer of its
//   own to bypass.
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
