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

import com.github.benmanes.gradle.versions.updates.DependencyUpdatesTask;
import org.agrona.build.dsl.AgronaBuild;
import org.gradle.api.Project;
import org.gradle.api.tasks.Copy;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.api.tasks.bundling.Compression;
import org.gradle.api.tasks.bundling.Tar;
import org.gradle.api.tasks.testing.TestReport;
import org.gradle.api.xdcl.Reaction;
import org.gradle.api.xdcl.ReactionScope;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Configures the root project: aggregated reports, test logs collection and the upload to Central Portal.
 */
public class AgronaBuildReaction implements Reaction<AgronaBuild, Project>
{
    private static final List<String> STABLE_KEYWORDS = List.of("RELEASE", "FINAL", "GA");
    private static final Pattern STABLE_VERSION = Pattern.compile("^[0-9,.v-]+(-r|-jre)?$");

    /**
     * {@inheritDoc}
     */
    public void on(final AgronaBuild data, final Project project, final ReactionScope scope)
    {
        project.getPluginManager().apply("java-library");
        project.getPluginManager().apply("io.github.ben-manes.versions");
        JavaConventions.applyCommon(project);

        if (data.defaultTasks().isPresent())
        {
            project.setDefaultTasks(data.defaultTasks().get());
        }

        project.getTasks().named("jar", (task) -> task.setEnabled(false));

        project.getTasks().register("testReport", TestReport.class, (task) ->
        {
            task.getDestinationDirectory().set(project.getLayout().getBuildDirectory().dir("reports/allTests"));
            // Include the results from the `test` task in all subprojects
            for (final Project subproject : project.getSubprojects())
            {
                task.getTestResults().from(subproject.getTasks().named("test"));
            }
        });

        // Reject all non stable versions
        project.getTasks().named("dependencyUpdates", DependencyUpdatesTask.class, (task) ->
            task.rejectVersionIf((selection) -> isNonStable(selection.getCandidate().getVersion())));

        final TaskProvider<Copy> copyTestLogs = project.getTasks().register("copyTestLogs", Copy.class, (task) ->
        {
            task.from(".");
            task.include("**/*.hprof");
            task.include("**/*.mdmp");
            task.include("/var/coredump/core*");
            task.include("**/*.log");
            task.include("**/build/reports/**");
            task.include("**/build/test-results/**");
            task.include("LICENSE");
            task.exclude("build");
            task.into("build/test_logs");

            task.setIncludeEmptyDirs(false);
        });

        project.getTasks().register("tarTestLogs", Tar.class, (task) ->
        {
            task.dependsOn(copyTestLogs);
            task.getArchiveBaseName().set("test_logs");
            task.from("build/test_logs");
            task.setCompression(Compression.BZIP2);
        });

        project.getTasks().register(
            "uploadArtifactsToCentralPortal",
            SonatypeCentralPortalUploadRepositoryTask.class,
            (task) ->
            {
                task.getPortalUsername().set(BuildSettings.configProperty(
                    project, "sonatypeCentralPortalUsername", "SONATYPE_CENTRAL_PORTAL_USERNAME"));
                task.getPortalPassword().set(BuildSettings.configProperty(
                    project, "sonatypeCentralPortalPassword", "SONATYPE_CENTRAL_PORTAL_PASSWORD"));
                task.getGroupId().set(BuildSettings.GROUP);
                task.getSnapshotRelease().set(!BuildSettings.isReleaseVersion(project));
            });
    }

    private static boolean isNonStable(final String version)
    {
        final String upperCaseVersion = version.toUpperCase(Locale.ROOT);
        final boolean hasStableKeyword = STABLE_KEYWORDS.stream().anyMatch(upperCaseVersion::contains);
        return !hasStableKeyword && !STABLE_VERSION.matcher(version).matches();
    }
}
