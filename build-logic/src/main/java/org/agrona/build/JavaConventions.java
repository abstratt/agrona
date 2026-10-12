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

import org.agrona.build.dsl.DependencyNotation;
import org.agrona.build.dsl.JavaModule;
import org.agrona.build.dsl.ModuleDependencies;
import org.gradle.api.JavaVersion;
import org.gradle.api.Project;
import org.gradle.api.artifacts.MinimalExternalModuleDependency;
import org.gradle.api.artifacts.dsl.DependencyHandler;
import org.gradle.api.logging.LogLevel;
import org.gradle.api.plugins.JavaPluginExtension;
import org.gradle.api.plugins.quality.CheckstyleExtension;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.JavaExec;
import org.gradle.api.tasks.bundling.Jar;
import org.gradle.api.tasks.compile.JavaCompile;
import org.gradle.api.tasks.javadoc.Javadoc;
import org.gradle.api.tasks.testing.Test;
import org.gradle.api.tasks.testing.logging.TestLogging;
import org.gradle.external.javadoc.StandardJavadocDocletOptions;
import org.gradle.jvm.toolchain.JavaLanguageVersion;
import org.gradle.jvm.toolchain.JavaLauncher;
import org.gradle.jvm.toolchain.JavaToolchainService;
import org.gradle.jvm.toolchain.JavadocTool;
import org.gradle.plugins.signing.Sign;

import java.util.List;
import java.util.Map;

/**
 * Conventions shared by the projects of the build.
 */
final class JavaConventions
{
    private static final List<String> FORCED_LIBRARIES = List.of(
        "jspecify",
        // patching conflicting Checkstyle dependencies
        "commons-codec",
        "commons-lang3",
        "httpcore",
        "plexus-utils");

    private JavaConventions()
    {
    }

    /**
     * Conventions of every project, the root project included. Requires the Java plugin to be applied.
     */
    static void applyCommon(final Project project)
    {
        final Object[] forced = FORCED_LIBRARIES.stream()
            .map((alias) -> moduleNotation(BuildSettings.catalogLibrary(project, alias)))
            .toArray();

        project.getConfigurations().configureEach((configuration) ->
            configuration.resolutionStrategy((strategy) ->
            {
                strategy.failOnVersionConflict();
                strategy.force(forced);
            }));

        final Provider<JavaLauncher> launcher = toolchainLauncher(project);
        project.getTasks().withType(JavaExec.class).configureEach((task) -> task.getJavaLauncher().set(launcher));
    }

