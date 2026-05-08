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
  id("composeai.maven-publishing")
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.kotlin.serialization)
}

dependencies {
  api(project(":data-render-core"))

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
