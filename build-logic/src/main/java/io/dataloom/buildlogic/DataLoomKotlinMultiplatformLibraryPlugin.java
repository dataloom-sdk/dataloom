package io.dataloom.buildlogic;

import java.util.Set;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.tasks.bundling.Jar;
import org.gradle.api.tasks.testing.Test;
import org.gradle.api.tasks.TaskProvider;
import org.jetbrains.kotlin.gradle.dsl.JvmTarget;
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension;
import org.jetbrains.kotlin.gradle.targets.jvm.KotlinJvmTarget;

/**
 * Shared production convention for DataLoom Kotlin Multiplatform libraries.
 *
 * <p>The convention itself is compiled as Java so resolving the build-logic
 * build never requires a second Kotlin compiler plugin. The Kotlin Gradle
 * plugin remains an explicit implementation dependency because this class
 * configures its public extension API.
 */
public final class DataLoomKotlinMultiplatformLibraryPlugin
        implements Plugin<Project> {

    @Override
    public void apply(Project project) {
        project.getPluginManager().apply("org.jetbrains.kotlin.multiplatform");

        KotlinMultiplatformExtension kotlin =
                project.getExtensions().getByType(KotlinMultiplatformExtension.class);

        kotlin.jvmToolchain(17);
        KotlinJvmTarget jvm = kotlin.jvm();
        jvm.getCompilerOptions().getJvmTarget().set(JvmTarget.JVM_17);

        boolean isAppleHost =
                System.getProperty("os.name", "").toLowerCase().contains("mac");
        boolean crossCompileAppleKlibs =
                project.getProviders()
                        .gradleProperty("dataloom.appleKlibCrossCompile")
                        .map(Boolean::parseBoolean)
                        .getOrElse(false);

        if (isAppleHost || crossCompileAppleKlibs) {
            kotlin.iosArm64();
            kotlin.iosSimulatorArm64();
            kotlin.iosX64();
        }

        if (!"runtime-external-consumer".equals(project.getName())) {
            kotlin.abiValidation();
        }

        project.getDependencies().add(
                "commonTestImplementation",
                "org.jetbrains.kotlin:kotlin-test"
        );

        project.getTasks().withType(Jar.class).configureEach(jar -> {
            jar.setPreserveFileTimestamps(false);
            jar.setReproducibleFileOrder(true);
        });

        project.getTasks().withType(Test.class).configureEach(Test::useJUnitPlatform);

        if ("dataloom-runtime".equals(project.getName())) {
            Configuration runtimeClasspath =
                    project.getConfigurations().getByName("jvmRuntimeClasspath");
            TaskProvider<ResolvedDependencyBoundaryCheckTask> boundaryCheck =
                    project.getTasks().register(
                            "checkResolvedDependencyBoundaries",
                            ResolvedDependencyBoundaryCheckTask.class,
                            task -> {
                                task.setGroup("verification");
                                task.setDescription(
                                        "Rejects testing artifacts on the production runtime classpath."
                                );
                                task.getRuntimeClasspath().from(runtimeClasspath);
                                task.getForbiddenFileMarkers().set(
                                        Set.of("dataloom-testing")
                                );
                            }
                    );
            project.getTasks().named("check").configure(
                    task -> task.dependsOn(boundaryCheck)
            );

            TaskProvider<PublicAbiBoundaryCheckTask> publicAbiCheck =
                    project.getTasks().register(
                            "checkPublicAbiBoundaries",
                            PublicAbiBoundaryCheckTask.class,
                            task -> {
                                task.setGroup("verification");
                                task.setDescription(
                                        "Rejects implementation-only packages from the public runtime ABI."
                                );
                                task.getAbiDumps().from(
                                        project.getLayout()
                                                .getBuildDirectory()
                                                .file("kotlin/abi/dataloom-runtime.api")
                                );
                                // Linux/JVM validation does not configure Native targets,
                                // so Kotlin does not generate a KLib dump there. Apple
                                // hosts and explicit cross-compilation still require it.
                                if (isAppleHost || crossCompileAppleKlibs) {
                                    task.getAbiDumps().from(
                                            project.getLayout()
                                                    .getBuildDirectory()
                                                    .file("kotlin/abi/dataloom-runtime.klib.api")
                                    );
                                }
                                task.getForbiddenMarkers().set(
                                        Set.of(
                                                "io/dataloom/core/",
                                                "io/dataloom/testing/",
                                                "io.dataloom.core.",
                                                "io.dataloom.testing."
                                        )
                                );
                                task.dependsOn(
                                        project.getTasks().named("internalDumpKotlinAbi")
                                );
                            }
                    );
            project.getTasks().named("check").configure(
                    task -> task.dependsOn(publicAbiCheck)
            );
        }
    }
}
