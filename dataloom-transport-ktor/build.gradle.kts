plugins {
    id("io.dataloom.kotlin.multiplatform-library")
}

private val ktorVersion: String = "2.3.12"

kotlin {
    explicitApi()

    sourceSets {
        commonMain {
            dependencies {
                api(project(":dataloom-api"))
                implementation("io.ktor:ktor-client-core:$ktorVersion")
            }
        }
        commonTest {
            dependencies {
                implementation(libs.kotlinx.coroutines.test)
                implementation("io.ktor:ktor-client-mock:$ktorVersion")
            }
        }
        jvmMain {
            dependencies {
                implementation("io.ktor:ktor-client-cio:$ktorVersion")
            }
        }
        findByName("iosMain")?.let { iosMain ->
            iosMain.dependencies {
                implementation("io.ktor:ktor-client-darwin:$ktorVersion")
            }
        }
    }
}
