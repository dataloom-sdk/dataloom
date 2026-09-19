@file:OptIn(ExperimentalKotlinGradlePluginApi::class)

import com.android.build.api.dsl.KotlinMultiplatformAndroidLibraryTarget
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType

// DataLoom foundational model module.
//
// This is the bottom of the production dependency graph. It contains only
// platform-neutral value types, identifiers, canonical errors, metadata, and
// time contracts. It must not depend on another DataLoom module.
//
// PILOT: this is the first shared KMP module to declare an explicit Android
// target, using AGP's dedicated com.android.kotlin.multiplatform.library
// plugin (not com.android.library, which AGP 9 forbids beside
// org.jetbrains.kotlin.multiplatform). See
// docs/android/kmp-android-target-blocker.md for why the plugin is applied
// by bare id (no version) and how to roll it out to the other shared modules.
plugins {
    id("io.dataloom.kotlin.multiplatform-library")
}

// Gated on the same switch that includes every other Android module in
// settings.gradle.kts, so a default JVM/iOS build still needs no Android SDK.
val androidTargetEnabled: Boolean =
    System.getenv("DATALOOM_ANDROID_BUILD") == "true"

if (androidTargetEnabled) {
    // Applied by BARE id: the root build already puts the AGP artifact on the
    // classpath (build.gradle.kts, `alias(libs.plugins.android.library)
    // apply false`). Adding a `version` here (or using a catalog alias, which
    // carries one) makes Gradle fail with "already on the classpath with an
    // unknown version".
    apply(plugin = "com.android.kotlin.multiplatform.library")
}

kotlin {
    explicitApi()

    // The JVM `System*` implementations (clock, secure random, digest, HMAC)
    // only use java.* APIs that Android also provides, so they live in a
    // source set shared by the JVM and Android targets. The group exists (with
    // only the JVM target) even when the Android target is not enabled.
    applyDefaultHierarchyTemplate {
        common {
            group("jvmAndroid") {
                withJvm()
                // The new AGP plugin's target is not matched by withAndroidTarget().
                withCompilations { it.platformType == KotlinPlatformType.androidJvm }
            }
        }
    }

    if (androidTargetEnabled) {
        (this as ExtensionAware).extensions
            .configure<KotlinMultiplatformAndroidLibraryTarget>("androidLibrary") {
                namespace = "io.dataloom.model"
                compileSdk = libs.versions.android.compileSdk.get().toInt()
                minSdk = libs.versions.android.minSdk.get().toInt()

                // Runs commonTest and jvmAndroidTest on the host JVM against
                // the Android-target classpath.
                withHostTest {}
            }
    }
}
