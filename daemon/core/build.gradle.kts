// Renderer-agnostic daemon core — see docs/daemon/DESIGN.md § 4
// ("Renderer-agnostic surface").
//
// Plain JVM module: holds the JSON-RPC server and the abstract `RenderHost`
// interface. The @Serializable protocol types it dispatches live in
// `:daemon-protocol`, exposed here as `api` — see docs/design/PREVIEW_SERVER_SPLIT.md
// ("`daemon-core` was a contract 14x the size of the contract"). Both
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
  // The wire shapes, split out for #3824. `api`, not `implementation`: this module's own public
  // surface is stated in protocol types — `RenderHost` returns a `RenderNowResult`, `JsonRpcServer`
  // dispatches protocol requests — and every existing consumer of
  // `ee.schimke.composeai.daemon.protocol.*` reaches them through this module, so they stay on its
  // compile ABI. The package did not move; only the module boundary around it did.
  api(project(":daemon-protocol"))

  // The device catalog, split out for #3824. `api` for the same reason as the protocol: this
  // module's own surface names `DeviceDimensions`, and every existing consumer of
  // `ee.schimke.composeai.daemon.devices.*` reaches it through here. The package did not move.
  api(project(":daemon-devices"))

  // In-process Kotlin compile, split out for #3824. `api` because `JsonRpcServer`'s constructor
  // takes a `BtaCompileService`, so the type is on this module's compile ABI, and because every
  // existing consumer of `ee.schimke.composeai.daemon.bta.*` reaches it through here.
  api(project(":daemon-bta"))

  api(project(":data-render-core"))

  // Semantics-tree models + structural differ (issue #1785). `api`, not `implementation`: the
  // published `HistoryDiffResult.semanticsDelta` field is a `SemanticsDelta`, so the type is part
  // of this module's compile ABI. Pure-JVM core module (no Compose/Android) — safe on the
  // renderer-agnostic daemon classpath.
  api(project(":data-layoutinspector-core"))

  // Theme-token models + structural differ (issue #1873). `api`, not `implementation`: the
  // published `HistoryDataDelta.theme` field is a `ThemeDelta`, so the type is part of this
  // module's compile ABI. Pure-JVM core module (no Compose/Android) — safe on the
  // renderer-agnostic daemon classpath, same as `:data-layoutinspector-core`.
  api(project(":data-theme-core"))

  // `PreviewOverrideValue` (the plain-Compose named-override value type) lives in the published
  // `:data-preview-overrides-core` so the runtime/producer/MCP clients depend on the override
  // schema
  // without dragging the daemon onto a preview's classpath. The protocol's
  // `PreviewOverrides.namedOverrides` field references it, so it's part of this module's compile
  // ABI
  // → `api`, not `implementation`. Pure-JVM (kotlinx-serialization only), safe on the daemon
  // classpath; the module has no dependency back on `:daemon:core`, so no cycle.
  api(project(":data-preview-overrides-core"))

  // Okio-based file IO (`SystemFileSystem`) for data-product reads, history sidecars, bundle IR
  // replay, and forensics dumps. `implementation` — Okio stays an internal detail; daemon/core's
  // public surface keeps its java.io.File signatures.
  implementation(project(":common-io"))

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

  // Stage-2 in-process compile. `BtaCompileSession` +
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
val generateDaemonVersionResource =
  tasks.register("generateDaemonVersionResource") {
    val outputDir = layout.buildDirectory.dir("generated/daemon-version-resource")
    val daemonVersion = project.version.toString()
    inputs.property("version", daemonVersion)
    outputs.dir(outputDir)
    doLast {
      val file =
        outputDir.get().file("ee/schimke/composeai/daemon/daemon-version.properties").asFile
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

kotlin {
  // `explicitApi()` — every declaration states its visibility, every public one its return type.
  // This is the JSON-RPC protocol contract an extracted preview server compiles against across a
  // repo boundary (#3824), and the widest surface of the twelve: 776 declarations. An
  // implicitly-public declaration here is an API decision nobody made.
  //
  // All of it is already in a shipped ABI, so the annotations record the existing surface rather
  // than changing it — narrowing any of these to `internal` would be a breaking change and is
  // deliberately not part of this pass. (`:daemon-client` in #4558 was the one module where
  // narrowing was free, because it had never been published.)
  explicitApi()

  // ABI dump gate, following `:rc-player-*`, `:daemon-client` and the eight contracts in #4561.
  // `checkKotlinAbi` diffs the real public ABI against the committed dump in `api/`, so a change to
  // the protocol surface is a diff in review rather than a downstream break. Regenerate with
  // `./gradlew :daemon:core:updateKotlinAbi`.
  @OptIn(org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation::class) abiValidation()
}

// `checkKotlinAbi` is not wired into `check` by the Kotlin Gradle plugin, so an unrecorded surface
// change would pass CI silently. Wire it explicitly — the gate is only worth having if it runs.
tasks.named("check") { dependsOn("checkKotlinAbi") }
