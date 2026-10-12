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
import org.gradle.api.GradleException;
import org.gradle.api.JavaVersion;
import org.gradle.api.Project;
import org.gradle.api.artifacts.MinimalExternalModuleDependency;
import org.gradle.api.artifacts.VersionCatalog;
import org.gradle.api.artifacts.VersionCatalogsExtension;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.util.Map;

/**
 * Settings shared by all projects of the build: versions, environment overrides and the version catalog.
 */
final class BuildSettings
{
    static final String GROUP = "org.agrona";

    private static final String LIBS_PREFIX = "libs.";
    private static final String PROJECTS_PREFIX = "projects.";

    private BuildSettings()
    {
    }

    static String rawBuildJavaVersion()
    {
        final String version = System.getenv("BUILD_JAVA_VERSION");
        return null != version && !version.isEmpty() ? version : JavaVersion.current().getMajorVersion();
    }

    static boolean isEarlyAccessJavaVersion()
    {
        return rawBuildJavaVersion().endsWith("-ea");
    }

    static int buildJavaVersion()
    {
        String buildJavaVersion = rawBuildJavaVersion();
        if (buildJavaVersion.indexOf('.') > 0)
        {
            buildJavaVersion = buildJavaVersion.substring(0, buildJavaVersion.indexOf('.'));
        }
        if (buildJavaVersion.indexOf('-') > 0)
        {
            buildJavaVersion = buildJavaVersion.substring(0, buildJavaVersion.indexOf('-'));
        }
        return Integer.parseInt(buildJavaVersion);
    }

    static String agronaVersion(final Project project)
    {
        try
        {
            return Files.readString(project.getRootProject().file("version.txt").toPath()).trim();
        }
        catch (final IOException ex)
        {
            throw new UncheckedIOException(ex);
        }
    }

    static boolean isReleaseVersion(final Project project)
    {
        return !agronaVersion(project).endsWith("-SNAPSHOT");
    }

    static String configProperty(final Project project, final String projectPropertyName, final String envVarName)
    {
        final Object property = project.getRootProject().findProperty(projectPropertyName);
        String value = null != property ? property.toString() : null;
        if (null == value || value.isEmpty())
        {
            value = System.getenv(envVarName);
            if (null == value || value.isEmpty())
            {
                return null;
            }
        }

        value = value.trim();

        return !value.isEmpty() ? value : null;
    }

    static VersionCatalog libs(final Project project)
    {
        return project.getExtensions().getByType(VersionCatalogsExtension.class).named("libs");
    }

    static String catalogVersion(final Project project, final String alias)
    {
        return libs(project).findVersion(alias)
            .orElseThrow(() -> new GradleException("No version '" + alias + "' in the version catalog"))
            .getRequiredVersion();
    }

    static MinimalExternalModuleDependency catalogLibrary(final Project project, final String alias)
    {
        return libs(project).findLibrary(alias)
            .orElseThrow(() -> new GradleException("No library '" + alias + "' in the version catalog"))
            .get();
    }

    /**
     * Resolve a dependency notation of a build script into something Gradle's dependency handler accepts.
     */
    static Object dependency(final Project project, final DependencyNotation notation)
    {
        if (notation instanceof DependencyNotation.StringValue)
        {
            return ((DependencyNotation.StringValue)notation).value();
        }

        final String symbol = ((DependencyNotation.SymbolValue)notation).value();
        if (symbol.startsWith(LIBS_PREFIX))
        {
            return catalogLibrary(project, symbol.substring(LIBS_PREFIX.length()));
        }
        else if (symbol.startsWith(PROJECTS_PREFIX))
        {
            return project.getDependencies().project(
                Map.of("path", ":" + symbol.substring(PROJECTS_PREFIX.length())));
        }

        throw new GradleException(
            "Unsupported dependency ':" + symbol + "': expected ':libs.<alias>' or ':projects.<name>'");
    }
}
