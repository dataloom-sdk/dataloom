import com.android.build.api.dsl.KotlinMultiplatformAndroidLibraryTarget

// DataLoom configuration module.
//
// Published as `dataloom-config` per ADR-0002: typed configuration,
// versioned immutable snapshots, deterministic precedence/rollback history,
// and integrity checksums. Moved out of dataloom-api (#236/#93) into its
// own artifact/module — it had zero callers outside its own package and
// depends only on dataloom-model, so it was never actually coupled to
// dataloom-api; the split now matches ADR-0002's intended graph, where
// dataloom-config is a peer of dataloom-api, not a sub-dependency of it.
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
                namespace = "io.dataloom.config"
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
