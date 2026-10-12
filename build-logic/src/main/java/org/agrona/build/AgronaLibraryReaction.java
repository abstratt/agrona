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

import net.bytebuddy.build.gradle.AbstractByteBuddyTaskExtension;
import net.bytebuddy.build.gradle.Adjustment;
import org.agrona.build.dsl.AgronaLibrary;
import org.agrona.build.dsl.Developer;
import org.agrona.build.dsl.License;
import org.agrona.build.dsl.Pom;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.file.Directory;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.plugins.JavaPluginExtension;
import org.gradle.api.provider.Provider;
import org.gradle.api.publish.PublishingExtension;
import org.gradle.api.publish.maven.MavenPom;
import org.gradle.api.publish.maven.MavenPublication;
import org.gradle.api.tasks.Copy;
import org.gradle.api.tasks.JavaExec;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.api.tasks.bundling.Jar;
import org.gradle.api.tasks.javadoc.Javadoc;
import org.gradle.api.tasks.testing.Test;
import org.gradle.api.xdcl.Reaction;
import org.gradle.api.xdcl.ReactionScope;
import org.gradle.internal.os.OperatingSystem;
import org.gradle.plugins.signing.SigningExtension;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Configures the Agrona library: code generation, the bundled native library, publishing and signing.
 */
public class AgronaLibraryReaction implements Reaction<AgronaLibrary, Project>
{
    private static final String RELEASES_REPO_URL =
        "https://ossrh-staging-api.central.sonatype.com/service/local/staging/deploy/maven2/";
    private static final String SNAPSHOTS_REPO_URL = "https://central.sonatype.com/repository/maven-snapshots/";

