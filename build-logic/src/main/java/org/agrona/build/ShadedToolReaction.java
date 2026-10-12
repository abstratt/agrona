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

import org.agrona.build.dsl.Jcstress;
import org.agrona.build.dsl.ShadedTool;
import org.gradle.api.Project;
import org.gradle.api.tasks.JavaExec;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.api.tasks.bundling.Jar;
import org.gradle.api.xdcl.Reaction;
import org.gradle.api.xdcl.ReactionScope;

import java.io.OutputStream;
import java.util.List;
import java.util.Map;

/**
 * Configures a project packaging an executable shaded jar, e.g. the benchmarks.
 */
public class ShadedToolReaction implements Reaction<ShadedTool, Project>
{
    /**
     * {@inheritDoc}
     */
    public void on(final ShadedTool data, final Project project, final ReactionScope scope)
    {
        JavaConventions.apply(project, data);
        project.getPluginManager().apply("com.gradleup.shadow");

        project.getTasks().named("compileJava", (task) -> task.dependsOn(":agrona:byteBuddy"));

        final TaskProvider<Jar> shadowJar = project.getTasks().named("shadowJar", Jar.class, (task) ->
        {
            task.getArchiveFileName().set(data.archiveFileName());
            task.getArchiveClassifier().set(data.classifier());
            task.getManifest().attributes(Map.of("Main-Class", data.mainClass().get()));
        });

        project.getTasks().named("jar", (task) -> task.finalizedBy(shadowJar));

        if (data.jcstress().isPresent())
        {
            final Jcstress jcstress = data.jcstress().get();
            project.getTasks().register("concurrencyTests", JavaExec.class, (task) ->
            {
                task.dependsOn(shadowJar);
                task.setClasspath(project.files(shadowJar));
                task.setWorkingDir(project.getLayout().getBuildDirectory().get());
                task.setArgs(List.of("-jvmArgs", jcstress.jvmArgs().get(), "-r", jcstress.reportDir().get()));
                task.setStandardOutput(OutputStream.nullOutputStream());
                task.setErrorOutput(OutputStream.nullOutputStream());
            });
        }
    }
}
