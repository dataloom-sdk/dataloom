// DataLoom SQLDelight Android storage driver.
//
// Provides createAndroidSqlDelightStorageDatabase — the Android AndroidSqliteDriver
// wiring for the SQLDelight-backed reference StorageProvider defined in
// dataloom-storage-sqldelight (JVM + iOS).
//
// This module exists separately from dataloom-storage-sqldelight because
// AGP 9.0+ does not allow the classic com.android.library plugin in the same
// module as org.jetbrains.kotlin.multiplatform. dataloom-storage-sqldelight
// stays a plain KMP module (JVM + iOS, no Android SDK required); this module
// supplies only the Android driver and requires the Android SDK/AGP, matching
// the same conditional-inclusion pattern as dataloom-connectivity-android,
// dataloom-scheduler-workmanager, dataloom-queue-room, and
// dataloom-storage-datastore.
//
// Rules:
// - May depend on dataloom-storage-sqldelight and the SQLDelight Android driver.
// - Must not depend on Room, WorkManager, or other DataLoom Android modules.
// - Must not become a transitive dependency of dataloom-core or dataloom-runtime.
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "io.dataloom.storage.sqldelight.android"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
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
}

dependencies {
    api(project(":dataloom-storage-sqldelight"))
    api(libs.sqldelight.android.driver)
}
