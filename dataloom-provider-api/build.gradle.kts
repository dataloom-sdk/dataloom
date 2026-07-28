// DataLoom provider SPI module.
//
// This module contains the minimal provider lifecycle and binding contracts
// needed by provider implementations without pulling in the complete SDK API.
plugins {
    id("io.dataloom.kotlin.multiplatform-library")
}

kotlin {
    explicitApi()

    sourceSets {
        commonMain {
            dependencies {
                api(project(":dataloom-model"))
            }
        }
    }
}
