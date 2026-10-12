# Agrona: Groovy DSL to XDCL conversion report

Date: 2026-10-12. Branch: `xdcl-build` (nothing committed; all changes are in the working tree).

## Summary

The Agrona build is now fully XDCL: `build.gradle` and `buildSrc/` are gone, and the build passes
on the XDCL-enabled Gradle with the same outputs as the original Groovy build.

## Why a custom plugin was needed

The XDCL-enabled Gradle distribution (`9.8.0-branch-xdclRelease-20261012070500+0000`) ships only
one built-in project type, `plugin-development-ecosystem` (`xdclGradlePlugin` /
`xdclGradlePluginKotlin`), for building XDCL plugins. There is no Java-library project type, and a
`build.gradle.xdcl` can only configure a plugin-contributed template.

So the conversion moves all imperative logic into a declarative plugin (schema plus Java
reactions) in an included build, `build-logic`, which replaces `buildSrc`. Only data is left in
the `.xdcl` scripts.

## Tooling used

| Tool | Location |
|---|---|
| `xdcl-lsp`, `xdcl-lsp-client`, `xdcl-fmt`, `xdcl-gradle-sync` | `~/xdcl/xdcl-toolkit-0.1.0-dev.20261008.gd14cf948/bin` (on `PATH`) |
| XDCL-enabled Gradle | `~/xdcl/gradle-9.8.0-20261012070500+0000` (not on `PATH`; used directly) |
| Java | OpenJDK 21.0.11 |

- An older toolkit, `~/dev/xdcl/xdcl-tools-0.1.0-dev.20260819.g9a9535b1/bin`, is also on `PATH`
  but comes later, so it is never picked. It is worth removing from `PATH` so the two never get
  mixed.
- The toolkit version matches the one the XDCL downloads page listed on the day.
- Every sync, validation and build ran on the installed distribution in `~/xdcl`. The stock
  Gradle 9.8.1 the old wrapper pinned was never used.

## What changed

### New layout

```
settings.gradle            # one-line marker stub (settings.gradle.xdcl wins)
settings.gradle.xdcl       # includedBuilds, plugin, repositories, include
build.gradle.xdcl          # agronaBuild { }           (root)
agrona/build.gradle.xdcl                    # agronaLibrary { … }
agrona-benchmarks/build.gradle.xdcl         # shadedTool { … }
agrona-concurrency-tests/build.gradle.xdcl  # shadedTool { … jcstress { … } }
agrona-native-lib/build.gradle.xdcl         # zigNativeLibrary { }
build-logic/               # replaces buildSrc
  settings.gradle          # marker stub
  settings.gradle.xdcl     # plugin-development-ecosystem + repositories
  build.gradle.xdcl        # xdclGradlePlugin { dependencies { … } }
  src/main/xdcl/agrona-build.xdsl      # schema
  src/main/xdcl/org.agrona.build.xdcl  # plugin descriptor (reactions list)
  src/main/java/org/agrona/build/…     # reactions, helpers, moved buildSrc classes
```

Deleted: `build.gradle`, `buildSrc/`.

### Schema (`agrona-build.xdsl`, package `org.agrona.build.dsl`)

- `type DependencyNotation = String | Symbol`: `"g:a:v"` coordinates, or a Symbol such as
  `:libs.jmh.core` (version-catalog library) or `:projects.agrona` (project dependency).
- `record ModuleDependencies`: `api`, `implementation`, `compileOnly`, `runtimeOnly`,
  `annotationProcessor`, `testImplementation`, `testRuntimeOnly`, `testPlatform`.
- `trait JavaModule`: `javaRelease` (default 17) and `dependencies`.
- Templates: `AgronaBuild` (`agronaBuild`), `AgronaLibrary` (`agronaLibrary`), `ShadedTool`
  (`shadedTool`), `ZigNativeLibrary` (`zigNativeLibrary`).
- Records for the pom (`Pom`, `Scm`, `License`, `Developer`) and `Jcstress`.

### Java classes in `build-logic`

| Class | What it ports from the old `build.gradle` |
|---|---|
| `BuildSettings` | `BUILD_JAVA_VERSION` parsing, `version.txt`, property/env lookups, version catalog access, dependency notation resolution |
| `JavaConventions` | the `allprojects` and `subprojects` blocks: resolution strategy, toolchain, Checkstyle, compiler flags, test JVM setup, Javadoc |
| `AgronaLibraryReaction` | `project(':agrona')`: publishing, signing, ByteBuddy, generated source set, generators, native library bundling, jars |
| `ShadedToolReaction` | `:agrona-benchmarks` and `:agrona-concurrency-tests`: shadow jar, `concurrencyTests` |
| `ZigNativeLibraryReaction` + `ZigBuild` | `project(':agrona-native-lib')`: `bootstrapZig`, `compileNative` |
| `AgronaBuildReaction` | root project: `testReport`, `copyTestLogs`, `tarTestLogs`, `uploadArtifactsToCentralPortal`, `dependencyUpdates` filter, default tasks |

