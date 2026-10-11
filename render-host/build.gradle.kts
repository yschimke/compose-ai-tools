// The render host, the bundle daemon and the git-backed preview history: rendering and history with
// no web server underneath (layer 1 in `docs/design/REPOSITORY_LAYERS.md`). Sources keep the
// `ee.schimke.composeai.cli.serve` package for source compatibility; published as `render-host`.
plugins {
  id("composeai.base-conventions")
  id("composeai.jvm-conventions")
  id("composeai.maven-publishing")
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.kotlin.serialization)
  // `FakeRenderSession`, which drives `ServeRenderHost` without a daemon; shared with `:cli` and
  // the server's tests. A fixture so it never reaches a runtime classpath.
  `java-test-fixtures`
}

dependencies {
  // `api` for everything in this module's public signatures, which `:cli` and the server's
  // `:server` compile against (`:data-*` products, `DaemonLaunchDescriptor`, `PreviewOverrides`,
  // `StreamFrameParams`). Project dependencies keep it built against the source tree it ships with.
  api(libs.composeai.preview.data.api)
  api(project(":bundle-format"))
  api(project(":bundle-coordinates"))
  api(libs.composeai.daemon.core)
  api(project(":render-session-api"))
  api(project(":render-session-subprocess"))
  api(libs.composeai.data.remotecompose.core)

  // Layer 0 contracts stay published coordinates: shape-only and below this module.
  api(libs.composeai.data.layoutinspector.core)
  api(libs.composeai.data.theme.core)
  // `ServeHost.parityIssues()` exposes the shape published by catalogs. The wire contract is
  // contracts'; this module owns only validation and storage behaviour.
  api(libs.composeai.parity.issues.protocol)
  // `ServeHost.guidelineResultFor()` exposes a catalog's published design-guideline results in the
  // contracts' shape; this module only validates and loads them (ServeGuidelineResultsStore).
  api(libs.composeai.design.guidelines.protocol)
  // Referenced by fully-qualified name (`ServePreview.overrides`), so easy to miss; public
  // signature, hence `api`.
  api(libs.composeai.data.preview.overrides.core)

  implementation(libs.composeai.common.io)
  implementation(project(":common-image-crop"))
  implementation(libs.kotlinx.serialization.json)
  implementation(libs.classgraph)

  testImplementation(kotlin("test"))
  // In-memory FileSystem for store tests; okio itself comes via `common-io`.
  testImplementation(libs.okio.fakefilesystem)

  testFixturesImplementation(kotlin("test"))
  // `FakeRenderSession` implements `RenderSession`, so the interface is part of the fixture's API.
  testFixturesApi(project(":render-session-api"))
}

kotlin {
  // compose-preview-server compiles against these coordinates on its own cadence, so every
  // declaration states its visibility and every public one its return type.
  explicitApi()

  @OptIn(org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation::class) abiValidation()
}

// `checkKotlinAbi` is not wired into `check` by the Kotlin Gradle plugin.
tasks.named("check") { dependsOn("checkKotlinAbi") }

composeAiMavenPublishing {
  coordinates(
    artifactId = "render-host",
    displayName = "Compose Preview — Render Host",
    description =
      "Daemon-backed preview rendering, packed-bundle materialisation and git-backed preview " +
        "history, without a web server. Backs the offline `bundle render`, `render matrix` and " +
        "`history manifest` commands, and is consumed by the preview server.",
  )
  inceptionYear.set("2026")
}

tasks.withType<Test>().configureEach {
  // JUnit 5 (`@Test`, `@TempDir`); without it the platform defaults to JUnit 4 and the tests don't
  // run.
  useJUnitPlatform()
}

// The module's layer test: the resolved runtime classpath must contain no web server (checked on
// the resolved graph, since transitive arrivals don't show in `dependencies {}`). HTTP clients
// (Ktor client, OkHttp via `:bundle-coordinates`) are allowed; a client opens no listening socket.
abstract class CheckRenderHostIsServerFree : DefaultTask() {
  @get:Input abstract val resolvedModules: SetProperty<String>

  @get:Input abstract val forbiddenPrefixes: ListProperty<String>

  @TaskAction
  fun check() {
    val prefixes = forbiddenPrefixes.get()
    val offenders =
      resolvedModules.get().filter { module -> prefixes.any { module.startsWith(it) } }.sorted()
    check(offenders.isEmpty()) {
      "`:render-host` resolved artifacts it exists to stay free of: " +
        offenders.joinToString(", ") +
        ". This module backs the OFFLINE `bundle render`, `render matrix` and `history manifest` " +
        "commands and is layer 1 because it opens no socket (docs/design/REPOSITORY_LAYERS.md). " +
        "Either the new code belongs in the preview server, or the dependency belongs behind an " +
        "interface this module implements."
    }
  }
}

tasks.register<CheckRenderHostIsServerFree>("checkRenderHostIsServerFree") {
  description = "Fails if a web server, mDNS or the Kotlin compiler reaches this module."
  group = "verification"

  resolvedModules.set(
    configurations.named("runtimeClasspath").flatMap { configuration ->
      configuration.incoming.artifacts.resolvedArtifacts.map { artifacts ->
        artifacts
          .mapNotNull { artifact ->
            (artifact.id.componentIdentifier as? ModuleComponentIdentifier)?.let {
              "${it.group}:${it.module}"
            }
          }
          .toSet()
      }
    }
  )

  // Prefixes, since the invariant is "no web server", not a specific engine.
  forbiddenPrefixes.set(
    listOf(
      "io.ktor:ktor-server",
      "org.jmdns:",
      // UI-builder service and protocol ownership belongs to the server's own published runtime.
      // Offline render/history callers must not regain that product surface transitively.
      "ee.schimke.composeai:ui-builder-protocol",
      // The Kotlin compiler frontend behind the playground's in-process compile. NOT
      // `kotlin-build-tools-api`, which is the interface and arrives via `:daemon:core`.
      "org.jetbrains.kotlin:kotlin-compiler",
      "org.jetbrains.kotlin:kotlin-build-tools-impl",
    )
  )
}

tasks.named("check") { dependsOn("checkRenderHostIsServerFree") }
