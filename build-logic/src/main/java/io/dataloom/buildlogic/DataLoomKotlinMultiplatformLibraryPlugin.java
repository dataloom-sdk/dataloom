package io.dataloom.buildlogic;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.gradle.api.GradleException;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.tasks.bundling.Jar;
import org.gradle.api.tasks.testing.Test;
import org.gradle.api.tasks.TaskProvider;
import org.jetbrains.kotlin.gradle.dsl.JvmTarget;
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension;
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilation;
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType;
import org.jetbrains.kotlin.gradle.plugin.KotlinTarget;
import org.jetbrains.kotlin.gradle.targets.jvm.KotlinJvmTarget;

/**
 * Shared production convention for DataLoom Kotlin Multiplatform libraries.
 *
 * <p>The convention itself is compiled as Java so resolving the build-logic
 * build never requires a second Kotlin compiler plugin. The Kotlin Gradle
 * plugin remains an explicit implementation dependency because this class
 * configures its public extension API.
 *
 * <p>The explicit Android KMP target is deliberately not created here: a
 * module opts in from its own build script (env-gated on
 * {@code DATALOOM_ANDROID_BUILD}) by applying AGP's
 * {@code com.android.kotlin.multiplatform.library} plugin, which runs after
 * this convention. Anything below that depends on the Android target is
 * therefore resolved lazily, from the target's actual presence, and this
 * class needs no AGP dependency. See docs/android/kmp-android-target-blocker.md.
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
            Configuration jvmRuntimeClasspath =
                    project.getConfigurations().getByName("jvmRuntimeClasspath");
            TaskProvider<ResolvedDependencyBoundaryCheckTask> boundaryCheck =
                    project.getTasks().register(
                            "checkResolvedDependencyBoundaries",
                            ResolvedDependencyBoundaryCheckTask.class,
                            task -> {
                                task.setGroup("verification");
                                task.setDescription(
                                        "Rejects testing artifacts on the production runtime classpaths."
                                );
                                // Both the JVM and, when the module has an explicit Android
                                // target, the Android main runtime classpath are guarded.
                                // Resolved lazily: the Android target only exists once the
                                // module's own build script has applied AGP's plugin.
                                task.getResolvedComponents().put(
                                        jvmRuntimeClasspath.getName(),
                                        project.provider(
                                                () -> componentNames(jvmRuntimeClasspath)
                                        )
                                );
                                task.getResolvedComponents().putAll(
                                        project.provider(
                                                () -> androidRuntimeClasspathComponents(project, kotlin)
                                        )
                                );
                                task.getForbiddenMarkers().set(
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
                                // Kotlin's ABI validation dumps the JVM target to
                                // abi/<project>.api while jvm is the only JVM-like target,
                                // and to abi/jvm/<project>.api as soon as a second one (the
                                // explicit Android target) exists. The Android target itself
                                // produces no dump; it compiles the same commonMain source.
                                task.getAbiDumps().from(
                                        project.getLayout()
                                                .getBuildDirectory()
                                                .file(
                                                        project.provider(
                                                                () -> hasAndroidTarget(kotlin)
                                                                        ? "kotlin/abi/jvm/dataloom-runtime.api"
                                                                        : "kotlin/abi/dataloom-runtime.api"
                                                        )
                                                )
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

    /**
     * Mirrors the condition Kotlin's ABI validation uses to switch its dump
     * layout: any target whose platform type is {@code androidJvm}.
     */
    private static boolean hasAndroidTarget(KotlinMultiplatformExtension kotlin) {
        return kotlin.getTargets()
                .stream()
                .anyMatch(target -> target.getPlatformType() == KotlinPlatformType.androidJvm);
    }

    /**
     * Component names on the main runtime classpath of every Android KMP
     * target, keyed by classpath name; empty when the module has no Android
     * target.
     */
    private static Map<String, List<String>> androidRuntimeClasspathComponents(
            Project project,
            KotlinMultiplatformExtension kotlin
    ) {
        Map<String, List<String>> components = new TreeMap<>();
        for (KotlinTarget target : kotlin.getTargets()) {
            if (target.getPlatformType() != KotlinPlatformType.androidJvm) {
                continue;
            }
            KotlinCompilation<?> main =
                    target.getCompilations().findByName(KotlinCompilation.MAIN_COMPILATION_NAME);
            String classpathName =
                    main == null ? null : main.getRuntimeDependencyConfigurationName();
            if (classpathName == null) {
                // Fail loudly: skipping would leave the Android classpath unguarded.
                throw new GradleException(
                        "Android target '" + target.getTargetName()
                                + "' exposes no main runtime classpath to check."
                );
            }
            components.put(
                    classpathName,
                    componentNames(project.getConfigurations().getByName(classpathName))
            );
        }
        return components;
    }

    /**
     * Display names of every component in the resolved dependency graph of the
     * classpath. Only the graph is resolved, never the artifacts, so nothing
     * has to be built and no artifact type has to be chosen.
     */
    private static List<String> componentNames(Configuration classpath) {
        return classpath.getIncoming()
                .getResolutionResult()
                .getAllComponents()
                .stream()
                .map(component -> component.getId().getDisplayName())
                .sorted()
                .toList();
    }
}
