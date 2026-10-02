// DataLoom reference transport-backed AssetProvider (gate #97, DL-043).
//
// Provides KtorAssetProvider -- a real HTTP-calling AssetProvider
// implementation backed by the Ktor client, as a sibling to
// dataloom-transport-ktor's own KtorTransportProvider but for asset chunk
// upload/download instead of change-event push/pull.
//
// Platform scope: JVM and native Android only, deliberately mirroring
// dataloom-transport-retrofit's and dataloom-transport-grpc's plain-JVM
// module shape rather than dataloom-transport-ktor's KMP one. Ktor's client
// is itself multiplatform, but this module is new and unproven: scoping it
// to JVM/Android avoids claiming Apple cross-compilation or ABI-baseline
// coverage this slice never exercised. A KMP (Apple-inclusive) version is a
// reasonable follow-up once there is a real caller.
//
// Rules:
// - Depends on dataloom-assets (the AssetProvider contract this implements)
//   and Ktor client artifacts only.
// - Must NOT become a mandatory dependency of dataloom-assets, dataloom-core,
//   or dataloom-runtime. It is strictly opt-in, like dataloom-transport-ktor.
// - Does not invent asset bytes handling beyond the documented wire protocol
//   in KtorAssetProvider's own KDoc; business-rule verification (digests,
//   quota, session conflicts) remains the remote server's job, not this
//   client's.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

private val ktorVersion: String = "2.3.12"

kotlin {
    explicitApi()
    jvmToolchain(17)
}

dependencies {
    api(project(":dataloom-assets"))
    implementation(libs.kotlinx.coroutines.core)
    implementation("io.ktor:ktor-client-core:$ktorVersion")
    implementation("io.ktor:ktor-client-cio:$ktorVersion")

    testImplementation(kotlin("test-junit"))
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation("io.ktor:ktor-client-mock:$ktorVersion")
}
