// Settings for the build-logic-android included build.
//
// This included build provides the io.dataloom.android.library convention
// plugin, which configures Android library modules (com.android.library +
// org.jetbrains.kotlin.android together in one classloader).
//
// It is included only when DATALOOM_ANDROID_BUILD=true so that the Android
// Gradle Plugin (and its dl.google.com download) is never triggered in
// KMP-only builds.
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
        google()
    }
    // Share the root project version catalog so that dependency versions
    // stay in a single place.
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "build-logic-android"
