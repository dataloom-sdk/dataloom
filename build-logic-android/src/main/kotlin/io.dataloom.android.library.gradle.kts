/**
 * Precompiled script plugin for DataLoom Android library modules.
 *
 * Applying this plugin configures:
 * - Android library plugin (com.android.library) with compileSdk, minSdk,
 *   testInstrumentationRunner, and consumerProguardFiles defaults.
 * - Centralized compileSdk and minSdk retrieved directly from the root version catalog.
 * - Java and Kotlin compatibility set to 17.
 * - Unit-test resources included in local JVM unit tests.
 * - Release build type with minification disabled.
 *
 * Plugin ID: io.dataloom.android.library
 */

plugins {
    id("com.android.library")
}

android {
    val compileSdkStr = libs.versions.android.compileSdk.get()
    compileSdk = compileSdkStr.toIntOrNull()
        ?: throw GradleException("Invalid or missing 'android-compileSdk' in version catalog (found '$compileSdkStr')")

    defaultConfig {
        val minSdkStr = libs.versions.android.minSdk.get()
        minSdk = minSdkStr.toIntOrNull()
            ?: throw GradleException("Invalid or missing 'android-minSdk' in version catalog (found '$minSdkStr')")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}
