pluginManagement {
    // build-logic provides KMP convention plugins.
    includeBuild("build-logic")

    // Resolve implementation modules directly. This removes a fragile marker
    // lookup while preserving the catalog-controlled versions.
    resolutionStrategy {
        eachPlugin {
            when (requested.id.id) {
                "org.jetbrains.kotlin.multiplatform" ->
                    useModule(
                        "org.jetbrains.kotlin:kotlin-gradle-plugin:${requested.version}",
                    )

                "org.jetbrains.kotlin.jvm" ->
                    useModule(
                        "org.jetbrains.kotlin:kotlin-gradle-plugin:${requested.version}",
                    )

                "com.android.library" ->
                    useModule(
                        "com.android.tools.build:gradle:${requested.version}",
                    )

                "com.google.devtools.ksp" ->
                    useModule(
                        "com.google.devtools.ksp:symbol-processing-gradle-plugin:${requested.version}",
                    )
            }
        }
    }

    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        google()
    }
}

rootProject.name = "dataloom"

include(
    ":dataloom-model",
    ":dataloom-provider-api",
    ":dataloom-plugin-api",
    ":dataloom-config",
    ":dataloom-api",
    ":dataloom-storage-sqldelight",
    ":dataloom-core",
    ":dataloom-runtime",
    ":dataloom-testing",
    ":dataloom-transport-ktor",
    ":dataloom-transport-graphql",
    ":dataloom-transport-retrofit",
    ":dataloom-transport-grpc",
    ":runtime-external-consumer",
    ":dataloom-storage-file",
)

// Android implementation modules.
//
// These modules require the Android SDK, Android Gradle Plugin, and network
// access to the Google Maven repository (dl.google.com). They are included
// when the DATALOOM_ANDROID_BUILD environment variable is set to "true".
//
// On CI the android-validation job sets DATALOOM_ANDROID_BUILD=true and
// builds these modules independently.
//
// Modules are independently consumable:
//   - dataloom-connectivity-android — Android ConnectivityProvider
//   - dataloom-scheduler-workmanager — WorkManager SchedulerProvider and worker bridge
//   - dataloom-queue-room — Room QueueProvider
//   - dataloom-storage-room — Room StorageProvider
//   - dataloom-storage-sqldelight-android — Android AndroidSqliteDriver wiring
//     for dataloom-storage-sqldelight (JVM + iOS module, always included above;
//     split out because AGP 9.0+ does not allow com.android.library in the
//     same module as org.jetbrains.kotlin.multiplatform)
//   - dataloom-android — real, production platform artifact aggregating the
//     provider modules above into one convenience dependency, per #101's
//     required "stable dataloom-android...platform artifact" (DL-039A);
//     see docs/android/dataloom-android.md
//   - runtime-android-reference-consumer — compile-only proof that the four
//     provider modules above compose with DataLoomBuilder (#101/DL-039A);
//     see docs/android/reference-consumer.md
//
// See docs/android/README.md for integration guidance.
val isAndroidBuildEnabled: Boolean =
    System.getenv("DATALOOM_ANDROID_BUILD") == "true"

if (isAndroidBuildEnabled) {
    include(
        ":dataloom-connectivity-android",
        ":dataloom-scheduler-workmanager",
        ":dataloom-queue-room",
        ":dataloom-storage-room",
        ":dataloom-storage-datastore",
        ":dataloom-storage-sqldelight-android",
        ":dataloom-android",
        ":runtime-android-reference-consumer",
    )
}

// dataloom-apple assembles the DataLoom XCFramework for Apple platforms.
// It is included only on macOS hosts because XCFramework assembly and
// Kotlin/Native linking for Apple targets require the Apple SDK and Xcode.
// The macOS CI job validates Apple compilation, simulator tests, and
// XCFramework assembly.  See docs/apple/xcframework-integration.md.

val isAppleHost: Boolean = run {
    val osName = System.getProperty("os.name") ?: ""
    osName.lowercase().contains("mac")
}

val isAppleKlibCrossCompileEnabled: Boolean =
    providers.gradleProperty("dataloom.appleKlibCrossCompile")
        .orNull
        ?.toBoolean() == true

if (isAppleHost || isAppleKlibCrossCompileEnabled) {
    include(":dataloom-apple")

    // dataloom-platform-ios -- first bounded slice of the eventual
    // dataloom-ios platform artifact (#101 / DL-039A): a real
    // ConnectivityProvider implementation for iOS. Gated the same way as
    // dataloom-apple above since it declares only iosArm64/
    // iosSimulatorArm64/iosX64 targets. See
    // docs/apple/connectivity-provider.md.
    include(":dataloom-platform-ios")

    // runtime-ios-reference-consumer -- compile-only proof that
    // dataloom-platform-ios's AppleDataLoomProviders/appleDataLoomProviders/
    // installAppleProviders helpers compose with DataLoomBuilder (#101 /
    // DL-039A), mirroring runtime-android-reference-consumer's role for the
    // Android path. Gated the same way as dataloom-platform-ios above since
    // its consuming code lives in src/iosMain and depends on that module.
    // See docs/apple/reference-consumer.md.
    include(":runtime-ios-reference-consumer")
}
