package io.dataloom.buildlogic;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.provider.MapProperty;
import org.gradle.api.provider.SetProperty;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.TaskAction;

/**
 * Verifies that the resolved dependency graph of every production runtime
 * classpath contains no forbidden testing or implementation component.
 *
 * <p>The check is made on component identities (for example
 * {@code project :dataloom-testing}) rather than on artifact file names:
 * the Android runtime classpath cannot be resolved to plain files without
 * naming an artifact type, and its artifacts are generically named
 * ({@code classes.jar}) so a file-name marker could not see them anyway.
 */
public abstract class ResolvedDependencyBoundaryCheckTask extends DefaultTask {

    /**
     * Display names of the components resolved on each production runtime
     * classpath, keyed by classpath name (for example
     * {@code jvmRuntimeClasspath} or {@code androidRuntimeClasspath}).
     */
    @Input
    public abstract MapProperty<String, List<String>> getResolvedComponents();

    @Input
    public abstract SetProperty<String> getForbiddenMarkers();

    @TaskAction
    public final void verifyResolvedDependencies() {
        Map<String, List<String>> classpaths = getResolvedComponents().get();
        if (classpaths.isEmpty()) {
            throw new GradleException("No production runtime classpaths were provided to check.");
        }

        Set<String> markers = getForbiddenMarkers().get()
                .stream()
                .map(marker -> marker.toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());

        List<String> violations = new ArrayList<>();
        for (Map.Entry<String, List<String>> classpath : classpaths.entrySet()) {
            if (classpath.getValue().isEmpty()) {
                // An empty graph would make this check vacuously green.
                throw new GradleException(
                        "No resolved components were found on " + classpath.getKey() + "."
                );
            }
            for (String component : classpath.getValue()) {
                String normalized = component.toLowerCase(Locale.ROOT);
                if (markers.stream().anyMatch(normalized::contains)) {
                    violations.add(component + " (" + classpath.getKey() + ")");
                }
            }
        }

        if (!violations.isEmpty()) {
            throw new GradleException(
                    "Forbidden production runtime dependencies resolved: "
                            + violations.stream().sorted().collect(Collectors.joining(", "))
            );
        }
    }
}
