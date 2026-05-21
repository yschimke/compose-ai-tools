# Setting up `yschimke/compose-ai-contrib`

This is the Gradle scaffolding to drop into `yschimke/compose-ai-contrib`
after lifting the rest of `contrib/`. The new repo has two real Gradle
needs: (1) drive the lifted CI workflows, (2) host a contract test
that exercises the published `compose-preview` artifacts end-to-end
against the lifted Amper fixture. Everything else (the Amper / Bazel
fixtures themselves) is build-system-native and doesn't touch Gradle.

## Layout

```
compose-ai-contrib/
├── amper-android/                # lifted from compose-ai-tools/contrib/
├── amper-cmp-desktop/            # lifted
├── bazel/                        # lifted
├── bazel-apk/                    # lifted
├── docs/                         # lifted (amper.md, bazel.md)
│   ├── amper.md
│   └── bazel.md
├── contract-tests/
│   └── amper-cmp-desktop/        # NEW — Maven-Central-consuming version of
│       ├── build.gradle.kts      # render-session-subprocess's AmperContractTest
│       └── src/test/kotlin/...
├── .github/
│   └── workflows/
│       ├── amper-android.yml     # lifted; paths already point at amper-android/
│       ├── bazel.yml             # lifted; paths already point at bazel/ + bazel-apk/
│       └── contract-tests.yml    # NEW — runs :contract-tests:amper-cmp-desktop:test
├── settings.gradle.kts           # NEW — see below
├── build.gradle.kts              # NEW — empty root, conventions in :contract-tests
├── gradle/
│   ├── libs.versions.toml        # NEW — pin the ee.schimke.composeai versions
│   └── wrapper/                  # standard Gradle wrapper
├── gradlew                       # standard wrapper script
├── gradlew.bat
└── README.md                     # consumer-facing intro (not the migration plan)
```

## `settings.gradle.kts`

```kotlin
pluginManagement {
  repositories {
    gradlePluginPortal()
    mavenCentral()
  }
}

dependencyResolutionManagement {
  repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
  repositories {
    mavenCentral()
    // Optional: snapshots from Sonatype Central. Pin to a release version in
    // libs.versions.toml unless you're testing against an unreleased commit.
    maven("https://central.sonatype.com/repository/maven-snapshots/") {
      name = "ossSnapshots"
      content { includeGroup("ee.schimke.composeai") }
    }
  }
}

rootProject.name = "compose-ai-contrib"

include(":contract-tests:amper-cmp-desktop")
project(":contract-tests:amper-cmp-desktop").projectDir =
  file("contract-tests/amper-cmp-desktop")
```

## `gradle/libs.versions.toml`

```toml
[versions]
# Pin to a release tag from yschimke/compose-ai-tools. Bump in lockstep with
# upstream releases (release-please cuts a new tag for each merge, so the
# version line moves in `compose-ai-tools/.release-please-manifest.json`).
composeai = "0.11.0"
kotlin = "2.3.21"
junit = "4.13.2"
truth = "1.4.5"

[libraries]
composeai-preview-discovery = { module = "ee.schimke.composeai:preview-discovery", version.ref = "composeai" }
composeai-daemon-launch-builder = { module = "ee.schimke.composeai:daemon-launch-builder", version.ref = "composeai" }
composeai-render-cli = { module = "ee.schimke.composeai:render-cli", version.ref = "composeai" }
composeai-render-session-api = { module = "ee.schimke.composeai:render-session-api", version.ref = "composeai" }
composeai-render-session-subprocess = { module = "ee.schimke.composeai:render-session-subprocess", version.ref = "composeai" }
composeai-daemon-desktop = { module = "ee.schimke.composeai:daemon-desktop", version.ref = "composeai" }
composeai-daemon-core = { module = "ee.schimke.composeai:daemon-core", version.ref = "composeai" }
composeai-data-render-connector = { module = "ee.schimke.composeai:data-render-connector", version.ref = "composeai" }
composeai-data-render-core = { module = "ee.schimke.composeai:data-render-core", version.ref = "composeai" }
junit = { module = "junit:junit", version.ref = "junit" }
truth = { module = "com.google.common.truth:truth", version.ref = "truth" }

[plugins]
kotlin-jvm = { id = "org.jetbrains.kotlin.jvm", version.ref = "kotlin" }
kotlin-serialization = { id = "org.jetbrains.kotlin.plugin.serialization", version.ref = "kotlin" }
```

