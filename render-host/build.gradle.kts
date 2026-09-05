// The render host, the bundle daemon and the git-backed preview history — what renders and reads
// history, with no web server underneath it.
//
// **This module came home.** It was written inside the `serve` package, so when the server was
// extracted to yschimke/compose-preview-server it went along, and `:cli` had to reach back across a
// repository boundary for it: `bundle render`, `render matrix`, `history manifest` and the
// missing-render report are OFFLINE commands that opened no socket, yet depended on a published
// artifact from the repository that owns the web server. That is one of the two edges of the
// dependency cycle in yschimke/compose-preview-server#180, and the half with no reason to exist —
// the module has zero project dependencies inside the server, and its entire dependency block is
// compose-ai-tools and contracts coordinates.
//
// `docs/design/REPOSITORY_LAYERS.md` settles where it belongs and the answer is here: layer 1 is
// behaviour that opens no socket, and this renders. Nothing about the sources changed in the move.
//
// Coordinate change, deliberate: it published as `compose-preview-render-host` from the server and
// publishes as `render-host` from here. Keeping the old coordinate would mean two repositories
// publishing one artifact on two version lines — a 1.x release that sorts BELOW the 2.x the server
// already shipped, which is a downgrade to every resolver and to Renovate. A new coordinate in this
// repository's own naming (`bundle-format`, `daemon-core`, `render-session-api`) has neither
// problem, and the old one stays resolvable at its final 2.x for anyone pinned to it.
//
// Package note: the sources keep `ee.schimke.composeai.cli.serve`, exactly as they did in the
// server. The rename is a separately reviewed change in both repositories, and keeping it is what
// makes this move source-compatible — `:cli`'s call sites do not change at all, they just resolve
// from a module in the right repository.
//
// One simplification the move earns for free. In the server this project was named `render-host`
// but published as `compose-preview-render-host`, so `java-test-fixtures` derived a capability
// (`…:render-host-test-fixtures`) that did not match what a consumer's `testFixtures(...)` asks for
// (`…:compose-preview-render-host-test-fixtures`), and the build file carried a block of
// configuration plus a `checkTestFixturesCapabilities` guard to reconcile them. Here the project
// name and the artifactId are the same string, so the derived capability is already the right one
// and all of that goes away.
plugins {
  id("composeai.base-conventions")
  id("composeai.jvm-conventions")
  id("composeai.maven-publishing")
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.kotlin.serialization)
  // `FakeRenderSession` — the fake `RenderSession` that drives `ServeRenderHost` without a daemon
  // subprocess. Shared by this module's own tests, `:cli`'s `BundleRenderKnobTest`, and the
  // server's live-host and session-registry tests across the repository boundary. A fixture rather
  // than a `main` source: it must not reach any runtime classpath.
  `java-test-fixtures`
}

dependencies {
  // `api` for everything appearing in this module's own public signatures, because both `:cli` here
  // and `:server` in the other repository write against those types directly: `ServeRenderHost`
  // returns products from `:data-*`, `ServeBundleDaemon.materialize` takes a
  // `DaemonLaunchDescriptor`, and `ServeHost` exposes `PreviewOverrides` and `StreamFrameParams`.
  //
  // Project dependencies where the server had to name published coordinates. That substitution is
  // the point of the move: this module is compiled and tested against the same source tree it ships
  // beside, instead of against whichever compose-ai-tools release the server happened to pin. The
  // skew that made #180 worth filing — the render host built against 1.62.0 while running against
  // main — cannot recur from this side.
  api(project(":preview-data-api"))
  api(project(":bundle-format"))
  api(project(":bundle-coordinates"))
  api(project(":daemon:core"))
  api(project(":render-session-api"))
  api(project(":render-session-subprocess"))
  api(project(":data-remotecompose-core"))

  // Layer 0. These stay published coordinates in both repositories: contracts is shape-only and
  // below us, which is exactly the dependency direction the layer rule allows.
  api(libs.composeai.data.layoutinspector.core)
  api(libs.composeai.data.theme.core)
  // `ServeHost.parityIssues()` exposes the shape published by catalogs. The wire contract is
  // contracts'; this module owns only validation and storage behaviour.
  api(libs.composeai.parity.issues.protocol)
  // Both reached by FULLY-QUALIFIED name rather than an import, so they are easy to miss when
  // reading the sources for what this module needs: `ServePreview.overrides` is declared as
  // `List<ee.schimke.composeai.data.overrides.PreviewOverrideDeclaration>`. A public signature,
  // hence `api`.
  api(libs.composeai.data.preview.overrides.core)

  implementation(libs.composeai.common.io)
  implementation(project(":common-image-crop"))
  implementation(libs.kotlinx.serialization.json)

  testImplementation(kotlin("test"))
  // In-memory FileSystem for the store tests, which assert on-disk output without touching the real
  // FS. Okio itself is on the compile classpath via `common-io`; the fake ships separately.
  testImplementation(libs.okio.fakefilesystem)

  testFixturesImplementation(kotlin("test"))
  // `FakeRenderSession` implements `RenderSession`, so the interface is part of the fixture's own
  // signature rather than an implementation detail of it.
  testFixturesApi(project(":render-session-api"))
}

kotlin {
  // Published across a repository boundary: compose-preview-server compiles against these
  // coordinates on its own release cadence (yschimke/compose-preview-server#289), so every
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
  // JUnit 5: the moved tests use the Jupiter `@Test` and `@TempDir`, which is what `kotlin("test")`
  // resolves once the platform is selected. Without this the platform defaults to JUnit 4 and the
  // moved test classes do not run.
  useJUnitPlatform()
}

// The whole point of the module, asserted against the RESOLVED runtime classpath rather than the
// `dependencies {}` block above — a transitive Ktor server would not show up in the block, and
// reading the block back would only re-state what someone just wrote.
//
// Ported unchanged in substance from the server, where it was `checkRenderHostIsServerFree`, and it
// matters more here rather than less: `docs/design/REPOSITORY_LAYERS.md` puts this module in layer
// 1
// precisely because it opens no socket, so this task is the layer test for it. It is also narrower
// than `checkLayerBoundary`, which asks where a coordinate comes from; this asks what kind of thing
// it is.
//
// Scoped to what this module can actually hold out. The Ktor CLIENT and OkHttp arrive through
// `:bundle-coordinates` (resolving coordinates is an HTTP fetch by nature) and
// `kotlin-build-tools-api` — the interface, not the compiler — through `:daemon:core`. A check that
// asserted "no HTTP at all" would fail on the commit introducing it, which is the same as not
// having one. A client is not a server: `bundle render` opens no listening socket either way.
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

  // Prefixes rather than exact coordinates: `io.ktor:ktor-server-cio` today, but the invariant is
  // "no web server", and an exact list would pass the first time someone swaps CIO for Netty.
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
