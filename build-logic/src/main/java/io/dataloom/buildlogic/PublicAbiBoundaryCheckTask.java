package io.dataloom.buildlogic;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.provider.SetProperty;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;

/**
 * Rejects implementation-only packages from a generated public ABI dump.
 */
public abstract class PublicAbiBoundaryCheckTask extends DefaultTask {

    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getAbiDumps();

    @Input
    public abstract SetProperty<String> getForbiddenMarkers();

    @TaskAction
    public void verifyPublicAbi() throws IOException {
        List<File> abiDumps = getAbiDumps().getFiles().stream().sorted().toList();
        if (abiDumps.isEmpty()) {
            throw new GradleException("No generated public ABI dumps were found.");
        }

        List<String> violations = new ArrayList<>();
        for (File abiDump : abiDumps) {
            String dump =
                    Files.readString(
                            abiDump.toPath(),
                            StandardCharsets.UTF_8
                    );
            for (String marker : getForbiddenMarkers().get()) {
                if (dump.contains(marker)) {
                    violations.add(abiDump.getName() + ": " + marker);
                }
            }
        }

        if (!violations.isEmpty()) {
            throw new GradleException(
                    "Public ABI contains implementation-only package markers: "
                            + String.join(", ", violations)
            );
        }
    }
}
