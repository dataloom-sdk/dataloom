// DataLoom API module.
//
// This module will house stable public contracts, canonical models,
// public error types, public configuration contracts, and provider
// and plugin contracts in future issues.
//
// Rules:
// - Must remain platform-independent.
// - Must not depend on any other DataLoom implementation module.
// - Must not contain runtime implementations.
plugins {
    id("io.dataloom.kotlin.multiplatform-library")
}

kotlin {
    explicitApi()

    sourceSets {
        commonMain {
            dependencies {
                api(project(":dataloom-model"))
                api(project(":dataloom-provider-api"))
                api(project(":dataloom-config"))
            }
        }
        commonTest {
            dependencies {
                implementation(libs.kotlinx.coroutines.test)
            }
        }
    }
}
