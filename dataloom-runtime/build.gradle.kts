import com.android.build.api.dsl.KotlinMultiplatformAndroidLibraryTarget

// DataLoom Runtime module.
//
// This module will house the future synchronization runtime, workflow
// orchestration, and engine coordination.
//
// Rules:
// - May depend on dataloom-api, dataloom-core, and dataloom-plugin.
// - Must not depend on dataloom-testing.
// - Must not expose internal implementation types publicly.
//
// Explicit Android KMP target: see docs/android/kmp-android-target-blocker.md
// (env-gated on DATALOOM_ANDROID_BUILD; plugin applied by bare id, no version).
// dataloom-plugin and dataloom-assets have no Android target of their own yet;
// this module's Android target still resolves them, falling back to their
// jvm() variant (see the blocker doc's "findings that shape the roll-out").
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
                namespace = "io.dataloom.runtime"
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
                api(project(":dataloom-api"))
                // DataLoom.pluginEngine's public signatures use dataloom-plugin's
                // result/request types and dataloom-plugin-api's identifiers.
                api(project(":dataloom-plugin-api"))
                api(project(":dataloom-plugin"))
                // DataLoom.assetTransfer's public signature uses dataloom-assets'
                // AssetTransferEngine.
                api(project(":dataloom-assets"))
                implementation(project(":dataloom-core"))
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