## `contract-tests/amper-cmp-desktop/build.gradle.kts`

```kotlin
plugins {
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.kotlin.serialization)
}

dependencies {
  // Builder + serializer for daemon-launch.json. The contract test assembles
  // a descriptor from the resolved render-side classpath plus the lifted
  // Amper fixture's `kotlin-output/`.
  testImplementation(libs.composeai.daemon.launch.builder)
  testImplementation(libs.composeai.render.session.subprocess)
  testImplementation(libs.composeai.render.session.api)

  // The render-side classpath the descriptor will reference. Surfaced into
  // the test JVM as a system property so the test can substitute the
  // user-classes path without re-resolving anything.
  rendererRuntime(libs.composeai.daemon.desktop)
  rendererRuntime(libs.composeai.daemon.core)
  rendererRuntime(libs.composeai.data.render.connector)
  rendererRuntime(libs.composeai.data.render.core)

  testImplementation(libs.junit)
  testImplementation(libs.truth)
}

val rendererRuntime by configurations.creating

tasks.test {
  // The renderer-side classpath in a single sysprop so the contract test
  // doesn't have to know about Gradle's configurations API at runtime.
  systemProperty(
    "contrib.rendererClasspath",
    rendererRuntime.asPath,
  )
  // Lifted Amper fixture lives at the repo root.
  systemProperty(
    "contrib.amperFixtureDir",
    rootProject.layout.projectDirectory.dir("amper-cmp-desktop").asFile.absolutePath,
  )
}
```

## `contract-tests/amper-cmp-desktop/src/test/kotlin/.../AmperContractTest.kt` — sketch

Lift from
[`render-session/subprocess/src/test/.../AmperContractTest.kt`](https://github.com/yschimke/compose-ai-tools/blob/main/render-session/subprocess/src/test/kotlin/ee/schimke/composeai/render/session/subprocess/AmperContractTest.kt)
with three changes:

1. Replace the `:samples:cmp:composePreviewDaemonStart`-derived classpath
   discovery with `System.getProperty("contrib.rendererClasspath").split(":")`.
2. Replace the hand-rolled `writeDescriptor(...)` JSON builder with
   `DaemonLaunchBuilder.build(...)` + `DaemonLaunchBuilder.encode(descriptor)`.
3. Read the fixture from `System.getProperty("contrib.amperFixtureDir")`
   instead of walking up to a Gradle workspace root.

The self-skip behaviour (no Amper outputs on disk → exit cleanly)
stays — Amper outputs are produced by `./amper build` in the fixture
dir, which CI runs via `.github/workflows/amper-android.yml`'s
sibling.

## `.github/workflows/contract-tests.yml` — sketch

```yaml
name: contract tests

on:
  pull_request:
  workflow_dispatch:

jobs:
  amper-cmp-desktop:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@<sha>
      - uses: actions/setup-java@<sha>
        with: { distribution: temurin, java-version: 17 }

      - name: Amper build (produces kotlin-output/ for the contract test)
        working-directory: amper-cmp-desktop
        env:
          AMPER_JAVA_OPTIONS: >-
            -ea -XX:+EnableDynamicAgentLoading
            -Djavax.net.ssl.trustStore=/etc/ssl/certs/java/cacerts
            -Djavax.net.ssl.trustStorePassword=changeit
        run: ./amper build

      - name: Contract test
        run: ./gradlew :contract-tests:amper-cmp-desktop:test --stacktrace
```

## Notes

- **No `includeBuild`.** All deps via Maven Central. This is what makes
  compose-ai-contrib a real "downstream consumer" — anything that
  works here also works for a third-party adopter.
- **Single-version pinning.** `composeai = "0.11.0"` in
  `libs.versions.toml` is the only place to bump when consuming a
  newer release. The renderer side and the library side are versioned
  together.
- **No render-cli dep in the contract test.** The test drives
  `SubprocessRenderSessions` directly because that's the most direct
  proof the published artifacts work end-to-end. A separate test (or
  a `bazel build`-only smoke target) can exercise `render-cli`'s
  `java -jar` path.