`UnsafeApiSourceGenerator`, `UnsafeApiBytecodeGenerator` and
`SonatypeCentralPortalUploadRepositoryTask` moved unchanged from `buildSrc`.

### Other files

- **Wrapper:** repinned from stock `9.8.1-all` to
  `9.8.0-branch-xdclRelease-20261012070500+0000-bin`, checksum-pinned
  (`55408e192266a1528e31a4dc88749e85737efa0b506c02bc6816ccf3dcde5e0b`). It was generated with the
  installed distribution in `~/xdcl`, so no stock Gradle was involved.
- **`settings.gradle`:** now a one-line marker stub.
- **`.gitignore`:** the `buildSrc` exceptions now name `build-logic`.
- **`README.md`:** the build link points at `build.gradle.xdcl` and `build-logic`.

## What was verified

- **Full build:** `clean build` passes, including Checkstyle, `-Werror` compile and Javadoc, and
  the test suite (11m 48s).
- **Same outputs as before:** compared against a clean checkout of `master` built on the same
  Gradle distribution:
  - the generated pom is identical;
  - every jar has the same entries and sizes, and the same manifest;
  - `tasks --all` is identical apart from the root project's directory name;
  - the execution order of 98 tasks is identical across `clean build publish
    uploadArtifactsToCentralPortal concurrencyTests testReport tarTestLogs dependencyUpdates`.
- **Other tasks:** `tarTestLogs` and `dependencyUpdates` ran for real.
- **Scripts:** all `.gradle.xdcl` files and the schema pass `xdcl-gradle-sync sync` and
  `xdcl-lsp-client validate` through the repinned wrapper.

### Not verified

- The actual publish, signing, Central Portal upload and jcstress run were dry-run only.
- `bundleNativeLibrary` is skipped off Linux, so it did not run.
- The baseline build was run without tests, so there is no before/after test comparison.
- The CI workflows call the same `./gradlew` commands but were not run. They will now download a
  development snapshot of Gradle.

## Things to know

- **Warning on every build:** Gradle reports "Multiple settings script files" because the stub
  `settings.gradle` sits next to `settings.gradle.xdcl` (root and `build-logic`). The XDCL tooling
  guidance says to keep the stub for IDEs and the wrapper task; delete both stubs for a quiet
  build.
- **`mavenLocal()` is gone:** XDCL settings accept only `:mavenCentral`, `:gradlePluginPortal` or
  a URL.
- **Duplicated versions:** `build-logic/build.gradle.xdcl` hard-codes the ByteBuddy, json, shadow,
  versions-plugin and findbugs versions, which must be kept in sync with
  `gradle/libs.versions.toml` by hand. Subproject dependencies still come from the catalog.
- **`wrapper { distributionType = 'ALL' }` dropped:** only the `-bin` snapshot is published.
- **Pre-release toolchain:** the build now depends on a development snapshot of Gradle and of the
  XDCL tooling.

### Departures from the approved plan

- `build-logic` resolves from the Gradle Plugin Portal as well as Maven Central, because the
  ben-manes versions plugin is only published on the portal.
- `agrona` keeps `evaluationDependsOn` on the native-lib project rather than a task-path
  dependency. The ByteBuddy plugin resolves task dependencies at configuration time and failed
  with the task-path form.
- The Mockito agent configuration is now named `testAgent` instead of `mockitoAgent`.
- The baseline came from a clean worktree of `master` built with the XDCL distribution, not from
  stock Gradle 9.8.1 (its download was too slow). This compares the two build definitions on one
  Gradle version.

## How to work with the build

```
export BUILD_JAVA_HOME=$JAVA_HOME          # toolchains are resolved from this variable
./gradlew                                  # clean build

# after editing any .xdsl / .gradle.xdcl
xdcl-gradle-sync sync .
xdcl-lsp-client validate --root . settings.gradle.xdcl build.gradle.xdcl */build.gradle.xdcl \
    build-logic/settings.gradle.xdcl build-logic/src/main/xdcl/agrona-build.xdsl
```
