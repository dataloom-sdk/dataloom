// DataLoom asset synchronization module (ADR-0002: `dataloom-assets`).
//
// Chunked, resumable, integrity-verified asset transfer contracts and their
// in-memory reference behaviour: chunk planning, bounded-memory streaming
// source/sink contracts, the transfer-session state machine, the
// AssetProvider SPI, a sequential transfer engine, an in-memory reference
// provider, a provider contract kit, and compression/encryption SPIs with
// zlib/DEFLATE and AES-256-GCM implementations (ADR-0014). See
// docs/adr/ADR-0006-asset-transfer-and-streaming-digest.md.
//
// Rules:
// - Kotlin multiplatform (jvm + iOS). Platform source sets exist only for the two
//   transform primitives (java.util.zip / javax.crypto on the JVM, platform.zlib on
//   Apple; Apple has no AES-GCM, see ADR-0014).
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
