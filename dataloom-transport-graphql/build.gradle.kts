// DataLoom GraphQL transport provider module.
//
// An optional, independently consumable reference TransportProvider backed
// by Apollo Kotlin. Supports JVM/Android and, on macOS hosts, iOS targets.
//
// This module depends only on dataloom-api and the Apollo Kotlin runtime.
// It must not become a mandatory dependency of any other DataLoom module.
//
// Apollo Kotlin is the only third-party runtime dependency added here;
// dataloom-model, dataloom-api, dataloom-core, and dataloom-runtime remain
// dependency-free as required by the architecture rules.
//
// See README.md for integration guidance and quickstart instructions.
plugins {
    id("io.dataloom.kotlin.multiplatform-library")
}

kotlin {
    explicitApi()

    sourceSets {
        commonMain {
            dependencies {
                api(project(":dataloom-api"))
                implementation(libs.apollo.runtime)
            }
        }
        commonTest {
            dependencies {
                implementation(libs.kotlinx.coroutines.test)
                implementation(libs.apollo.testingSupport)
            }
        }
    }
}
