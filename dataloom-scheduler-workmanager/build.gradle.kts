// DataLoom WorkManager scheduler provider.
//
// Provides WorkManagerSchedulerProvider — an AndroidX WorkManager-backed
// SchedulerProvider — and DataLoomCoroutineWorker with DataLoomWorkerFactory
// for explicit Worker injection.
//
// Rules:
// - May depend on dataloom-api, dataloom-runtime, and AndroidX WorkManager.
// - Must not depend on Room, dataloom-queue-room, or dataloom-connectivity-android.
plugins {
    id("io.dataloom.android.library")
}

android {
    namespace = "io.dataloom.scheduler.workmanager"
}

dependencies {
    // DataLoom public API contracts and runtime queue-worker contracts
    implementation(project(":dataloom-api"))
    implementation(project(":dataloom-runtime"))

    // AndroidX WorkManager with coroutines support
    implementation(libs.workmanager.ktx)

    // Kotlin coroutines for Android
    implementation(libs.kotlinx.coroutines.android)

    // Local JVM unit tests
    testImplementation(kotlin("test"))
    testImplementation(libs.mockito.kotlin)
}
