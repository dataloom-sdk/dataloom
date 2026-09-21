import com.android.build.api.dsl.KotlinMultiplatformAndroidLibraryTarget

// DataLoom Core module.
//
// This module provides internal, platform-independent foundations shared
// by runtime components.
//
// Rules:
// - May depend on dataloom-api.
// - Must not depend on dataloom-runtime or dataloom-testing.
// - Internal implementation details must not be exposed as public API.
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
    if (androidTargetEnabled) {
        (this as ExtensionAware).extensions
            .configure<KotlinMultiplatformAndroidLibraryTarget>("androidLibrary") {
                namespace = "io.dataloom.core"
                compileSdk = libs.versions.android.compileSdk.get().toInt()
                minSdk = libs.versions.android.minSdk.get().toInt()
                withHostTest {}
            }
    }

    sourceSets {
        commonMain {
            dependencies {
                implementation(project(":dataloom-model"))
                implementation(project(":dataloom-provider-api"))
                implementation(project(":dataloom-api"))
            }
        }
    }
}
