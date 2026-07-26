// DataLoom connectivity provider for Android.
//
// Provides AndroidConnectivityProvider — a single bounded query of the
// Android ConnectivityManager for the current device-level network state.
//
// Rules:
// - May depend on dataloom-api and Android framework connectivity APIs.
// - Must not depend on Room, SQLite, WorkManager, or other DataLoom Android modules.
plugins {
    id("io.dataloom.android.library")
}

android {
    namespace = "io.dataloom.connectivity.android"
}

dependencies {
    // DataLoom public API contracts
    implementation(project(":dataloom-api"))

    // Local JVM unit tests
    testImplementation(kotlin("test"))
    testImplementation(libs.mockito.kotlin)
}
