pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
}

rootProject.name = "dataloom"

// Build-logic contains reusable convention plugins for all DataLoom modules.
includeBuild("build-logic")

include(
    ":dataloom-api",
    ":dataloom-core",
    ":dataloom-runtime",
    ":dataloom-testing",
)

// dataloom-apple assembles the DataLoom XCFramework for Apple platforms.
// It is included only on macOS hosts because XCFramework assembly and
// Kotlin/Native linking for Apple targets require the Apple SDK and Xcode.
// The macOS CI job validates Apple compilation, simulator tests, and
// XCFramework assembly.  See docs/apple/xcframework-integration.md.
//
// The host check uses the same DefaultNativePlatform API as the convention
// plugin so that platform detection is consistent across the entire build.
val isAppleHost: Boolean = run {
    val osName = System.getProperty("os.name") ?: ""
    osName.lowercase().contains("mac")
}

if (isAppleHost) {
    include(":dataloom-apple")
}
