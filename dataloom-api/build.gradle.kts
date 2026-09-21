import com.android.build.api.dsl.KotlinMultiplatformAndroidLibraryTarget

// DataLoom API module.
//
// This module will house stable public contracts, canonical models,
// public error types, public configuration contracts, and provider
// and plugin contracts in future issues.
//
// Rules:
// - Must remain platform-independent.
// - Must not depend on any other DataLoom implementation module.
// - Must not contain runtime implementations.
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
                namespace = "io.dataloom.api"
                compileSdk = libs.versions.android.compileSdk.get().toInt()
                minSdk = libs.versions.android.minSdk.get().toInt()
                withHostTest {}
            }
    }

    sourceSets {
        commonMain {
            dependencies {
                api(project(":dataloom-model"))
                api(project(":dataloom-provider-api"))
                api(project(":dataloom-config"))
                // AppLifecycleProvider.states() exposes kotlinx.coroutines.flow.Flow,
                // the only coroutines type in this module's public API (ADR-0007).
                api(libs.kotlinx.coroutines.core)
            }
        }
        commonTest {
            dependencies {
                implementation(libs.kotlinx.coroutines.test)
            }
        }
    }
}
