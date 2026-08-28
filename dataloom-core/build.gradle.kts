// DataLoom Core module.
//
// This module provides internal, platform-independent foundations shared
// by runtime components.
//
// Rules:
// - May depend on dataloom-api.
// - Must not depend on dataloom-runtime or dataloom-testing.
// - Internal implementation details must not be exposed as public API.
plugins {
    id("io.dataloom.kotlin.multiplatform-library")
}

kotlin {
    sourceSets {
        commonMain {
            dependencies {
                implementation(project(":dataloom-model"))
                implementation(project(":dataloom-provider-api"))
                implementation(project(":dataloom-plugin-api"))
                implementation(project(":dataloom-api"))
                // Needed for #98's plugin execution-bounds enforcement
                // (PluginExecutionBoundsEnforcer): coroutine timeout
                // cancellation (withTimeoutOrNull) and concurrency limiting
                // (kotlinx.coroutines.sync.Semaphore), mirroring
                // io.dataloom.runtime.retry.CoroutineRetryTimeoutExecutor's
                // own use of the same library for the same purpose.
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
