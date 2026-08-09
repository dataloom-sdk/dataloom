// DataLoom Room storage provider.
//
// Provides RoomStorageProvider — a generic AndroidX Room-backed
// StorageProvider implementation that persists opaque outbound and inbound
// change sets plus checkpoints.
//
// Rules:
// - May depend on dataloom-api, dataloom-model, and AndroidX Room.
// - Must not depend on dataloom-core, dataloom-runtime, or other Android modules.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
}

android {
    namespace = "io.dataloom.storage.room"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
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
        managedDevices {
            localDevices {
                create("pixel2Api35") {
                    device = "Pixel 2"
                    apiLevel = 35
                    systemImageSource = "aosp"
                    testedAbi = "x86_64"
                }
            }
        }
    }

    sourceSets.getByName("androidTest").assets.srcDir("$projectDir/schemas")
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.generateKotlin", "true")
}

dependencies {
    api(project(":dataloom-api"))
    api(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    testImplementation(kotlin("test-junit"))
    testImplementation(libs.mockito.kotlin)
    testImplementation(libs.room.testing)

    androidTestImplementation(kotlin("test-junit"))
    androidTestImplementation(libs.room.testing)
    androidTestImplementation(libs.androidx.test.core.ktx)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}
