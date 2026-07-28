plugins {
    `java-gradle-plugin`
}

repositories {
    mavenCentral()
    gradlePluginPortal()
}

dependencies {
    implementation(libs.kotlin.gradlePlugin)
    testImplementation(gradleTestKit())
}

gradlePlugin {
    plugins {
        create("dataLoomKotlinMultiplatformLibrary") {
            id = "io.dataloom.kotlin.multiplatform-library"
            implementationClass = "io.dataloom.buildlogic.DataLoomKotlinMultiplatformLibraryPlugin"
        }
    }
}

tasks.test {
    useJUnitPlatform()
}
