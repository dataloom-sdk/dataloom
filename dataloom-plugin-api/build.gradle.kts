import com.android.build.api.dsl.KotlinMultiplatformAndroidLibraryTarget

// DataLoom plugin SPI module.
//
// This module contains the stable plugin manifest, permission, lifecycle,
// compatibility, and bounded-execution *contracts* required by #93 (DL-039)
// to freeze the V1 published artifact graph. It intentionally contains no
// plugin loading, registration, enforcement, isolation, or certification
// behavior — that engine is #98 (DL-044 plugin platform)'s job, built on
// top of these contracts. See docs/api/plugin-api.md for the exact scope
// boundary between this module and #98.
//
// Explicit Android KMP target: see docs/android/kmp-android-target-blocker.md
// (env-gated on DATALOOM_ANDROID_BUILD; plugin applied by bare id, no version).
plugins {
    id("io.dataloom.kotlin.multiplatform-library")
}

val androidTargetEnabled: Boolean =
    System.getenv("DATALOOM_ANDROID_BUILD") == "true"

if (androidTargetEnabled) {
    apply(plugin = "com.android.kotlin.multiplatform.library")
}

kotlin {
    explicitApi()

    if (androidTargetEnabled) {
        (this as ExtensionAware).extensions
            .configure<KotlinMultiplatformAndroidLibraryTarget>("androidLibrary") {
                namespace = "io.dataloom.plugin.api"
                compileSdk = libs.versions.android.compileSdk.get().toInt()
                minSdk = libs.versions.android.minSdk.get().toInt()
                withHostTest {}
            }
    }

    sourceSets {
        commonMain {
            dependencies {
                api(project(":dataloom-model"))
            }
        }
    }
}