    /**
     * {@inheritDoc}
     */
    public void on(final AgronaLibrary data, final Project project, final ReactionScope scope)
    {
        JavaConventions.apply(project, data);

        project.getPluginManager().apply("maven-publish");
        project.getPluginManager().apply("signing");
        project.getPluginManager().apply("net.bytebuddy.byte-buddy-gradle-plugin");

        final String agronaVersion = BuildSettings.agronaVersion(project);
        project.evaluationDependsOn(data.nativeLibraryProject().get());
        final Project nativeLibraryProject = project.project(data.nativeLibraryProject().get());
        final TaskProvider<Task> compileNative = nativeLibraryProject.getTasks().named("compileNative");
        final DirectoryProperty buildDirectory = project.getLayout().getBuildDirectory();
        final JavaPluginExtension java = project.getExtensions().getByType(JavaPluginExtension.class);
        final SourceSet main = java.getSourceSets().getByName("main");

        project.getDependencies().add(
            "testImplementation", project.files(buildDirectory.get() + "/classes/java/generated"));

        if (data.testAgent().isPresent())
        {
            final Configuration testAgent =
                project.getConfigurations().create("testAgent", (configuration) -> configuration.setTransitive(false));
            testAgent.withDependencies((dependencies) -> dependencies.add(
                project.getDependencies().create(BuildSettings.dependency(project, data.testAgent().get()))));

            project.getTasks().named("test", Test.class, (test) ->
                test.jvmArgs("-javaagent:" + testAgent.getAsPath()));
        }

        project.getTasks().named("test", (test) -> test.dependsOn(compileNative));

        final File generatedDir = project.file(buildDirectory.get() + "/generated-src");
        final SourceSet generated = java.getSourceSets().create("generated", (sourceSet) ->
        {
            sourceSet.getJava().srcDir(generatedDir);
            sourceSet.setCompileClasspath(sourceSet.getCompileClasspath().plus(main.getRuntimeClasspath()));
        });

        project.getTasks().register("generatePrimitiveSpecialisations", JavaExec.class, (task) ->
        {
            task.getMainClass().set("org.agrona.generation.SpecialisationGenerator");
            task.setClasspath(main.getRuntimeClasspath());
            task.getOutputs().dir(generatedDir);
        });

        project.getTasks().register("generateUnsafeApi", UnsafeApiSourceGenerator.class, (task) ->
            task.setOutputDirectory(main.getAllJava().getSourceDirectories().getSingleFile()));

        project.getTasks().named("compileGeneratedJava", (task) -> task.dependsOn("generatePrimitiveSpecialisations"));
        project.getTasks().named("compileTestJava", (task) -> task.dependsOn("compileGeneratedJava"));
        project.getTasks().named("javadoc", (task) -> task.dependsOn("generatePrimitiveSpecialisations"));

        final AbstractByteBuddyTaskExtension<?> byteBuddy =
            (AbstractByteBuddyTaskExtension<?>)project.getExtensions().getByName("byteBuddy");
        byteBuddy.setAdjustment(Adjustment.SELF);
        byteBuddy.transformation((transformation) -> transformation.setPlugin(UnsafeApiBytecodeGenerator.class));

        final Provider<Directory> generatedResourceDir = buildDirectory.dir("generated-resources");
        final TaskProvider<Copy> bundleNativeLibrary =
            project.getTasks().register("bundleNativeLibrary", Copy.class, (task) ->
            {
                task.setDescription("Bundle the native library");
                task.onlyIf("runs only on Linux", (t) -> OperatingSystem.current().isLinux());
                task.dependsOn(compileNative);
                task.from(nativeLibraryProject.getLayout().getBuildDirectory().dir("lib"));
                task.into(generatedResourceDir.get().dir("native"));
            });

        project.getTasks().named("processResources", (task) -> task.dependsOn(bundleNativeLibrary));

        main.getResources().srcDir(generatedResourceDir);

        final Map<String, String> manifest = new LinkedHashMap<>(data.manifest().getOrElse(Map.of()));
        manifest.put("Implementation-Version", agronaVersion);
        project.getTasks().named("jar", Jar.class, (jar) ->
        {
            jar.from(generated.getOutput());
            jar.getManifest().attributes(manifest);
        });

        project.getTasks().register("sourcesJar", Jar.class, (task) ->
        {
            task.dependsOn("generatePrimitiveSpecialisations");
            task.getArchiveClassifier().set("sources");
            task.from(main.getAllSource());
            task.from(generated.getAllSource());
        });

        project.getTasks().named("javadoc", Javadoc.class, (javadoc) ->
        {
            javadoc.source(generated.getAllJava());
            javadoc.exclude("org/agrona/UnsafeApi.java");
        });

        java.withSourcesJar();
        java.withJavadocJar();

        final boolean isReleaseVersion = BuildSettings.isReleaseVersion(project);
        final String username = BuildSettings.configProperty(
            project, "sonatypeCentralPortalUsername", "SONATYPE_CENTRAL_PORTAL_USERNAME");
        final String password = BuildSettings.configProperty(
            project, "sonatypeCentralPortalPassword", "SONATYPE_CENTRAL_PORTAL_PASSWORD");

        final PublishingExtension publishing = project.getExtensions().getByType(PublishingExtension.class);
        final MavenPublication publication =
            publishing.getPublications().create("agrona", MavenPublication.class, (mavenPublication) ->
            {
                mavenPublication.from(project.getComponents().getByName("java"));
                mavenPublication.pom((pom) -> configurePom(pom, data.pom()));
            });

        publishing.getRepositories().maven((repository) ->
        {
            repository.setUrl(!isReleaseVersion ? SNAPSHOTS_REPO_URL : RELEASES_REPO_URL);
            repository.credentials((credentials) ->
            {
                credentials.setUsername(username);
                credentials.setPassword(password);
            });
        });

        // NOTE: the key is an ASCII armored secret key, the password is plain text
        final String signingKey = BuildSettings.configProperty(project, "signingKey", "SIGNING_GPG_SECRET_KEY");
        final String signingPassword = BuildSettings.configProperty(project, "signingPassword", "SIGNING_GPG_PASSWORD");

        final SigningExtension signing = project.getExtensions().getByType(SigningExtension.class);
        if (null != signingKey)
        {
            signing.useInMemoryPgpKeys(signingKey, signingPassword);
        }
        signing.sign(publication);
    }

    private static void configurePom(final MavenPom mavenPom, final Pom pom)
    {
        mavenPom.getName().set(pom.name());
        mavenPom.getDescription().set(pom.description());
        mavenPom.getUrl().set(pom.url());

        mavenPom.scm((scm) ->
        {
            scm.getConnection().set(pom.scm().connection());
            scm.getDeveloperConnection().set(pom.scm().developerConnection());
            scm.getUrl().set(pom.scm().url());
        });

        mavenPom.licenses((licenses) ->
        {
            for (final License license : pom.licenses().get())
            {
                licenses.license((mavenLicense) ->
                {
                    mavenLicense.getName().set(license.name());
                    mavenLicense.getUrl().set(license.url());
                });
            }
        });

        mavenPom.developers((developers) ->
        {
            for (final Developer developer : pom.developers().get())
            {
                developers.developer((mavenDeveloper) ->
                {
                    mavenDeveloper.getId().set(developer.id());
                    mavenDeveloper.getName().set(developer.name());
                    mavenDeveloper.getEmail().set(developer.email());
                    mavenDeveloper.getUrl().set(developer.url());
                });
            }
        });
    }
}
