package io.dataloom.buildlogic;

import java.io.File;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.provider.SetProperty;
import org.gradle.api.tasks.Classpath;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.TaskAction;

/**
 * Verifies that resolved production dependencies do not contain forbidden
 * testing or implementation artifacts.
 */
public abstract class ResolvedDependencyBoundaryCheckTask extends DefaultTask {

    @Classpath
    public abstract ConfigurableFileCollection getRuntimeClasspath();

    @Input
    public abstract SetProperty<String> getForbiddenFileMarkers();

    @TaskAction
    public final void verifyResolvedDependencies() {
        Set<String> markers = getForbiddenFileMarkers().get()
                .stream()
                .map(marker -> marker.toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());

        List<String> violations = getRuntimeClasspath().getFiles()
                .stream()
                .map(File::getName)
                .filter(fileName -> {
                    String normalized = fileName.toLowerCase(Locale.ROOT);
                    return markers.stream().anyMatch(normalized::contains);
                })
                .sorted()
                .toList();

        if (!violations.isEmpty()) {
            throw new GradleException(
                    "Forbidden production runtime dependencies resolved: "
                            + String.join(", ", violations)
            );
        }
    }
}
