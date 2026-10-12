/*
 * Copyright 2014-2025 Real Logic Limited.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.agrona.build;

import org.gradle.api.Action;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.file.Directory;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.Exec;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.internal.jvm.Jvm;
import org.gradle.internal.os.OperatingSystem;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.List;
import java.util.stream.Collectors;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * Tasks compiling the JNI shared library with Zig, bootstrapping Zig itself when necessary.
 */
final class ZigBuild
{
    private ZigBuild()
    {
    }

    static void apply(final Project project)
    {
        project.getPluginManager().apply("lifecycle-base");

        final File cSourcesDir = project.file("src/main/c");
        final File headersDir = project.file("src/main/headers");
        final File buildScriptFile = project.file("src/build.zig");
        final File javaHome = Jvm.current().getJavaHome();

        final String zigVersion = BuildSettings.catalogVersion(project, "zig");
        final boolean isWindows = OperatingSystem.current().isWindows();
        final File zigInstallDir = project.file("scripts/zig");
        final File bootstrappedZig = new File(zigInstallDir, "zig");

        final Provider<String> zigOverride = project.getProviders().gradleProperty("zig.executable");
        final Provider<Boolean> systemZigMatches = project.getProviders().provider(() -> systemZigMatches(zigVersion));
        final Provider<String> zigExecutable = zigOverride.orElse(project.getProviders().provider(() ->
            systemZigMatches.get() ? "zig" : bootstrappedZig.getAbsolutePath()));

        final TaskProvider<Exec> bootstrapZig = project.getTasks().register("bootstrapZig", Exec.class, (task) ->
        {
            task.setDescription(
                "Installs Zig " + zigVersion + " into scripts/zig if a matching Zig is not available.");
            task.setGroup("build");

            final File installScript = project.file("scripts/install-zig.sh");

            task.onlyIf("bootstrap not supported on Windows", (t) -> !isWindows);
            task.onlyIf(
                "requires no zig override and no zig " + zigVersion + " on PATH",
                (t) -> !zigOverride.isPresent() && !systemZigMatches.get());

            task.getInputs().file(installScript);
            task.getInputs().property("zigVersion", zigVersion);

            task.commandLine("bash", installScript.getAbsolutePath(), zigVersion, zigInstallDir.getAbsolutePath());
        });

        final TaskProvider<Exec> compileNative = project.getTasks().register("compileNative", Exec.class, (task) ->
        {
            task.setDescription("Compiles the JNI shared library for every supported Linux architecture.");
            task.setGroup("build");
            task.dependsOn(bootstrapZig);

            task.onlyIf(
                "requires zig " + zigVersion + " on Windows",
                (t) -> !isWindows || zigOverride.isPresent() || systemZigMatches.get());

            final Provider<Directory> buildDir = project.getLayout().getBuildDirectory().dir("zig");
            final Provider<Directory> outputDir = project.getLayout().getBuildDirectory().dir("lib");

            task.getInputs().dir(cSourcesDir);
            task.getInputs().file(buildScriptFile);
            task.getInputs().dir(headersDir);
            task.getOutputs().dir(outputDir);

            task.setWorkingDir(project.getProjectDir());
            // an anonymous class rather than a lambda keeps the task eligible for up-to-date checks
            task.doFirst(new Action<Task>()
            {
                public void execute(final Task t)
                {
                    ((Exec)t).setExecutable(zigExecutable.get());
                }
            });
            task.setArgs(List.of(
                "build",
                "--build-file", buildScriptFile.getAbsolutePath(),
                "--cache-dir", buildDir.get().dir("cache").getAsFile().getAbsolutePath(),
                "--prefix", buildDir.get().getAsFile().getAbsolutePath(),
                "--prefix-lib-dir", outputDir.get().getAsFile().getAbsolutePath(),
                "-Djava_home=" + javaHome));
        });

        project.getTasks().named("assemble", (task) -> task.dependsOn(compileNative));
    }

    private static boolean systemZigMatches(final String zigVersion)
    {
        try
        {
            final Process process = new ProcessBuilder("zig", "version")
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
            final String stdout;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), UTF_8)))
            {
                stdout = reader.lines().collect(Collectors.joining("\n"));
            }
            return 0 == process.waitFor() && stdout.trim().equals(zigVersion);
        }
        catch (final IOException ignore)
        {
            return false;
        }
        catch (final InterruptedException ex)
        {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
