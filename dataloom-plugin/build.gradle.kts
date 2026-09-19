// DataLoom plugin engine module (io.dataloom:dataloom-plugin).
//
// This module owns #98 (DL-044 plugin platform)'s runtime engine: deny-by-
// default plugin registration and dependency resolution (PluginRegistry),
// lifecycle state tracking and authorized transitions (PluginLifecycleState-
// Tracker and its request/decision/result types), bounded execution
// (PluginExecutionBoundsEnforcer), and the lifecycle audit bridge. It builds on
// the behavior-free contracts in dataloom-plugin-api, which stays the SPI that
// plugin authors depend on. See docs/adr/ADR-0003-plugin-engine-module.md.
//
// Rules:
// - May depend on dataloom-model, dataloom-plugin-api, dataloom-api, and
//   kotlinx-coroutines.
// - Must not depend on dataloom-core, dataloom-runtime, or dataloom-testing.
// - Public API is consumed directly by dataloom-runtime's public surface
//   (DataLoom.plugins), so every public type here is a published, stable type
//   and none may reference an internal engine namespace.
plugins {
    id("io.dataloom.kotlin.multiplatform-library")
}

kotlin {
    explicitApi()

    sourceSets {
        commonMain {
            dependencies {
                api(project(":dataloom-model"))
                api(project(":dataloom-plugin-api"))
                api(project(":dataloom-api"))
                // PluginExecutionBoundsEnforcer: coroutine timeout cancellation
                // (withTimeoutOrNull) and concurrency limiting
                // (kotlinx.coroutines.sync.Semaphore), the same library and
                // mechanism io.dataloom.runtime.retry.CoroutineRetryTimeoutExecutor
                // uses for provider timeouts.
                implementation(libs.kotlinx.coroutines.core)
            }
        }
        commonTest {
            dependencies {
                implementation(libs.kotlinx.coroutines.test)
            }
        }
    }
}
