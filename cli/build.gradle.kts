import java.io.File
import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedArtifactResult
import org.gradle.api.attributes.Attribute
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.ClasspathNormalizer
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.TaskAction
import org.gradle.process.CommandLineArgumentProvider
import org.gradle.process.ExecOperations

plugins {
  id("composeai.base-conventions")
  id("composeai.jvm-conventions")
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.kotlin.serialization)
  application
}

// See gradle-plugin/build.gradle.kts for how CI sets PLUGIN_VERSION. Local
// builds derive the SNAPSHOT version from `.release-please-manifest.json`.
version =
  providers.environmentVariable("PLUGIN_VERSION").orNull
    ?: run {
      val manifest = rootDir.resolve(".release-please-manifest.json").readText()
      val current = Regex(""""\.":\s*"([^"]+)"""").find(manifest)!!.groupValues[1]
      val (major, minor, patch) = current.split(".").map { it.toInt() }
      "$major.$minor.${patch + 1}-SNAPSHOT"
    }

base { archivesName.set("compose-preview") }

application {
  applicationName = "compose-preview"
  mainClass.set("ee.schimke.composeai.cli.MainKt")
  // The Tooling API loads gradle-dist's native-platform jar into our JVM, which
  // calls `System.load`. On JDK 24+ that prints a 4-line restricted-method
  // warning on every CLI invocation. Pre-declaring native access for the
  // unnamed module (where Tooling API + native-platform live) silences it.
  applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED")
}

// Note: don't set `archiveFileName` directly — Gradle's distribution plugin
// uses it to derive the root directory inside the archive, so a full filename
// like `compose-preview-<version>.tar.gz` leaks the `.tar.gz` suffix into the
// extracted folder name. Setting `archiveExtension` instead lets Gradle compute
// the file name as `<archivesName>-<version>.<extension>` while keeping the
// internal root as `<archivesName>-<version>/`.
tasks.named<Tar>("distTar") {
  archiveExtension.set("tar.gz")
  compression = Compression.GZIP
}

// Sidecar configuration carrying the desktop renderer + its Compose Multiplatform runtime. Lives
// OUTSIDE `runtimeClasspath` so [CheckCliDaemonLibraryBoundary] keeps holding: the CLI's own JVM
// never loads renderer classes (no version-skew risk against consumer Compose), and the renderer
// only runs in the subprocess spawned by `compose-preview bundle render`.
//
// Resolved files are copied into `cli/build/install/compose-preview/lib-renderer/` by the
// distribution wiring below, and located at runtime via `APP_HOME/lib-renderer/` (the same env
// var the generated `bin/compose-preview` script exports for its own classpath).
val composePreviewRenderer =
  configurations.create("composePreviewRenderer") {
    isCanBeResolved = true
    isCanBeConsumed = false
  }

// Sidecar configuration carrying the desktop daemon module (`:daemon:desktop`) plus its
// Compose Multiplatform runtime. Same isolation story as `composePreviewRenderer` above —
// never on the CLI's own classpath; only loaded by the subprocess JVM that
// `compose-preview bundle daemon` spawns.
//
// Resolved into `cli/build/install/compose-preview/lib-daemon-desktop/` and located at
// runtime via `APP_HOME/lib-daemon-desktop/`.
val composePreviewDaemonDesktop =
  configurations.create("composePreviewDaemonDesktop") {
    isCanBeResolved = true
    isCanBeConsumed = false
  }

// Sidecar configuration carrying the desktop/JVM embedded Remote Compose player
// (`:third-party-rc-embedded-player-jvm`). `compose-preview serve` spawns its `RcJvmRenderMain` as
// a one-shot subprocess to render a captured `ir/<id>.rc` to PNG for the viewer's cmp-jvm chip —
// the same subprocess-only isolation as the desktop daemon. Deliberately does NOT bundle Compose
// Multiplatform / Skiko: the subprocess classpath joins `lib-rcjvm/*` + `lib-daemon-desktop/*` at
// launch, and the daemon sidecar already carries the per-OS Compose + Skiko stack. Resolved into
// `cli/build/install/compose-preview/lib-rcjvm/`, located at runtime via `APP_HOME/lib-rcjvm/` (or
// `-Dcomposeai.cli.libRcjvmDir`).
val composePreviewRcJvm =
  configurations.create("composePreviewRcJvm") {
    isCanBeResolved = true
    isCanBeConsumed = false
  }

// Sidecar configuration carrying the Android (Robolectric) daemon module (`:daemon:android`).
// Same subprocess-only isolation as the desktop daemon above — never on the CLI's own classpath,
// only loaded by the JVM that `compose-preview bundle daemon` spawns for an `backend="android"`
// bundle, joined at launch time with the consumer's SDK `android.jar` (resolved from ANDROID_HOME).
//
// Unlike the desktop daemon, `:daemon:android` is an AGP `com.android.library`: a plain-JVM
// consumer like `:cli` can't natively resolve its runtime (AGP exposes it as an AAR with
// AAR-shaped transitive deps + AGP-generated R.jars). So we mirror `:daemon:harness`'s proven
// approach instead of staging resolved artifacts directly: `:daemon:android` exposes a
// `daemonHarnessClasspathFile` consumable configuration whose single artifact is a text file
// listing the absolute paths of every JAR on its debug-unit-test runtime classpath. We consume
// that descriptor here (matching attribute, zero AGP variants on the consumer side) and
// [StageDaemonAndroidLibs] copies the listed jars into `lib-daemon-android/`.
//
// Resolved into `cli/build/install/compose-preview/lib-daemon-android/` and located at runtime via
// `APP_HOME/lib-daemon-android/` (or `-Dcomposeai.cli.libDaemonAndroidDir`).
val composePreviewDaemonAndroid =
  configurations.create("composePreviewDaemonAndroid") {
    isCanBeResolved = true
    isCanBeConsumed = false
    attributes {
      attribute(
        Attribute.of("ee.schimke.composeai.daemon.harness.classpath", String::class.java),
        "android",
      )
    }
  }

// BTA (Kotlin Build Tools API) *implementation* classpath for the `serve --playground` in-process
// compile: `kotlin-build-tools-impl` (transitively `kotlin-compiler-embeddable` + runtime) plus the
// Compose compiler plugin. These are the jars the gradle plugin's `DaemonBootstrapTask` supplies to
// the editor daemon via sysprops — the serve host has no gradle plugin, so it stages them into the
// CLI install (`lib-bta/`) and loads them into BTA's isolated classloader at compile time. Never on
// the CLI's own classpath (a whole compiler frontend); resolved into
// `cli/build/install/compose-preview/lib-bta/`, located at runtime via `APP_HOME/lib-bta/` (or
// `-Dcomposeai.cli.libBtaDir`).
val composePreviewBta =
  configurations.create("composePreviewBta") {
    isCanBeResolved = true
    isCanBeConsumed = false
  }

// Sidecar configuration carrying `:usage-source-psi` — the Kotlin *parser* behind the usage
// cleaner.
// Same isolation story as `lib-bta/` above, and loaded together with it: the analyzer needs a
// frontend, and the frontend must never be on the CLI's own classpath. Resolved into
// `cli/build/install/compose-preview/lib-usage-psi/`, located at runtime via
// `APP_HOME/lib-usage-psi/` (or `-Dcomposeai.cli.libUsagePsiDir`).
//
// Just this module's jar: `:usage-source-psi` declares the frontend `compileOnly`, so its runtime
// closure is the Kotlin stdlib the CLI already ships, and the compiler jars ride in `lib-bta/`.
val composePreviewUsagePsi =
  configurations.create("composePreviewUsagePsi") {
    isCanBeResolved = true
    isCanBeConsumed = false
  }

// Gradle resolves a published `ee.schimke.composeai:<x>` coordinate to the workspace project that
// publishes it — but it matches on the project's *Gradle* identity (`group:name`), not on the
// `artifactId` its publication declares. Eight of the nine coordinates the published server pulls
// back into this build are top-level includes whose project name already equals their artifactId
// (`:bundle-format` -> `bundle-format`, and so on), so they substitute on their own.
//
// `daemon-core` is the one that does not: it is `include(":daemon:core")`, so its Gradle name is
// `core` and Gradle sees `ee.schimke.composeai:core`, which matches nothing in the server's POM.
// The
// result without this rule is both copies on the compile classpath — `project(":daemon:core")` from
// this file and `ee.schimke.composeai:daemon-core:1.53.0` dragged in by the server — which is a
// duplicate-class classpath (`composePreview.classpathDuplicates=fail` in gradle.properties exists
// for exactly this shape) and, worse, silently compiles half the CLI against a released copy of a
// module the workspace is actively changing.
//
// Substituting rather than excluding: an `exclude` would drop the coordinate for `:cli` but leave
// the server's own resolution unaware that a workspace project should stand in for it. Renaming the
// project to `daemon-core` in `settings.gradle.kts` would fix it structurally and is the better
// long-term answer; it moves a path every module and CI path filter names, so it is not part of
// this swap.
configurations.configureEach {
  resolutionStrategy.dependencySubstitution {
    substitute(module("ee.schimke.composeai:daemon-core"))
      .using(project(":daemon:core"))
      .because("published server POM names the artifactId; the project is `:daemon:core` (#4732)")
  }
}

dependencies {
  // The BTA implementation + Compose compiler plugin jars, staged into `lib-bta/` (see the
  // `composePreviewBta` configuration above). `kotlin-build-tools-impl` pulls
  // `kotlin-compiler-embeddable` and the rest of the frontend transitively.
  add(
    "composePreviewBta",
    "org.jetbrains.kotlin:kotlin-build-tools-impl:${libs.versions.kotlin.get()}",
  )
  add(
    "composePreviewBta",
    "org.jetbrains.kotlin:kotlin-compose-compiler-plugin-embeddable:${libs.versions.kotlin.get()}",
  )
  add("composePreviewUsagePsi", project(":usage-source-psi"))
  // BTA *interfaces only* — the CLI references `BtaCompileSession`'s build-tools-api parameter
  // types
  // (`CompilerPlugin`, `KotlinLogger`, `SourcesChanges`) to drive an in-process playground compile.
  // `:daemon:core` declares this as `implementation`, so it isn't transitive; the impl JARs ride in
  // `lib-bta/`, not here.
  implementation("org.jetbrains.kotlin:kotlin-build-tools-api:${libs.versions.kotlin.get()}")

  // SPIKE, test-only: the Kotlin frontend, for `PsiParseSpikeTest` to measure whether a
  // *parse-only*
  // PSI pass is cheap enough to replace the cleaner's text passes. Deliberately
  // `testImplementation`
  // and nothing else — the CLI's own runtime classpath must stay free of the frontend (see the
  // `lib-bta/` note above). If the spike says yes, the real change loads these jars through the
  // existing isolated `lib-bta/` classloader, not from here.
  testImplementation(
    "org.jetbrains.kotlin:kotlin-compiler-embeddable:${libs.versions.kotlin.get()}"
  )

  // Published wire-format DTOs (`PreviewResult`, `PreviewManifest`, the v1 a11y mirror types,
  // `ExtensionPayload`). `api` so the existing in-package imports across this module (and the
  // CLI tests) keep resolving without an explicit `import` change — same source-compat pattern
  // `:data-a11y-core` used for the D2.2 extraction. External consumers (contrib scripting,
  // third-party tooling) pull `:preview-data-api` directly, not transitively through `:cli`.
  api(project(":preview-data-api"))

  // The wire contract `compose-preview build-host` serves. `api` because `BuildHostCommand`'s
  // testable seam takes and returns protocol types, and the CLI's own tests drive it by them.
  //
  // Note the direction: this module holds shape only, so depending on it costs the CLI nothing it
  // did not already have, and the preview server can depend on the SAME module without acquiring
  // the Gradle Tooling API. That asymmetry is the point of the protocol
  // (yschimke/compose-preview-server#180, #9).
  api(project(":build-host-protocol"))
  implementation(project(":common-image-crop"))

  // Gradle Tooling-API render pipeline + the `GradleConnection` / `PreviewModule` /
  // `CapturedTestFailure` / `TerminalProgress` plumbing the CLI used to host inline. `api` again
  // for source-compat (existing in-package imports). Transitively brings in the Tooling-API and
  // slf4j-nop dependencies the CLI used to declare directly.
  api(project(":gradle-preview-driver"))

  // The preview-bundle format — reading/writing a `.previewbundle`, its manifest DTO, sidecar
  // injectors, deterministic zip helpers, the detached signature scheme, classpath hydration, and
  // the Android resource/launch support. Split out of this module for #3824; the `bundle`
  // subcommands stay here. `api` for source-compat: the types kept their `ee.schimke.composeai.cli`
  // package, so every existing call site (including `serve`) resolves them unchanged.
  api(project(":bundle-format"))
  api(libs.composeai.agent.grant.protocol)

  // Turning a bundle's recorded Maven coordinates back into local jars — the `bundle daemon` and
  // `bundle render` subcommands and `serve` all need it. Split out of this module for #3824
  // preparation item 7. `api` for source-compat with the existing in-package call sites.
  api(project(":bundle-coordinates"))

  // The render host, the bundle daemon and the git-backed preview history — what the OFFLINE
  // commands actually use.
  //
  // Eight of the twelve serve-package symbols this module's main sources reference live here:
  // `ServeRenderHost`, `ServeBundleDaemon`, `RenderOutcome`, `SvgOutcome`, `RenderFailureFrame`,
  // `PreviewHistory`, `PreviewHistoryManifest` and `ServeParameterRows` — `bundle render`,
  // `history manifest`, `render matrix` and the missing-render report. None of them opens a socket.
  //
  // A PROJECT dependency, not a published coordinate. The module was split out of the server in
  // compose-preview-server#38 and published as `compose-preview-render-host`, but it moved into
  // this repository in 1.77.0 (#5137) and the server now consumes it from here
  // (compose-preview-server#289) — it had zero project dependencies inside that build and lived
  // there only because it was written inside the `serve` package. `:cli` kept resolving the old
  // external coordinate until compose-preview-server 3.0.0 retired it at its final 2.x, which is
  // the second half of that move and what this line finishes.
  //
  // `api`, like the server dependency below and for the same reason: the call sites reference these
  // types in-package (`ee.schimke.composeai.cli.serve`), which the published artifact keeps.
  // The render host, the bundle daemon and the git-backed preview history — what the OFFLINE
  // commands actually use. This was `compose-preview-render-host`, published by the preview server,
  // until yschimke/compose-preview-server#180 moved the module to the repository everything it
  // depends on already lived in. It is a project here now, so `bundle render`, `render matrix` and
  // `history manifest` compile against the source tree they ship beside rather than against
  // whichever server release this module happened to pin.
  api(project(":render-host"))

  // The preview server is NO LONGER on this module's compile or runtime classpath.
  //
  // `serve` and `browse` were the last things holding it there, and they are launchers now: they
  // exec the published `compose-preview-server` binary instead of linking `ServeRunner`. That
  // removes the forward edge of the dependency cycle in yschimke/compose-preview-server#180, and it
  // is why `CheckLayerBoundary`'s allowlist of known layer-2 edges is now empty.
  //
  // What that actually removes from the distribution, measured rather than claimed: the
  // `compose-preview-serve` jar itself, `jmdns` (the `serve --lan` advertiser), and two Ktor
  // plugins nothing else asks for — `ktor-server-compression` and `-auto-head-response`.
  // Seventy-two jars to sixty-nine.
  //
  // `ktor-server-core`, `-cio`, `-content-negotiation`, `-sse` and `-websockets` do NOT leave, and
  // never were serve's to take: they arrive through `:mcp` and the MCP Kotlin SDK, because
  // `compose-preview mcp` runs a server of its own. An earlier draft of this comment claimed the
  // Ktor floor left with `serve`. It did not — checked against the built distribution, which is the
  // only way that claim was ever checkable.
  //
  // It survives as a TEST dependency, deliberately and narrowly. Two tests drive the CLI's own
  // HTTP clients against a real `ServeHttpServer`, and their whole purpose is to catch the two
  // repositories' independently-declared wire types drifting apart:
  //
  //   * `AgentAccessClientIntegrationTest` — the device-grant flow. Five of its cases approve or
  //     deny a grant by reaching into the server's store, which is only possible in-process.
  //   * `SharePreviewServeUploadTest` — one case, `a real serve host and this client agree`. The
  //     rest of that file drives a bare JDK `HttpServer` and needs nothing from here.
  //
  // A stub would make both tests pass while testing nothing they exist for: the point is checking
  // the halves against *each other*, not each against its own idea of the other. So the edge stays
  // where it earns its keep, and nowhere else.
  //
  // The exclusion is not optional. Today's `compose-preview-serve` POM still names the server's old
  // `compose-preview-render-host`, whose classes are the same classes, in the same package, as
  // `:render-host` above — two copies on one test classpath. Gradle substitutes a published
  // coordinate for a workspace project only when `<group>:<projectName>` matches, and
  // `compose-preview-render-host` does not match `render-host`, so it has to be said explicitly.
  // Once a server release names the new coordinate this becomes a no-op that can go.
  testImplementation(libs.composeai.preview.serve) {
    exclude(group = "ee.schimke.composeai", module = "compose-preview-render-host")
  }

  // Okio-based file IO (`SystemFileSystem` + suspend helpers) the CLI commands read/write through.
  implementation(libs.composeai.common.io)

  // mDNS/DNS-SD advertiser for `serve --lan` — publishes `_composeai._tcp` so the mobile/wear
  // session-viewer clients (`:clients:*`) discover the server on the LAN without a typed URL.

  implementation(libs.kotlinx.serialization.json)

  // Semantics text-diff engine + payload model for the `diff-semantics` command (issue #1785).
  implementation(libs.composeai.data.layoutinspector.core)

  // Material 3 resolved tokens + node-consumer attribution, joined to semantics for the live
  // Typography inspection layer.
  implementation(libs.composeai.data.theme.core)

  // `fonts/used` sidecar file name for `bundle pack --with-semantics` font carriage.
  implementation(project(":data-fonts-core"))

  // The renderer's own locale-direction rule, so `serve` resolves a published capture gutter's
  // leading/trailing edges onto left/right exactly as the render that produced the pixels did
  // (pseudolocale first, then the real language table) rather than keeping a second copy of it.
  implementation(project(":data-pseudolocale-core"))

  // Ktor client (OkHttp engine) for downloading a bundle when the open arg is a URL. The explicit
  // okhttp dep pins the engine to OkHttp 5.x — ktor-client-okhttp 3.0.3 only declares a transitive
  // 4.12.0, so without this the catalog's okhttp 5 version is never selected.
  implementation(libs.ktor.client.core)
  implementation(libs.ktor.client.okhttp)
  implementation(libs.okhttp)

  // Embedded Ktor server (CIO engine) backing `compose-preview serve` — the LAN preview server
  // that fronts a long-lived render session over HTTP. CIO is pure-Kotlin/coroutines (no Netty),
  // keeping the transitive + logging surface minimal. Same `ktor` version ref as the client above,
  // so the strict slf4j pin in the `constraints {}` block below covers the server too.
  // WebSockets plugin: the `serve` streamed-frame lane (`/ws/{id}`) — tier-2 streaming spike.
  // HEAD answers GET across the whole site — the probe link unfurlers send before downloading.

  // Bundle the MCP server so `compose-preview mcp serve` can invoke it in-process —
  // the consumer install story stays a single tarball + a single launcher.
  implementation(project(":mcp"))
  // Used directly by `DaemonSmokeCheck` (the spawn port + subprocess factory). Available
  // transitively through `:mcp`, but declared because this module compiles against it.
  implementation(project(":daemon-client"))
  // Renderer-agnostic daemon core helpers that are safe to use as a local library from CLI
  // commands. Keep renderer backends (`:daemon:android`, `:daemon:desktop`) out of this module.
  implementation(project(":daemon:core"))
  // ClassGraph for the `serve --playground` preview scan: a scoped `@Preview` enumeration of a
  // just-compiled snippet's classes dir (mirrors `:daemon:core`'s IncrementalDiscovery, which keeps
  // classgraph as its own `implementation` and so doesn't leak it here).
  implementation(libs.classgraph)
  // Wire-shape of the `compose/overrides` data product (`PreviewOverrideDeclaration`) — the
  // editable
  // knobs `compose-preview serve` reads from a bundle's `previews/<id>.overrides.json` sidecar to
  // present controls. Pure JVM (depends only on `:daemon:core`), not a renderer artifact.
  implementation(libs.composeai.data.preview.overrides.core)
  // Wire-shape of the `compose/remotecompose` data product (`RemoteComposeKnobDeclaration` /
  // `RemoteComposeDeclarationsPayload`) — the Remote Compose named-value knobs `serve` reads from a
  // bundle's `previews/<id>.remotecompose.json` sidecar to advertise editable controls. Pure JVM
  // (payload schema only; the alpha `androidx.compose.remote.*` deps live in the connector, not
  // here), so it stays off the renderer/daemon boundary the CLI guards.
  implementation(project(":data-remotecompose-core"))
  // `PreviewBackdrop` / `PreviewBackground` — the one chain that decides which ground a preview is
  // presented on, shared with both renderers and both daemons so the served pages cannot disagree
  // with the pixels. Pure JVM ARGB math, no Compose types, so it stays off the renderer/daemon
  // boundary the CLI guards for the same reason the two entries above do.
  implementation(libs.composeai.data.render.core)
  // Public render-session library — the CLI consumes its own published API for daemon-driven
  // commands (`compose-preview a11y` etc.) instead of touching DaemonClient directly. We eat
  // our own dog food: anything the CLI can do, a third-party tooling consumer can do via the
  // same API.
  implementation(project(":render-session-api"))
  implementation(project(":render-session-subprocess"))

  // `compose-preview bundle render` ships the desktop renderer + its full Compose Multiplatform
  // runtime in `lib-renderer/`. Subprocess only; never on the CLI's own classpath.
  add("composePreviewRenderer", project(":renderer-desktop"))

  // `compose-preview bundle daemon` ships the desktop daemon in `lib-daemon-desktop/`. Same
  // subprocess-only isolation. The Compose Multiplatform runtime (incl. Skiko) is *not*
  // bundled here — the subprocess classpath joins `lib-daemon-desktop/*` + `lib-renderer/*`
  // at launch time, and the renderer sidecar already carries the per-OS Compose stack.
  add("composePreviewDaemonDesktop", project(":daemon:desktop"))

  // `compose-preview serve` ships the desktop/JVM embedded Remote Compose player in `lib-rcjvm/`
  // for the cmp-jvm chip's one-shot render subprocess. Subprocess-only isolation; the Compose +
  // Skiko runtime is not bundled here (the subprocess joins `lib-rcjvm/*` +
  // `lib-daemon-desktop/*`).
  add("composePreviewRcJvm", libs.rcplayer.embedded.jvm)

  // `compose-preview bundle daemon` ships the Android (Robolectric) daemon in
  // `lib-daemon-android/`. This resolves `:daemon:android`'s `daemonHarnessClasspathFile`
  // descriptor (a text file of runtime jar paths) — see the configuration KDoc above — never the
  // AAR itself, so no AGP variant resolution leaks onto a plain-JVM consumer.
  add("composePreviewDaemonAndroid", project(":daemon:android"))

  // `:gradle-preview-driver` pulls `org.gradle:gradle-tooling-api`, whose shaded variant
  // *strictly* requires `slf4j-api:2.0.17`. Ktor 3.5.0 (and friends) pull `slf4j-api:2.0.18`
  // transitively onto this same runtime classpath, which Gradle can't reconcile against the
  // strict ceiling — `:cli:distTar`/`installDist`/etc. fail to resolve `runtimeClasspath`.
  // Pin slf4j-api to the strictly-required 2.0.17 so the soft 2.0.18 requests downgrade. Safe:
  // slf4j-api is a stable facade and 2.0.17↔2.0.18 are binary-compatible.
  constraints {
    implementation("org.slf4j:slf4j-api") {
      version { strictly("2.0.17") }
      because(
        "gradle-tooling-api 9.5.1 strictly requires slf4j-api 2.0.17; ktor 3.5.0 client+server " +
          "pull 2.0.18"
      )
    }
  }

  testImplementation(kotlin("test"))
  // In-memory FileSystem for tests that assert on-disk output without touching the real FS
  // (e.g. RenderMatrixCellNamesTest's stale-cell clearing). okio itself is on the compile
  // classpath transitively via `common:io`; the fake ships separately.
  testImplementation(libs.okio.fakefilesystem)

  // `FakeRenderSession`. `BundleRenderKnobTest` drives `bundle render --knob` against a fake render
  // session rather than spawning a daemon; the fixture lives with `ServeRenderHost`, which is what
  // it fakes, and the fixture variant keeps it off both modules' runtime classpaths.
  //
  // `testFixtures(project(":render-host"))` — the fixture moved with `ServeRenderHost` into
  // `:render-host` (compose-preview-server#38, then into this repository in #5137), and a project
  // dependency sidesteps capability matching entirely.
  //
  // Worth recording what that retires. `java-test-fixtures` derives the capability from the
  // *Gradle project* name, and the server is `:server` upstream while it publishes as
  // `compose-preview-serve`, so 2.0.0 advertised `ee.schimke.composeai:server-test-fixtures` and
  // `testFixtures(...)` matched nothing:
  //
  //     Unable to find a variant of ee.schimke.composeai:compose-preview-serve:2.0.0 with the
  //     requested capability: feature 'test-fixtures'
  //
  // We consumed it by naming that capability explicitly. Upstream has since fixed the server's
  // spelling too (keeping the legacy name alongside, so the workaround would still resolve), but
  // there is no reason to keep it: the fixture is not in that artifact any more.
  testImplementation(testFixtures(project(":render-host")))
  // Gradle TestKit drives a real Gradle build inside [InitScriptExclusiveContentReproducerTest] —
  // the only way to assert that the rendered init script doesn't trip Gradle 9.3+'s
  // `exclusiveContent`-vs-`buildscript.repositories` validation when the consumer's
  // pluginManagement repositories declare it (the Confetti `main` shape; issues #1470, #1482).
  testImplementation(gradleTestKit())
}

// Multiple JetBrains Compose Multiplatform `components-*-desktop` artifacts ship as
// `library-desktop-<version>.jar` (e.g. `components-resources-desktop` and
// `components-ui-tooling-preview-desktop`), so a flat copy into `lib-daemon-desktop/` collides on
// filename. Stage the resolved artifacts to a build directory first, disambiguating colliding
// filenames by Maven `module-version.jar`, so both end up on the daemon's classpath at runtime.
// The six Skiko native runtimes remain in daemon-desktop's published POM for Maven consumers, but
// are excluded from CLI staging: [SkikoNativeProvision] downloads only the current host's jar.
val stageDaemonDesktopLibs =
  tasks.register<Sync>("stageDaemonDesktopLibs") {
    description = "Stages :daemon:desktop runtime artifacts, renaming filename collisions."
    destinationDir = layout.buildDirectory.dir("staged-daemon-desktop-libs").get().asFile
    val artifactsProvider = composePreviewDaemonDesktop.incoming.artifacts.resolvedArtifacts
    from(
      artifactsProvider.map { resolved ->
        resolved
          .filterNot { it.file.name.startsWith("skiko-awt-runtime-") }
          .map(ResolvedArtifactResult::getFile)
      }
    )
    val nameByPath = artifactsProvider.map { resolved ->
      val staged = resolved.filterNot { it.file.name.startsWith("skiko-awt-runtime-") }
      val counts = staged.groupingBy { it.file.name }.eachCount()
      staged.associate { artifact ->
        val original = artifact.file.name
        val mapped =
          if (counts.getValue(original) > 1) {
            val id = artifact.id.componentIdentifier
            if (id is ModuleComponentIdentifier) "${id.module}-${id.version}.jar" else original
          } else original
        artifact.file.absolutePath to mapped
      }
    }
    inputs.property("nameByPath", nameByPath)
    eachFile {
      val mapped = nameByPath.get()[file.absolutePath]
      if (mapped != null) name = mapped
    }
  }

// The renderer configuration currently resolves the build host's Skiko native. Stage it through a
// filter as well, otherwise a macOS-built release would still embed a macOS native in the portable
// archive even after the daemon's six-platform closure was cleaned up.
val stageRendererLibs =
  tasks.register<Sync>("stageRendererLibs") {
    description = "Stages the desktop renderer runtime without host-specific Skiko natives."
    destinationDir = layout.buildDirectory.dir("staged-renderer-libs").get().asFile
    val artifactsProvider = composePreviewRenderer.incoming.artifacts.resolvedArtifacts
    from(
      artifactsProvider.map { resolved ->
        resolved
          .filterNot { it.file.name.startsWith("skiko-awt-runtime-") }
          .map(ResolvedArtifactResult::getFile)
      }
    )
  }

// Stage the JVM embedded player's runtime artifacts for `lib-rcjvm/`, disambiguating any colliding
// `library-desktop-<version>.jar` filenames by Maven `module-version.jar` — same reasoning as
// [stageDaemonDesktopLibs].
val stageRcJvmLibs =
  tasks.register<Sync>("stageRcJvmLibs") {
    description = "Stages the vendored JVM player's runtime artifacts for lib-rcjvm/."
    destinationDir = layout.buildDirectory.dir("staged-rcjvm-libs").get().asFile
    val artifactsProvider = composePreviewRcJvm.incoming.artifacts.resolvedArtifacts
    from(
      artifactsProvider.map { resolved ->
        resolved
          .filterNot { it.file.name.startsWith("skiko-awt-runtime-") }
          .map(ResolvedArtifactResult::getFile)
      }
    )
    val nameByPath = artifactsProvider.map { resolved ->
      val staged = resolved.filterNot { it.file.name.startsWith("skiko-awt-runtime-") }
      val counts = staged.groupingBy { it.file.name }.eachCount()
      staged.associate { artifact ->
        val original = artifact.file.name
        val mapped =
          if (counts.getValue(original) > 1) {
            val id = artifact.id.componentIdentifier
            if (id is ModuleComponentIdentifier) "${id.module}-${id.version}.jar" else original
          } else original
        artifact.file.absolutePath to mapped
      }
    }
    inputs.property("nameByPath", nameByPath)
    eachFile {
      val mapped = nameByPath.get()[file.absolutePath]
      if (mapped != null) name = mapped
    }
  }

// Stage the Android daemon's runtime jars from `:daemon:android`'s `daemonHarnessClasspathFile`
// descriptor (a newline-separated text file of absolute jar paths, ordered module-jar →
// testFixtures → R.jar → full test runtime → android.jar; see that module's
// `writeDaemonClasspath`).
// We copy each listed jar into a build dir so the distribution wiring can fold it into
// `lib-daemon-android/`. Two deliberate transforms:
//   - `android.jar` is dropped: it's the SDK platform jar (redistribution-sensitive, and
//     `BundleDaemonCommand.androidDaemonLaunch` re-adds it from the consumer's ANDROID_HOME at
//     launch), so it must not ride along in the shipped tarball.
//   - filenames are index-prefixed (`%04d-<name>`) to (a) keep the descriptor's classpath
//     precedence intact under the `lib-daemon-android/*` glob the daemon launcher expands and
//     (b) dodge basename collisions on AAR `classes.jar` / AGP-generated `R.jar`.
abstract class StageDaemonAndroidLibs : DefaultTask() {
  @get:InputFiles abstract val classpathDescriptor: ConfigurableFileCollection

  @get:OutputDirectory abstract val destinationDir: DirectoryProperty

  @TaskAction
  fun stage() {
    val descriptor = classpathDescriptor.singleFile
    val dest = destinationDir.get().asFile
    dest.deleteRecursively()
    dest.mkdirs()
    descriptor
      .readLines()
      .map { it.trim() }
      .filter { it.isNotEmpty() }
      .map { File(it) }
      .filter { it.isFile && it.name.endsWith(".jar") && it.name != "android.jar" }
      .forEachIndexed { index, jar -> jar.copyTo(File(dest, "%04d-%s".format(index, jar.name))) }
  }
}

val stageDaemonAndroidLibs =
  tasks.register<StageDaemonAndroidLibs>("stageDaemonAndroidLibs") {
    description =
      "Stages :daemon:android runtime jars (from its classpath descriptor) into lib-daemon-android."
    classpathDescriptor.from(composePreviewDaemonAndroid)
    destinationDir.set(layout.buildDirectory.dir("staged-daemon-android-libs"))
  }

// Stage the BTA impl + Compose-plugin jars into `lib-bta/`, disambiguating any colliding filenames
// by Maven `module-version.jar` (same reason as the desktop daemon: multiple artifacts can share a
// basename). The playground compile loads this whole directory into BTA's isolated classloader.
val stageBtaLibs =
  tasks.register<Sync>("stageBtaLibs") {
    description = "Stages the BTA impl + Compose compiler plugin jars for serve --playground."
    destinationDir = layout.buildDirectory.dir("staged-bta-libs").get().asFile
    val artifactsProvider = composePreviewBta.incoming.artifacts.resolvedArtifacts
    from(artifactsProvider.map { it.map(ResolvedArtifactResult::getFile) })
    val nameByPath = artifactsProvider.map { resolved ->
      val counts = resolved.groupingBy { it.file.name }.eachCount()
      resolved.associate { artifact ->
        val original = artifact.file.name
        val mapped =
          if (counts.getValue(original) > 1) {
            val id = artifact.id.componentIdentifier
            if (id is ModuleComponentIdentifier) "${id.module}-${id.version}.jar" else original
          } else original
        artifact.file.absolutePath to mapped
      }
    }
    inputs.property("nameByPath", nameByPath)
    eachFile {
      val mapped = nameByPath.get()[file.absolutePath]
      if (mapped != null) name = mapped
    }
  }

// The CMP/Wasm Remote Compose player bundle, staged into the install dist as `rc-player-wasm/`.
//
// This used to be `files(project(":rc-player-wasm")...).builtBy(...)` — a directory produced by a
// sibling module. The players are published by yschimke/rc-players now, so the bundle arrives as a
// zip (`rc-player-wasm-dist`, `dist` classifier) and is unpacked here. `zipTree` inside a
// `provider`
// keeps resolution lazy, so a build that never assembles the distribution never downloads it.
//
// Resolved through its own configuration rather than a plain `dependencies {}` entry for the same
// reason as the sidecars above: this is not the CLI's own classpath, and nothing here should reach
// the compile or runtime graph.
val composePreviewRcPlayerWasm =
  configurations.create("composePreviewRcPlayerWasm") {
    isCanBeResolved = true
    isCanBeConsumed = false
    isTransitive = false
  }

dependencies { add("composePreviewRcPlayerWasm", libs.rcplayer.wasm.dist.map { "$it:dist@zip" }) }

val rcPlayerWasmDist = provider { zipTree(composePreviewRcPlayerWasm.singleFile) }

val previewUiWasmDist =
  files(project(":cli:serve-wasm").layout.buildDirectory.dir("wasmDist"))
    .builtBy(":cli:serve-wasm:wasmFrontendDist")

distributions {
  named("main") {
    contents {
      into("lib-renderer") { from(stageRendererLibs) }
      into("lib-daemon-desktop") { from(stageDaemonDesktopLibs) }
      into("lib-rcjvm") { from(stageRcJvmLibs) }
      into("lib-bta") { from(stageBtaLibs) }
      into("lib-usage-psi") { from(composePreviewUsagePsi) }
      // Static browser sidecar: release-matched CMP/Skiko Remote Compose player assets.
      into("rc-player-wasm") { from(rcPlayerWasmDist) }
      // The experimental Compose/Wasm preview browser is a release-matched static sidecar too.
      // Shipping it here lets every CLI/image expose it without a source checkout or local build.
      into("preview-ui") { from(previewUiWasmDist) }
    }
  }
}

abstract class CheckCliSkikoNativePackaging : DefaultTask() {
  @get:InputFiles
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val stagedJars: ConfigurableFileCollection

  @TaskAction
  fun checkPackaging() {
    val jars = stagedJars.files.flatMap { root -> root.listFiles()?.toList().orEmpty() }
    val nativeJars = jars.filter { it.name.startsWith("skiko-awt-runtime-") }
    check(nativeJars.isEmpty()) {
      "Portable CLI contains host-specific Skiko natives: ${nativeJars.joinToString { it.name }}"
    }
    check(jars.any { it.name.matches(Regex("skiko-awt-[^-].*\\.jar")) }) {
      "Portable CLI lost the skiko-awt API jar needed to derive the native version"
    }
  }
}

val checkCliSkikoNativePackaging =
  tasks.register<CheckCliSkikoNativePackaging>("checkCliSkikoNativePackaging") {
    description = "Checks that the portable CLI stages no host-specific Skiko native jars."
    group = "verification"
    dependsOn(stageRendererLibs, stageDaemonDesktopLibs, stageRcJvmLibs)
    stagedJars.from(stageRendererLibs, stageDaemonDesktopLibs, stageRcJvmLibs)
  }

tasks.named("check") { dependsOn(checkCliSkikoNativePackaging) }

// The Android (Robolectric) daemon runtime is ~150-200 MB (Robolectric + the full Compose-Android /
// AndroidX / Wear-Tiles / Remote-Compose stack). Bundling it into the main CLI tarball ballooned it
// to ~382 MB, so it ships as a SEPARATE archive (`compose-preview-android-daemon-<version>.zip`)
// that `compose-preview bundle daemon` fetches on demand and caches the first time it renders an
// `backend="android"` bundle. A standalone `Zip` (NOT a second `distributions {}` entry) so the
// distribution plugin doesn't wire it into `assemble` — that would drag `:daemon:android` (and its
// Android SDK requirement) back into a plain `:cli:build`. Built explicitly by the release job.
tasks.register<Zip>("packageAndroidDaemon") {
  description =
    "Packages the Android daemon runtime as a standalone archive for on-demand download."
  archiveFileName.set("compose-preview-android-daemon-${project.version}.zip")
  destinationDirectory.set(layout.buildDirectory.dir("distributions"))
  into("lib-daemon-android") { from(stageDaemonAndroidLibs) }
}

tasks.withType<Test>().configureEach {
  useJUnitPlatform()

  // The BTA jars, handed to `PsiParseSpikeTest` so its isolated-classloader check runs in an
  // ordinary `:cli:test`. It used to look for the *installed* `lib-bta/`, which `test` does not
  // stage — so on a clean checkout the one test of the proposed deployment route skipped silently,
  // and a broken reflective signature would have passed CI. Same artifacts the install stages,
  // taken straight from the configuration.
  //
  // Through a `CommandLineArgumentProvider` (resolved at execution time, declared as an input) so
  // the configuration cache stays valid rather than resolving a configuration at configuration
  // time.
  val btaJars = composePreviewBta.incoming.files
  inputs.files(btaJars).withPropertyName("libBtaJars").withNormalizer(ClasspathNormalizer::class)
  // And `:usage-source-psi` beside them, so `PlaygroundSourceCleaner` takes its **parsed** path
  // under test instead of silently falling back to the text passes — which would leave the
  // parser-backed rewrite, the whole point of the change, with no coverage at all.
  val usagePsiJars = composePreviewUsagePsi.incoming.files
  inputs
    .files(usagePsiJars)
    .withPropertyName("libUsagePsiJars")
    .withNormalizer(ClasspathNormalizer::class)
  // The shared wire fixtures under `scripts/design-artifacts/fixtures/`, which
  // `ServeIssueReportTest` and `ServeParityIssuesStoreTest` read straight off disk rather than
  // through the test classpath. Undeclared, Gradle cannot know that editing one changes what those
  // tests assert — so the exact change they exist to catch, an edit to a wire contract shared with
  // the JavaScript producer, could be served UP-TO-DATE or from the build cache without the
  // assertions ever running. That would leave the fixture looking like enforcement while enforcing
  // nothing.
  inputs
    .files(
      layout.projectDirectory.dir("../scripts/design-artifacts/fixtures").asFileTree.matching {
        include("*.json")
      }
    )
    .withPropertyName("sharedWireFixtures")
    .withPathSensitivity(PathSensitivity.RELATIVE)
  jvmArgumentProviders.add(
    CommandLineArgumentProvider {
      listOf(
        "-Dcomposeai.libBtaJars=" + btaJars.joinToString(File.pathSeparator) { it.absolutePath },
        "-Dcomposeai.usagePsi.jars=" +
          (usagePsiJars + btaJars).joinToString(File.pathSeparator) { it.absolutePath },
      )
    }
  )
}

abstract class CheckCliDaemonLibraryBoundary : DefaultTask() {
  @get:Classpath abstract val runtimeClasspath: ConfigurableFileCollection

  @get:Input abstract val forbiddenProjectDirs: ListProperty<String>

  @TaskAction
  fun checkBoundary() {
    val forbiddenDirs = forbiddenProjectDirs.get()
    val forbidden =
      runtimeClasspath.files
        .filter { file ->
          val path = file.invariantSeparatorsPath
          forbiddenDirs.any { forbiddenDir -> path.startsWith("$forbiddenDir/") }
        }
        .map { it.path }
        .sorted()

    check(forbidden.isEmpty()) {
      "CLI may depend on renderer-agnostic :daemon:core only; forbidden renderer artifacts on " +
        "runtimeClasspath: ${forbidden.joinToString(", ")}"
    }
  }
}

tasks.register<CheckCliDaemonLibraryBoundary>("checkCliDaemonLibraryBoundary") {
  description = "Fails if renderer implementations leak onto the CLI runtime classpath."
  group = "verification"

  runtimeClasspath.from(configurations.named("runtimeClasspath"))
  forbiddenProjectDirs.set(
    listOf(":daemon:android", ":daemon:desktop", ":renderer-android", ":renderer-desktop").map {
      project(it).projectDir.invariantSeparatorsPath
    }
  )
}

tasks.named("check") { dependsOn("checkCliDaemonLibraryBoundary") }

// The `cli` -> `serve` seam ratchet and the server's module-boundary check both retired with the
// extraction (#4732). They existed to keep the coupling measurable while the server was still a
// module of this build; a published artifact enforces the same thing structurally and in the
// stronger direction, because nothing in `cli/serve` can reach back into `:cli` from Maven Central.
// The surviving question that note used to end on — that most of the crossing symbols were
// render-host and history plumbing rather than server code — has since been answered: the server
// split them out in 2.2.0 and they now live in this build as `:render-host` (#5137), which `:cli`
// depends on as a project. Four symbols still cross into the server proper, all of them from
// `ServeCommand.kt`; see the dependency block above for what that still costs and what deciding
// it would take.

// This repository's representations of `daemon-launch.json`, checked against each other.
//
// The descriptor is written by the gradle plugin and read by the daemon JVM, this CLI's `doctor`
// and the subprocess writer. Two copies carry a comment asking a human to keep them in sync —
// `SubprocessRenderSession.kt`'s "mirrors the gradle plugin's writer" and `McpCommand.kt`'s
// "Keep in sync — bump together". This is that comment, enforced.
//
// It lives on `:cli` because the check spans modules that sit in different builds (the writer is
// inside the `gradle-plugin` composite and the JVM reader is in the pinned contracts checkout), so
// no single owning module exists. `:cli` runs on every PR and holds one of the sites itself.
abstract class CheckDaemonLaunchSchema : DefaultTask() {
  /**
   * Every Kotlin source in the repo, not just the registered representations.
   *
   * The checker's strongest rule is repo-wide: it fails on a schema-version constant, or a
   * descriptor construction stamping one, that is not registered. That rule reads files nobody
   * listed — which is the point. Declaring only the representations let Gradle mark the task
   * up-to-date after a mirror was added in an eighth file, so locally the one check that finds new
   * mirrors never ran on the change that introduced one. The exclusions mirror `PRUNE` in the
   * checker; if one list grows, so must the other.
   */
  @get:InputFiles
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val representations: ConfigurableFileCollection

  @get:InputFile
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val allowlist: RegularFileProperty

  @get:InputFile
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val checker: RegularFileProperty

  /** Root of a compose-preview-contracts checkout, when one is present. */
  @get:Input @get:Optional abstract val contractsRoot: Property<String>

  /** Nothing to produce — the file just lets Gradle skip the check when nothing moved. */
  @get:OutputFile abstract val stamp: RegularFileProperty

  @get:Inject abstract val execOps: ExecOperations

  @TaskAction
  fun checkSchema() {
    execOps.exec {
      commandLine("python3", checker.get().asFile.absolutePath)
      // Passed explicitly rather than inherited, so what the checker sees is what Gradle
      // fingerprinted above. An inherited value could differ from the declared input.
      contractsRoot.orNull?.let { environment("COMPOSE_PREVIEW_CONTRACTS_ROOT", it) }
    }
    stamp.get().asFile.writeText("ok\n")
  }
}

tasks.register<CheckDaemonLaunchSchema>("checkDaemonLaunchSchema") {
  description = "Fails if the daemon-launch.json writer and its readers disagree."
  group = "verification"

  val repoRoot = rootProject.layout.projectDirectory
  representations.from(
    rootProject.fileTree(repoRoot) {
      include("**/*.kt")
      exclude(
        "**/build/**",
        "**/node_modules/**",
        "**/.git/**",
        "**/.gradle/**",
        "**/out/**",
        "**/dist/**",
        "scripts/**",
      )
    }
  )
  // The JVM reader moved to yschimke/compose-preview-contracts with the wire contracts, so it is
  // declared as an input. Without it the first successful run stamps this task UP-TO-DATE and every
  // later edit to the reader is invisible, which is the drift this gate exists to catch.
  //
  // Eagerly to a String, for the configuration-cache reason given above.
  val contractsRootPath: String? =
    providers.environmentVariable("COMPOSE_PREVIEW_CONTRACTS_ROOT").orNull?.takeIf {
      it.isNotBlank()
    }
      ?: repoRoot.asFile.parentFile
        ?.resolve("compose-preview-contracts")
        ?.takeIf {
          it
            .resolve(
              "daemon/protocol/src/main/kotlin/ee/schimke/composeai/daemon/protocol/DaemonLaunchDescriptor.kt"
            )
            .isFile
        }
        ?.absolutePath
  if (contractsRootPath != null) {
    contractsRoot.set(contractsRootPath)
    representations.from(
      rootProject.fileTree(contractsRootPath) {
        include("**/*.kt")
        exclude("**/build/**", "**/.git/**")
      }
    )
  }

  allowlist.set(repoRoot.file("scripts/daemon-launch-schema-allowlist.json"))
  checker.set(repoRoot.file("scripts/check-daemon-launch-schema.py"))
  stamp.set(layout.buildDirectory.file("check-daemon-launch-schema/ok.txt"))
}

tasks.named("check") { dependsOn("checkDaemonLaunchSchema") }

// Bake the resolved Gradle build version into a properties resource the CLI reads at runtime
// (see `Version.kt#BUNDLE_VERSION`). Avoids the previous hand-edited literal in source — which
// drifted out of sync with the release manifest and made `compose-preview show` advertise a
// nonexistent v0.9.0 release. Mirrors `gradle-plugin/build.gradle.kts`'s
// `generatePluginVersionResource`.
val generateCliVersionResource =
  tasks.register("generateCliVersionResource") {
    val outputDir = layout.buildDirectory.dir("generated/cli-version-resource")
    val cliVersion = project.version.toString()
    // The `xr-composite` release the provisioner fetches from — a catalog PIN that moves only
    // when the native compositor changes, NOT this CLI's version. Baked here (rather than read
    // from the catalog at runtime, which the installed CLI has no access to) so the writer of the
    // shared cache and the plugin-side reader resolve the same directory; the plugin bakes the
    // same value through `generatePluginVersionResource`. See `XrCompositeProvision`.
    val xrCompositeVersion = libs.versions.xr.composite.get()
    // The preview-server release `serve`/`browse` launch, and which `ServerDistributionProvision`
    // fetches on first use. The catalog pin, NOT this CLI's version: the server releases on its own
    // cadence from its own repository, and an installed CLI cannot read the catalog. See
    // `SERVE_VERSION`.
    val serveVersion = libs.versions.composeai.preview.serve.get()
    inputs.property("version", cliVersion)
    inputs.property("xrCompositeVersion", xrCompositeVersion)
    inputs.property("serveVersion", serveVersion)
    outputs.dir(outputDir)
    doLast {
      val file = outputDir.get().file("ee/schimke/composeai/cli/cli-version.properties").asFile
      file.parentFile.mkdirs()
      file.writeText(
        "version=$cliVersion\nxrCompositeVersion=$xrCompositeVersion\nserveVersion=$serveVersion\n"
      )
    }
  }

sourceSets.main.get().resources.srcDir(generateCliVersionResource)
