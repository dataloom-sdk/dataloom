plugins {
    id("io.dataloom.kotlin.multiplatform-library")
    alias(libs.plugins.sqldelight)
}

val isAndroidBuildEnabled: Boolean =
    System.getenv("DATALOOM_ANDROID_BUILD") == "true"

if (isAndroidBuildEnabled) {
    apply(plugin = "com.android.library")
}

kotlin {
    explicitApi()

    if (isAndroidBuildEnabled) {
        androidTarget()
    }

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

        findByName("androidMain")?.dependencies {
            implementation(libs.sqldelight.android.driver)
        }

        findByName("iosMain")?.dependencies {
            implementation(libs.sqldelight.native.driver)
        }
    }
}

if (isAndroidBuildEnabled) {
    android {
        namespace = "io.dataloom.storage.sqldelight"
        compileSdk = libs.versions.android.compileSdk.get().toInt()

        defaultConfig {
            minSdk = libs.versions.android.minSdk.get().toInt()
        }
    }
}

sqldelight {
    databases {
        create("DataLoomStorageDatabase") {
            packageName.set("io.dataloom.storage.sqldelight.internal")
        }
    }
}
