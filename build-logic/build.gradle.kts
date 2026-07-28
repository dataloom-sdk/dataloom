plugins {
    `java-gradle-plugin`
}

val isAndroidBuildEnabled: Boolean =
    System.getenv("DATALOOM_ANDROID_BUILD") == "true"

repositories {
    mavenCentral()
    gradlePluginPortal()
    if (isAndroidBuildEnabled) {
        google()
    }
}

dependencies {
    implementation(libs.kotlin.gradlePlugin)
    if (isAndroidBuildEnabled) {
        // AGP and KGP must share the build-logic classloader. AGP's built-in
        // Kotlin integration asks KGP to create KotlinAndroidTarget, whose
        // public type graph references AGP APIs such as BaseVariant.
        implementation(libs.android.gradlePlugin)
    }
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
