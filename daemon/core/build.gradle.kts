// Renderer-agnostic daemon core — see docs/daemon/DESIGN.md § 4
// ("Renderer-agnostic surface").
//
// Plain JVM module: holds the JSON-RPC server, the @Serializable protocol
// types, and the abstract `RenderHost` interface. Both
// `:daemon:android` (Robolectric backend) and `:daemon:desktop` depend on
// this module and contribute their own concrete `RenderHost` implementation.
//
// **Published to Maven Central** as `ee.schimke.composeai:daemon-core` —
// public surface for embedders (Gradle plugin, future Maven/IntelliJ plugins,
// third-party tooling) so the daemon JAR can be consumed by coordinate rather
// than included-build wiring. Pre-1.0; API may break across minor versions —
// see DESIGN.md § 17 (decisions log).

plugins {
  id("composeai.base-conventions")
  id("composeai.maven-publishing")
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.kotlin.serialization)
}

dependencies {
  api(project(":data-render-core"))

  // Okio-based file IO (`SystemFileSystem`) for data-product reads, history sidecars, bundle IR
  // replay, and forensics dumps. `implementation` — Okio stays an internal detail; daemon/core's
  // public surface keeps its java.io.File signatures.
  implementation(project(":common-io"))

  // JVM client for the native XR render server — the daemon fronts it for `xr/…`
  // (RENDERER_SERVICE). `api`, not `implementation`: `JsonRpcServer`'s public constructor exposes
  // `XrRenderServerFactory?`, so the type is part of this published module's compile ABI.
  api(project(":renderer-xr-client"))

  // Protocol message types are @Serializable. Exposed as `api` so downstream
  // daemon modules (e.g. :daemon:android) get
  // kotlinx-serialization-json on their compile classpath without re-declaring
  // it — they instantiate `Json {}` and reference protocol types directly.
  api(libs.kotlinx.serialization.json)

  // B2.2 phase 2 — IncrementalDiscovery's scoped @Preview scan uses ClassGraph,
  // mirroring the gradle-plugin's DiscoverPreviewsTask. Layered as `implementation`
  // because it's an internal detail of the daemon-side discovery pass; not part of
  // the renderer-agnostic protocol surface that downstream :daemon:android /
  // :daemon:desktop modules consume from this module's `api`.
  implementation(libs.classgraph)

  // Stage-2 in-process compile (COMPILE-IN-PROCESS.md). `BtaCompileSession` +
  // `DefaultBtaCompileService.fromSysprops` link against the Build Tools API
  // unconditionally at daemon startup, so the API jar must be on the daemon JVM's
  // main classpath even before the editor opts in via the VS Code workspace
  // setting — `fromSysprops` parses the descriptor's `btaCompile` sysprops and
  // constructs `CompilerPlugin(...)` eagerly. The API surface is small
  // (interfaces only). The corresponding *impl* JARs (kotlin-build-tools-impl
  // + kotlin-compiler-embeddable + compose-plugin) are supplied by the gradle
  // plugin's `DaemonClasspathDescriptor` and loaded into BTA's isolated
  // classloader lazily on the first `compileSources` call. That classloader is
  // rooted at `SharedApiClassesClassLoader()`, which delegates API class lookups
  // up to *this* parent classpath — so having the API jar on the daemon's main
  // classpath is also required for the impl side to resolve shared types
  // correctly.
  implementation("org.jetbrains.kotlin:kotlin-build-tools-api:${libs.versions.kotlin.get()}")
  testImplementation("org.jetbrains.kotlin:kotlin-build-tools-api:${libs.versions.kotlin.get()}")

  testImplementation(libs.junit)
}

// Bake the daemon's own version into a resource so `JsonRpcServer.initialize` can report the
// real release back to VS Code instead of the `0.0.0-dev` fallback. Mirrors
// `gradle-plugin/build.gradle.kts`'s `generatePluginVersionResource` and `cli/build.gradle.kts`'s
// `generateCliVersionResource`.
val generateDaemonVersionResource by tasks.registering {
  val outputDir = layout.buildDirectory.dir("generated/daemon-version-resource")
  val daemonVersion = project.version.toString()
  inputs.property("version", daemonVersion)
  outputs.dir(outputDir)
  doLast {
    val file = outputDir.get().file("ee/schimke/composeai/daemon/daemon-version.properties").asFile
    file.parentFile.mkdirs()
    file.writeText("version=$daemonVersion\n")
  }
}

sourceSets.main.get().resources.srcDir(generateDaemonVersionResource)

composeAiMavenPublishing {
  coordinates(
    artifactId = "daemon-core",
    displayName = "Compose Preview — Daemon Core",
    description =
      "Renderer-agnostic core of the compose-preview daemon: JSON-RPC server, protocol types (@Serializable), and the RenderHost abstraction. Pre-1.0; consumed by daemon-android and daemon-desktop.",
  )
  inceptionYear.set("2025")
}
