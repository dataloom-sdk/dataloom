// DataLoom File Storage Provider module.
//
// Reference StorageProvider backed by plain files — no database, no ORM,
// no third-party dependency beyond standard Kotlin/JVM/Kotlin-Native I/O.
//
// Intended use: low-volume, demonstration, and getting-started scenarios.
// For high-throughput or large-dataset production use, prefer the Room or
// SQLDelight providers.
//
// Rules:
// - Depends only on dataloom-api (and its transitive dataloom-model,
//   dataloom-provider-api) plus kotlinx.coroutines.core.
// - Must not become a mandatory dependency of any existing module.
// - Must not modify dataloom-model, dataloom-api, dataloom-core, or
//   dataloom-runtime.
plugins {
    id("io.dataloom.kotlin.multiplatform-library")
}

kotlin {
    explicitApi()

    sourceSets {
        commonMain {
            dependencies {
                api(project(":dataloom-api"))
                implementation(libs.kotlinx.coroutines.core)
            }
        }
        commonTest {
            dependencies {
                implementation(libs.kotlinx.coroutines.test)
            }
        }
    }
}
