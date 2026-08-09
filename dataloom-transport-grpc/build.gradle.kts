// DataLoom reference gRPC TransportProvider (JVM/Android only).
//
// Provides GrpcTransportProvider — an abstract TransportProvider base class
// backed by grpc-kotlin unary calls. Applications supply their own generated
// gRPC stubs by subclassing GrpcTransportProvider.
//
// Platform scope: JVM and native Android only. Google's grpc-kotlin is built
// on grpc-java and has no Kotlin/Native (iOS) target. Do NOT add iosMain
// source sets or Apple targets to this module. iOS support is a separate
// follow-up item pending a viable Kotlin/Native gRPC client.
//
// Rules:
// - May depend on dataloom-model, dataloom-api, and grpc-kotlin/grpc-java libraries.
// - Must NOT become a mandatory dependency of dataloom-core, dataloom-runtime,
//   or any other shared DataLoom module. It is strictly opt-in.
// - Must NOT expose io.grpc types in public API surfaces.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
    explicitApi()
}

dependencies {
    // DataLoom public contracts — consumed as the JVM variant of KMP modules.
    api(project(":dataloom-model"))
    api(project(":dataloom-api"))

    // gRPC Kotlin coroutine stubs (grpc-java is a transitive dependency).
    implementation(libs.grpc.kotlin.stub)
    // grpc-core brings Status, StatusException, and ManagedChannel.
    implementation(libs.grpc.core)
    // grpc-stub brings ClientCalls and stub base classes.
    implementation(libs.grpc.stub)
    // Coroutines core — required by grpc-kotlin-stub coroutine extensions.
    implementation(libs.kotlinx.coroutines.core)

    // grpc-okhttp provides the production ManagedChannelBuilder transport.
    // Declared as runtimeOnly — applications choose their transport dependency.
    runtimeOnly(libs.grpc.okhttp)

    // Test dependencies.
    testImplementation(kotlin("test"))
    // In-process gRPC channel for unit testing without a real network.
    testImplementation(libs.grpc.inprocess)
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
