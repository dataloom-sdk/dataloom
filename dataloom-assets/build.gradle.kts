// DataLoom asset synchronization module (ADR-0002: `dataloom-assets`).
//
// Chunked, resumable, integrity-verified asset transfer contracts and their
// in-memory reference behaviour: chunk planning, bounded-memory streaming
// source/sink contracts, the transfer-session state machine, the
// AssetProvider SPI, a sequential transfer engine, an in-memory reference
// provider, a provider contract kit, and compression/encryption SPIs (identity
// implementations only). See docs/adr/ADR-0006-asset-transfer-and-streaming-digest.md.
//
// Rules:
// - Pure Kotlin multiplatform (jvm + iOS), no platform-specific source sets.
// - Depends on dataloom-api (asset manifest, provider result/error contracts)
//   and dataloom-model (digest primitives, including the incremental digest
//   capability) plus kotlinx.coroutines.core.
// - dataloom-runtime depends on this module only for the opt-in
//   DataLoomBuilder.assetTransferConfiguration capability; absent that spec
//   the module is inert.
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