    /**
     * Conventions of a Java module.
     */
    static void apply(final Project project, final JavaModule module)
    {
        project.getPluginManager().apply("java-library");
        project.getPluginManager().apply("checkstyle");
        applyCommon(project);

        final String agronaVersion = BuildSettings.agronaVersion(project);
        final int buildJavaVersion = BuildSettings.buildJavaVersion();
        final String release = String.valueOf(module.javaRelease().get());

        project.setGroup(BuildSettings.GROUP);
        project.setVersion(agronaVersion);

        project.getExtensions().getByType(CheckstyleExtension.class)
            .setToolVersion(BuildSettings.catalogVersion(project, "checkstyle"));

        final JavaPluginExtension java = project.getExtensions().getByType(JavaPluginExtension.class);
        java.getToolchain().getLanguageVersion().set(JavaLanguageVersion.of(buildJavaVersion));
        java.setSourceCompatibility(JavaVersion.toVersion(release));

        final boolean isReleaseVersion = BuildSettings.isReleaseVersion(project);
        project.getTasks().withType(Sign.class).configureEach((task) ->
            task.onlyIf((t) -> isReleaseVersion &&
            project.getGradle().getTaskGraph().hasTask(project.getTasks().getByName("publish"))));

        project.getTasks().withType(Jar.class).configureEach((task) ->
        {
            task.setEnabled(true);
            task.setIncludeEmptyDirs(false);
        });

        project.getTasks().withType(JavaCompile.class).configureEach((task) ->
        {
            task.getOptions().getCompilerArgs().add("--release");
            task.getOptions().getCompilerArgs().add(release);
            // Enable all warnings and treat them as errors
            task.getOptions().getCompilerArgs().addAll(List.of("-Xlint:all", "-Werror"));
            task.getOptions().setEncoding("UTF-8");
            task.getOptions().setDeprecation(true);
        });

        final Provider<JavaLauncher> launcher = toolchainLauncher(project);
        project.getTasks().named("test", Test.class, (test) ->
        {
            test.jvmArgs("--add-opens", "java.base/jdk.internal.misc=ALL-UNNAMED");
            test.jvmArgs("--add-opens", "java.base/java.util.zip=ALL-UNNAMED");

            if (buildJavaVersion >= 21)
            {
                test.jvmArgs("-XX:+EnableDynamicAgentLoading");
            }

            final String baseLogFileName = project.getLayout().getBuildDirectory().get() + "/" +
                project.getLayout().getProjectDirectory().getAsFile().getName() + "-" + test.getName() + "-%p";
            test.jvmArgs(
                "-XX:MaxDirectMemorySize=2g",
                "-Xms2048m",
                "-Xmx2048m",
                "-Xmn1536m",
                "-XX:+CreateCoredumpOnCrash",
                "-XX:+HeapDumpOnOutOfMemoryError",
                "-XX:+CrashOnOutOfMemoryError",
                "-XX:HeapDumpPath=" + baseLogFileName + "-heap.hprof",
                "-XX:+UseParallelGC",
                "-Xlog:gc*,safepoint=info,arguments=info:file=" + baseLogFileName + "-gc.log:time",
                "-XX:ErrorFile=" + baseLogFileName + "-crash.log");

            test.useJUnitPlatform();

            for (final LogLevel level : LogLevel.values())
            {
                final TestLogging testLogging = test.getTestLogging().get(level);
                testLogging.setExceptionFormat("full");
                testLogging.setEvents(List.of("FAILED", "STANDARD_OUT", "STANDARD_ERROR"));
            }

            test.systemProperties(Map.of(
                "agrona.disable.bounds.checks", "false",
                "agrona.strict.alignment.checks", "true",
                "net.bytebuddy.experimental", "true",
                "net.bytebuddy.safe", "true"));

            test.getJavaLauncher().set(launcher);
        });

        final Provider<JavadocTool> javadocTool = project.getExtensions().getByType(JavaToolchainService.class)
            .javadocToolFor((spec) -> spec.getLanguageVersion().set(JavaLanguageVersion.of(buildJavaVersion)));
        project.getTasks().named("javadoc", Javadoc.class, (javadoc) ->
        {
            final StandardJavadocDocletOptions options = (StandardJavadocDocletOptions)javadoc.getOptions();
            javadoc.setTitle("<h1>Agrona</h1>");
            options.setBottom("<i>Copyright &#169; 2014-2025 Real Logic Limited. All Rights Reserved.</i>");
            options.setEncoding("UTF-8");
            options.setDocEncoding("UTF-8");
            options.setCharSet("UTF-8");
            if (BuildSettings.isEarlyAccessJavaVersion())
            {
                options.links("https://download.java.net/java/early_access/jdk" + buildJavaVersion + "/docs/api/");
            }
            else
            {
                options.links("https://docs.oracle.com/en/java/javase/" + buildJavaVersion + "/docs/api/");
            }

            options.addStringOption("-release", release);
            options.addBooleanOption("Werror", true);
            options.addBooleanOption("Xdoclint:all", true);

            javadoc.getJavadocTool().set(javadocTool);
        });

        if (module.dependencies().isPresent())
        {
            addDependencies(project, module.dependencies().get());
        }
    }

    static Provider<JavaLauncher> toolchainLauncher(final Project project)
    {
        return project.getExtensions().getByType(JavaToolchainService.class).launcherFor((spec) ->
            spec.getLanguageVersion().set(JavaLanguageVersion.of(BuildSettings.buildJavaVersion())));
    }

    private static void addDependencies(final Project project, final ModuleDependencies dependencies)
    {
        final DependencyHandler handler = project.getDependencies();
        for (final DependencyNotation platform : dependencies.testPlatform().getOrElse(List.of()))
        {
            handler.add("testImplementation", handler.platform(BuildSettings.dependency(project, platform)));
        }

        add(project, "api", dependencies.api());
        add(project, "implementation", dependencies.implementation());
        add(project, "compileOnly", dependencies.compileOnly());
        add(project, "runtimeOnly", dependencies.runtimeOnly());
        add(project, "annotationProcessor", dependencies.annotationProcessor());
        add(project, "testImplementation", dependencies.testImplementation());
        add(project, "testRuntimeOnly", dependencies.testRuntimeOnly());
    }

    private static void add(
        final Project project, final String configuration, final Provider<List<DependencyNotation>> notations)
    {
        for (final DependencyNotation notation : notations.getOrElse(List.of()))
        {
            project.getDependencies().add(configuration, BuildSettings.dependency(project, notation));
        }
    }

    private static String moduleNotation(final MinimalExternalModuleDependency dependency)
    {
        return dependency.getModule() + ":" + dependency.getVersionConstraint().getRequiredVersion();
    }
}
