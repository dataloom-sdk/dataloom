plugins {
    id("io.dataloom.kotlin.multiplatform-library")
    alias(libs.plugins.sqldelight)
}

kotlin {
    explicitApi()

    sourceSets {
        commonMain {
            dependencies {
                api(project(":dataloom-model"))
                api(project(":dataloom-api"))
                implementation(libs.sqldelight.runtime)
            }
        }

        named("jvmMain") {
            dependencies {
                implementation(libs.sqldelight.sqlite.driver)
            }
        }

        // findByName("iosMain") returns null here: it is a synchronous,
        // point-in-time lookup, and Kotlin does not register the "iosMain"
        // intermediate source set (the one src/iosMain/kotlin actually
        // compiles against, via compileIosMainKotlinMetadata) until later in
        // its own target-configuration lifecycle. named(...) returns a lazy
        // provider that defers configuration until the source set is
        // actually created, so it sees the source set once Kotlin registers
        // it instead of missing it entirely.
        matching { it.name == "iosMain" }.configureEach {
            dependencies {
                implementation(libs.sqldelight.native.driver)
            }
        }
    }
}

sqldelight {
    databases {
        create("DataLoomStorageDatabase") {
            packageName.set("io.dataloom.storage.sqldelight.internal")
            // The default sqlite-3-18-dialect predates SQLite's upsert syntax
            // (ON CONFLICT ... DO UPDATE SET), which DataLoomStorage.sq uses.
            // Upsert was added in SQLite 3.24.0.
            dialect(libs.sqldelight.dialect)
        }
    }
}
