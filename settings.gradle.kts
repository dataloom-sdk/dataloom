pluginManagement {
    // Android implementation modules and the io.dataloom.android.library
    // convention plugin require the Android Gradle Plugin and access to
    // dl.google.com.  Both are gated on DATALOOM_ANDROID_BUILD=true so that
    // KMP-only builds (PR validation, JVM, Apple) never contact dl.google.com.
    val isAndroidBuildEnabled = System.getenv("DATALOOM_ANDROID_BUILD") == "true"

    // build-logic provides KMP convention plugins (io.dataloom.kotlin.*).
    // Declared here (inside pluginManagement) so that the plugins {} DSL in
    // every module can resolve it before any build script is evaluated.
    includeBuild("build-logic")

    // build-logic-android provides the io.dataloom.android.library convention
    // plugin.  It depends on the Android Gradle Plugin and is therefore only
    // included when DATALOOM_ANDROID_BUILD=true, keeping dl.google.com
    // entirely out of KMP-only builds.
    if (isAndroidBuildEnabled) {
        includeBuild("build-logic-android")
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
    ":dataloom-api",
    ":dataloom-core",
    ":dataloom-runtime",
    ":dataloom-testing",
)

// Android implementation modules.
//
// These modules require the Android SDK, Android Gradle Plugin, and network
// access to the Google Maven repository (dl.google.com). They are included
// when the DATALOOM_ANDROID_BUILD environment variable is set to "true".
//
// On CI the android-validation job sets DATALOOM_ANDROID_BUILD=true and
// builds the three modules independently.
//
// Modules are independently consumable:
//   - dataloom-connectivity-android — Android ConnectivityProvider
//   - dataloom-scheduler-workmanager — WorkManager SchedulerProvider and worker bridge
//   - dataloom-queue-room — Room QueueProvider
//
// See docs/android/README.md for integration guidance.
val isAndroidBuildEnabled: Boolean =
    System.getenv("DATALOOM_ANDROID_BUILD") == "true"

if (isAndroidBuildEnabled) {
    include(
        ":dataloom-connectivity-android",
        ":dataloom-scheduler-workmanager",
        ":dataloom-queue-room",
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

if (isAppleHost) {
    include(":dataloom-apple")
}
