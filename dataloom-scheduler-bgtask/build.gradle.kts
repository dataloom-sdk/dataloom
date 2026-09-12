// DataLoom BackgroundTasks scheduler bridge (#101 / DL-039A).
//
// Apple counterpart to dataloom-scheduler-workmanager's DataLoomCoroutineWorker
// / DataLoomWorkerFactory bridge: DataLoomBackgroundTaskHandler executes one
// queueWorker.run() cycle from inside a host app's BGTaskScheduler launch
// handler and reports completion back to the platform via
// BGTask.setTaskCompletedWithSuccess. It does not itself register or submit
// any BGTaskRequest -- AppleSchedulerProvider (dataloom-platform-ios) already
// owns schedule()/cancel(); this module owns only the "what happens when the
// OS actually wakes the app up" half.
//
// Why a separate module rather than dataloom-platform-ios:
// dataloom-platform-ios's own build rules forbid leaking BGTaskScheduler,
// BGTaskRequest, or "any other Apple platform type" through its public API.
// DataLoomBackgroundTaskHandler's entire purpose is to accept a real BGTask
// from the host app's launch-handler closure, so it cannot live there --
// exactly the same reasoning that keeps DataLoomCoroutineWorker (which
// accepts real android.content.Context / androidx.work.WorkerParameters) out
// of dataloom-android and inside its own dataloom-scheduler-workmanager
// module instead.
//
// See docs/apple/background-task-handler.md.
//
// Rules:
// - May depend on dataloom-api, dataloom-runtime, and kotlinx-coroutines-core.
// - Must not depend on dataloom-platform-ios, dataloom-core internals, or
//   dataloom-testing.
// - DataLoomBackgroundTaskHandler lives in src/iosMain (not src/commonMain):
//   BGTask is an Apple-only platform type with no commonMain equivalent, so
//   there is nothing to abstract behind an expect/actual boundary here --
//   unlike AppleSchedulerProvider's internal BackgroundTaskGateway, this
//   class's whole point is to accept that platform type directly.
// - Targets: iosArm64, iosSimulatorArm64, iosX64 -- matching
//   dataloom-platform-ios's target declaration style. Included in the Gradle
//   build only on macOS hosts, or when klib cross-compilation is explicitly
//   requested with -Pdataloom.appleKlibCrossCompile=true (see
//   settings.gradle.kts), the same convention dataloom-platform-ios already
//   uses.
import org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation

plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

@OptIn(ExperimentalAbiValidation::class)
kotlin {
    explicitApi()
    abiValidation()

    iosArm64()
    iosSimulatorArm64()
    iosX64()

    sourceSets {
        commonMain {
            dependencies {
                // DataLoomError and other canonical API types reached through
                // dataloom-runtime's queue-worker result types.
                api(project(":dataloom-api"))
                // DataLoomQueueWorker, QueueWorkerRunRequest, QueueWorkerRunResult.
                api(project(":dataloom-runtime"))
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
