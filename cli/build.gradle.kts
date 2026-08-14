import java.io.File
import org.gradle.api.DefaultTask
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedArtifactResult
import org.gradle.api.attributes.Attribute
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.TaskAction

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
  // BTA *interfaces only* — the CLI references `BtaCompileSession`'s build-tools-api parameter
  // types
  // (`CompilerPlugin`, `KotlinLogger`, `SourcesChanges`) to drive an in-process playground compile.
  // `:daemon:core` declares this as `implementation`, so it isn't transitive; the impl JARs ride in
  // `lib-bta/`, not here.
  implementation("org.jetbrains.kotlin:kotlin-build-tools-api:${libs.versions.kotlin.get()}")

  // Published wire-format DTOs (`PreviewResult`, `PreviewManifest`, the v1 a11y mirror types,
  // `ExtensionPayload`). `api` so the existing in-package imports across this module (and the
  // CLI tests) keep resolving without an explicit `import` change — same source-compat pattern
  // `:data-a11y-core` used for the D2.2 extraction. External consumers (contrib scripting,
  // third-party tooling) pull `:preview-data-api` directly, not transitively through `:cli`.
  api(project(":preview-data-api"))

  // Gradle Tooling-API render pipeline + the `GradleConnection` / `PreviewModule` /
  // `CapturedTestFailure` / `TerminalProgress` plumbing the CLI used to host inline. `api` again
  // for source-compat (existing in-package imports). Transitively brings in the Tooling-API and
  // slf4j-nop dependencies the CLI used to declare directly.
  api(project(":gradle-preview-driver"))

  // Okio-based file IO (`SystemFileSystem` + suspend helpers) the CLI commands read/write through.
  implementation(project(":common-io"))

  // mDNS/DNS-SD advertiser for `serve --lan` — publishes `_composeai._tcp` so the mobile/wear
  // session-viewer clients (`:clients:*`) discover the server on the LAN without a typed URL.
  implementation(libs.jmdns)

  implementation(libs.kotlinx.serialization.json)

  // Semantics text-diff engine + payload model for the `diff-semantics` command (issue #1785).
  implementation(project(":data-layoutinspector-core"))

  // `fonts/used` sidecar file name for `bundle pack --with-semantics` font carriage.
  implementation(project(":data-fonts-core"))

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
  implementation(libs.ktor.server.core)
  implementation(libs.ktor.server.cio)
  // WebSockets plugin: the `serve` streamed-frame lane (`/ws/{id}`) — tier-2 streaming spike.
  implementation(libs.ktor.server.websockets)
  implementation(libs.ktor.server.compression)
  // HEAD answers GET across the whole site — the probe link unfurlers send before downloading.
  implementation(libs.ktor.server.auto.head.response)

  // Bundle the MCP server so `compose-preview mcp serve` can invoke it in-process —
  // the consumer install story stays a single tarball + a single launcher.
  implementation(project(":mcp"))
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
  implementation(project(":data-preview-overrides-core"))
  // Wire-shape of the `compose/remotecompose` data product (`RemoteComposeKnobDeclaration` /
  // `RemoteComposeDeclarationsPayload`) — the Remote Compose named-value knobs `serve` reads from a
  // bundle's `previews/<id>.remotecompose.json` sidecar to advertise editable controls. Pure JVM
  // (payload schema only; the alpha `androidx.compose.remote.*` deps live in the connector, not
  // here), so it stays off the renderer/daemon boundary the CLI guards.
  implementation(project(":data-remotecompose-core"))
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
  add("composePreviewRcJvm", project(":third-party-rc-embedded-player-jvm"))

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
val stageDaemonDesktopLibs =
  tasks.register<Sync>("stageDaemonDesktopLibs") {
    description = "Stages :daemon:desktop runtime artifacts, renaming filename collisions."
    destinationDir = layout.buildDirectory.dir("staged-daemon-desktop-libs").get().asFile
    val artifactsProvider = composePreviewDaemonDesktop.incoming.artifacts.resolvedArtifacts
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

// Stage the JVM embedded player's runtime artifacts for `lib-rcjvm/`, disambiguating any colliding
// `library-desktop-<version>.jar` filenames by Maven `module-version.jar` — same reasoning as
// [stageDaemonDesktopLibs].
val stageRcJvmLibs =
  tasks.register<Sync>("stageRcJvmLibs") {
    description = "Stages :third-party-rc-embedded-player-jvm runtime artifacts for lib-rcjvm/."
    destinationDir = layout.buildDirectory.dir("staged-rcjvm-libs").get().asFile
    val artifactsProvider = composePreviewRcJvm.incoming.artifacts.resolvedArtifacts
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

val rcPlayerWasmDist =
  files(project(":rc-player-wasm").layout.buildDirectory.dir("wasmDist"))
    .builtBy(":rc-player-wasm:wasmPlayerDist")

distributions {
  named("main") {
    contents {
      into("lib-renderer") { from(composePreviewRenderer) }
      into("lib-daemon-desktop") { from(stageDaemonDesktopLibs) }
      into("lib-rcjvm") { from(stageRcJvmLibs) }
      into("lib-bta") { from(stageBtaLibs) }
      // Static browser sidecar: release-matched CMP/Skiko Remote Compose player assets.
      into("rc-player-wasm") { from(rcPlayerWasmDist) }
    }
  }
}

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
  // Catalog checkouts for the usage-snippet corpus (UsageSnippetCorpusTest). Absent by default, so
  // the corpus is a no-op in a normal build; `scripts/usage-corpus.sh` supplies them. Forwarded
  // rather than read from the environment so the paths show up in the build's own inputs.
  for (key in
    listOf(
      "composeai.usageCorpus.m3-catalog",
      "composeai.usageCorpus.meshcore-mobile",
      "composeai.usageCorpus.out",
      "composeai.usageCorpus.samples",
    )) {
    providers.systemProperty(key).orNull?.let { systemProperty(key, it) }
  }
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

// Bake the resolved Gradle build version into a properties resource the CLI reads at runtime
// (see `Version.kt#BUNDLE_VERSION`). Avoids the previous hand-edited literal in source — which
// drifted out of sync with the release manifest and made `compose-preview show` advertise a
// nonexistent v0.9.0 release. Mirrors `gradle-plugin/build.gradle.kts`'s
// `generatePluginVersionResource`.
val generateCliVersionResource by tasks.registering {
  val outputDir = layout.buildDirectory.dir("generated/cli-version-resource")
  val cliVersion = project.version.toString()
  inputs.property("version", cliVersion)
  outputs.dir(outputDir)
  doLast {
    val file = outputDir.get().file("ee/schimke/composeai/cli/cli-version.properties").asFile
    file.parentFile.mkdirs()
    file.writeText("version=$cliVersion\n")
  }
}

sourceSets.main.get().resources.srcDir(generateCliVersionResource)

// The typefaces the served viewer registers for its client-side Remote Compose lanes
// (`ServeRcFonts`): without them the browser lane paints a document's generic families in whatever
// the *viewer's* machine calls `sans-serif`, at different metrics and without the Medium weight,
// while the baked PNG beside it used these files (issue #3480).
//
// STAGED, not committed a second time. The source is the one vendored directory the offline parity
// harness reads (`scripts/design-artifacts/rc-fonts.mjs`'s `DEFAULT_FONTS_DIR`) and the snapshot
// renderer rasterizes with, so "the viewer's faces" and "the faces parity is measured against"
// cannot become different files. The named-family faces in that directory (Orbitron, Lobster Two)
// are deliberately left out — the player fetches those itself through `WebFonts.ts`; only the four
// behind the generic families need registering, and they are what `ServeRcFonts.FACES` declares
// (`ServeRcFontsTest` fails when this list and that table disagree).
val stageRcFontResources by
  tasks.registering(Sync::class) {
    description =
      "Stage the vendored generic-family faces the serve viewer registers for its RC lanes."
    from(rootDir.resolve("samples/cmp-wasm-catalog/src/wasmJsMain/resources/fonts")) {
      include(
        "Roboto-Regular.ttf",
        "Roboto-Medium.ttf",
        "NotoSerif-Regular.ttf",
        "DroidSansMono.ttf",
        // The faces' own licence, so the jar carries it beside the bytes.
        "LICENSE.txt",
      )
      into("rc-fonts")
    }
    into(layout.buildDirectory.dir("generated/rc-font-resources"))
  }

sourceSets.main.get().resources.srcDir(stageRcFontResources)
