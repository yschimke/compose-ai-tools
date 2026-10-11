import java.io.File
import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedArtifactResult
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.ClasspathNormalizer
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
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
  // The Tooling API's native-platform jar calls `System.load`; declaring native access silences the
  // JDK 24+ restricted-method warning on every invocation.
  applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED")
}

// Don't set `archiveFileName`: the distribution plugin derives the archive's root directory from
// it, which would leak `.tar.gz` into the extracted folder name.
tasks.named<Tar>("distTar") {
  archiveExtension.set("tar.gz")
  compression = Compression.GZIP
}

// The desktop renderer and the desktop/Android daemons are not staged here: they come from
// yschimke/compose-preview-daemon releases, fetched on first use by `DaemonSidecarProvision` at the
// `composeai-preview-daemon` catalog pin (baked in below as `previewDaemonVersion`).

// The CMP Remote Compose render worker (`:rc-render-jvm`), which `serve` spawns to render
// `ir/<id>.rc` for the cmp-jvm chip. Excludes Compose/Skiko: the subprocess classpath joins
// `lib-rcjvm/*` + `lib-daemon-desktop/*`. Staged to `lib-rcjvm/` (or
// `-Dcomposeai.cli.libRcjvmDir`).
val composePreviewRcJvm =
  configurations.create("composePreviewRcJvm") {
    isCanBeResolved = true
    isCanBeConsumed = false
  }

// Kotlin Build Tools API implementation plus the Compose compiler plugin, for the `serve
// --playground` in-process compile. Loaded into BTA's isolated classloader, never onto the CLI's
// own classpath. Staged to `lib-bta/` (or `-Dcomposeai.cli.libBtaDir`).
val composePreviewBta =
  configurations.create("composePreviewBta") {
    isCanBeResolved = true
    isCanBeConsumed = false
  }

// `:usage-source-psi`, the Kotlin parser behind the usage cleaner, loaded alongside `lib-bta/` and
// likewise kept off the CLI's classpath. Staged to `lib-usage-psi/` (or
// `-Dcomposeai.cli.libUsagePsiDir`). Only this module's jar: the frontend is `compileOnly` and
// rides in `lib-bta/`.
val composePreviewUsagePsi =
  configurations.create("composePreviewUsagePsi") {
    isCanBeResolved = true
    isCanBeConsumed = false
  }

dependencies {
  implementation(platform(libs.rcplayers.bom))
  // BTA implementation + Compose compiler plugin for `lib-bta/`; the frontend comes transitively.
  add(
    "composePreviewBta",
    "org.jetbrains.kotlin:kotlin-build-tools-impl:${libs.versions.kotlin.get()}",
  )
  add(
    "composePreviewBta",
    "org.jetbrains.kotlin:kotlin-compose-compiler-plugin-embeddable:${libs.versions.kotlin.get()}",
  )
  add("composePreviewUsagePsi", project(":usage-source-psi"))
  // BTA interfaces only, for `BtaCompileSession`'s parameter types (not transitive from
  // `:daemon:core`); the implementation jars ride in `lib-bta/`.
  implementation("org.jetbrains.kotlin:kotlin-build-tools-api:${libs.versions.kotlin.get()}")

  // SPIKE, test-only: the Kotlin frontend for `PsiParseSpikeTest`. Must stay off the runtime
  // classpath; a real change would load it through the isolated `lib-bta/` classloader.
  testImplementation(
    "org.jetbrains.kotlin:kotlin-compiler-embeddable:${libs.versions.kotlin.get()}"
  )

  // Published wire-format DTOs. `api` so in-package imports here and in tests keep resolving;
  // external consumers depend on `:preview-data-api` directly.
  api(libs.composeai.preview.data.api)

  // The wire contract `compose-preview build-host` serves; `api` because `BuildHostCommand`'s seam
  // uses its types. Shape only, so the preview server can depend on it without the Tooling API.
  api(project(":build-host-protocol"))
  implementation(project(":common-image-crop"))

  // Gradle Tooling-API render pipeline and `GradleConnection` plumbing. `api` for in-package source
  // compatibility.
  api(project(":gradle-preview-driver"))

  // The preview-bundle format (reader/writer, manifest, sidecar injectors, signing, hydration).
  // `api` because the types keep the `ee.schimke.composeai.cli` package.
  api(project(":bundle-format"))
  api(libs.composeai.agent.grant.protocol)

  // Turns a bundle's recorded Maven coordinates back into local jars. `api` for in-package source
  // compatibility.
  api(project(":bundle-coordinates"))

  // The render host, bundle daemon and git-backed preview history used by the offline commands
  // (`bundle render`, `history manifest`, `render matrix`). A project dependency; `api` because
  // call sites reference these types in-package (`ee.schimke.composeai.cli.serve`).
  api(project(":render-host"))

  // The preview server is not on any classpath of this module: `serve` and `browse` exec the
  // published `compose-preview-server` binary. Tests that check wire compatibility launch that
  // distribution via `ServeDistributionHarness`.

  // Okio-based file IO (`SystemFileSystem` + suspend helpers) the CLI commands read/write through.
  implementation(libs.composeai.common.io)

  implementation(libs.kotlinx.serialization.json)

  // Semantics text-diff engine + payload model for the `diff-semantics` command (issue #1785).
  implementation(libs.composeai.data.layoutinspector.core)

  // Material 3 resolved tokens + node-consumer attribution, joined to semantics for the live
  // Typography inspection layer.
  implementation(libs.composeai.data.theme.core)

  // `fonts/used` sidecar file name for `bundle pack --with-semantics` font carriage.
  implementation(libs.composeai.data.fonts.core)

  // The renderer's own locale-direction rule, so `serve` resolves a published capture gutter's
  // leading/trailing edges onto left/right exactly as the render that produced the pixels did
  // (pseudolocale first, then the real language table) rather than keeping a second copy of it.
  implementation(libs.composeai.data.pseudolocale.core)

  // Ktor client (OkHttp engine) for downloading a bundle when the open arg is a URL. The explicit
  // okhttp dep pins the engine to OkHttp 5.x — ktor-client-okhttp 3.0.3 only declares a transitive
  // 4.12.0, so without this the catalog's okhttp 5 version is never selected.
  implementation(libs.ktor.client.core)
  implementation(libs.ktor.client.okhttp)
  implementation(libs.okhttp)

  // Axis expansion and contact-sheet stitching for the offline `render-matrix` command, shared with
  // the MCP `render_matrix` tool so they agree.
  implementation(project(":render-matrix"))
  // `compose-preview guidelines`: the batched design-guidelines check over rendered previews.
  implementation(project(":design-guidelines"))

  // The Remote Compose JSON codec behind `compose-preview rc`. Not a `RemoteComposePairing`
  // concern: the CLI only compiles and inflates documents in-process and never builds a daemon
  // classpath.
  implementation(project(":remotecompose-json"))

  // The MCP server isn't on this classpath either: `mcp serve` execs the `compose-preview-mcp`
  // binary from compose-preview-server. `mcp install` / `mcp doctor` stay here as offline
  // behaviour. Used directly by `DaemonSmokeCheck` (spawn port + subprocess factory).
  implementation(libs.composeai.daemon.client)
  // Renderer-agnostic daemon core helpers that are safe to use as a local library from CLI
  // commands. Keep renderer backends (`:daemon:android`, `:daemon:desktop`) out of this module.
  implementation(libs.composeai.daemon.core)
  // ClassGraph for the `serve --playground` scoped `@Preview` scan of a just-compiled snippet.
  implementation(libs.classgraph)
  // `compose/overrides` wire shape (`PreviewOverrideDeclaration`) for editable knobs. Pure JVM.
  implementation(libs.composeai.data.preview.overrides.core)
  // `compose/remotecompose` wire shape for Remote Compose knobs. Pure JVM (schema only; the
  // `androidx.compose.remote.*` deps live in the connector), so it stays off the renderer boundary.
  implementation(libs.composeai.data.remotecompose.core)
  // `PreviewBackdrop` / `PreviewBackground`, shared with renderers and daemons so served pages
  // match the pixels. Pure JVM ARGB math.
  implementation(libs.composeai.data.render.core)
  // Daemon-driven commands use the public render-session API rather than DaemonClient, so
  // third-party tooling can do anything the CLI can.
  implementation(project(":render-session-api"))
  implementation(project(":render-session-subprocess"))

  // The CMP Remote Compose render worker for `lib-rcjvm/`. Skiko natives aren't bundled; the host's
  // is fetched at run time by `SkikoNativeProvision`.
  add("composePreviewRcJvm", project(":rc-render-jvm"))

  // The Tooling API strictly requires `slf4j-api:2.0.17` while Ktor pulls 2.0.18, which fails
  // `runtimeClasspath` resolution. Pin 2.0.17; the two are binary-compatible.
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
  // In-memory FileSystem for tests; okio itself comes transitively via `common:io`.
  testImplementation(libs.okio.fakefilesystem)

  // `FakeRenderSession` for `BundleRenderKnobTest`, from `:render-host`'s test fixtures (a project
  // dependency, so no capability matching is needed).
  testImplementation(testFixtures(project(":render-host")))
  // TestKit for [InitScriptExclusiveContentReproducerTest]: asserts the init script doesn't trip
  // Gradle 9.3+'s `exclusiveContent`-vs-`buildscript.repositories` validation.
  testImplementation(gradleTestKit())
}

// Stage the render worker's runtime for `lib-rcjvm/`, renaming colliding `library-desktop-<v>.jar`
// files to `module-version.jar`. Host Skiko natives are filtered out so the archive stays portable.
val stageRcJvmLibs =
  tasks.register<Sync>("stageRcJvmLibs") {
    description = "Stages the CMP render worker's runtime artifacts for lib-rcjvm/."
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

// Stage BTA + Compose-plugin jars into `lib-bta/`, disambiguating colliding filenames the same way.
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

// The CMP/Wasm Remote Compose player, published by yschimke/rc-players as a `dist` zip and unpacked
// into `rc-player-wasm/`. Lazy, and in its own configuration so it never reaches the CLI classpath.
val composePreviewRcPlayerWasm =
  configurations.create("composePreviewRcPlayerWasm") {
    isCanBeResolved = true
    isCanBeConsumed = false
  }

dependencies {
  add("composePreviewRcPlayerWasm", platform(libs.rcplayers.bom))
  add(
    "composePreviewRcPlayerWasm",
    libs.rcplayer.wasm.dist.map {
      mapOf(
        "group" to it.module.group,
        "name" to it.module.name,
        "classifier" to "dist",
        "ext" to "zip",
      )
    },
  )
}

val rcPlayerWasmDist = provider { zipTree(composePreviewRcPlayerWasm.singleFile) }

val previewUiWasmDist =
  files(project(":cli:serve-wasm").layout.buildDirectory.dir("wasmDist"))
    .builtBy(":cli:serve-wasm:wasmFrontendDist")

distributions {
  named("main") {
    contents {
      into("lib-rcjvm") { from(stageRcJvmLibs) }
      into("lib-bta") { from(stageBtaLibs) }
      into("lib-usage-psi") { from(composePreviewUsagePsi) }
      // Static browser sidecar: release-matched CMP/Skiko Remote Compose player assets.
      into("rc-player-wasm") { from(rcPlayerWasmDist) }
      // The experimental Compose/Wasm preview browser, a release-matched static sidecar.
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
    // The `skiko-awt` jar the native version derives from is in the provisioned
    // `lib-daemon-desktop/`; compose-preview-daemon's `checkSkikoNativePackaging` holds the
    // matching invariant.
  }
}

val checkCliSkikoNativePackaging =
  tasks.register<CheckCliSkikoNativePackaging>("checkCliSkikoNativePackaging") {
    description = "Checks that the portable CLI stages no host-specific Skiko native jars."
    group = "verification"
    dependsOn(stageRcJvmLibs)
    stagedJars.from(stageRcJvmLibs)
  }

tasks.named("check") { dependsOn(checkCliSkikoNativePackaging) }

tasks.withType<Test>().configureEach {
  useJUnitPlatform()

  // Hand the BTA jars to `PsiParseSpikeTest` so it runs in plain `:cli:test` (which doesn't stage
  // `lib-bta/`). Via a `CommandLineArgumentProvider` to keep the configuration cache valid.
  val btaJars = composePreviewBta.incoming.files
  inputs.files(btaJars).withPropertyName("libBtaJars").withNormalizer(ClasspathNormalizer::class)
  // And `:usage-source-psi`, so `PlaygroundSourceCleaner`'s parsed path is covered.
  val usagePsiJars = composePreviewUsagePsi.incoming.files
  inputs
    .files(usagePsiJars)
    .withPropertyName("libUsagePsiJars")
    .withNormalizer(ClasspathNormalizer::class)
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
  /** `group:module` of every resolved module artifact on the CLI's runtime classpath. */
  @get:Input abstract val resolvedModules: ListProperty<String>

  @get:Input abstract val forbiddenModules: ListProperty<String>

  @TaskAction
  fun checkBoundary() {
    val forbidden = forbiddenModules.get().toSet()
    val leaked = resolvedModules.get().filter { it in forbidden }.sorted()

    check(leaked.isEmpty()) {
      "CLI may depend on the renderer-agnostic daemon-core only; forbidden renderer artifacts on " +
        "runtimeClasspath: ${leaked.joinToString(", ")}"
    }
  }
}

// Daemon/renderers are published coordinates, so check resolved module identities (as
// `checkLayerBoundary` does), which also catches transitive arrivals.
tasks.register<CheckCliDaemonLibraryBoundary>("checkCliDaemonLibraryBoundary") {
  description = "Fails if renderer implementations leak onto the CLI runtime classpath."
  group = "verification"

  resolvedModules.set(
    configurations.named("runtimeClasspath").flatMap { configuration ->
      configuration.incoming.artifacts.resolvedArtifacts.map { artifacts ->
        artifacts.mapNotNull { artifact ->
          (artifact.id.componentIdentifier as? ModuleComponentIdentifier)?.let {
            "${it.group}:${it.module}"
          }
        }
      }
    }
  )
  forbiddenModules.set(
    listOf("daemon-android", "daemon-desktop", "renderer-android", "renderer-desktop").map {
      "ee.schimke.composeai:$it"
    }
  )
}

tasks.named("check") { dependsOn("checkCliDaemonLibraryBoundary") }

// This repository's representations of `daemon-launch.json`, checked against each other — the "keep
// in sync" comments in `SubprocessRenderSession.kt` and `McpCommand.kt`, enforced. Lives on `:cli`
// because the sites span builds and `:cli` runs on every PR.
abstract class CheckDaemonLaunchSchema : DefaultTask() {
  /**
   * Every Kotlin source in the repo, since the checker fails on any unregistered schema-version
   * constant or descriptor construction; declaring only known files would let new mirrors go
   * unchecked. Exclusions mirror `PRUNE` in the checker.
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
      // Explicit rather than inherited, so the checker sees what Gradle fingerprinted.
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
  // The JVM reader lives in yschimke/compose-preview-contracts, so declare it as an input or edits
  // to it never re-run this task. Eager String for the configuration cache.
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

// Bake the build version into a resource for `Version.kt#BUNDLE_VERSION`. Mirrors the plugin's
// `generatePluginVersionResource`.
val generateCliVersionResource =
  tasks.register("generateCliVersionResource") {
    val outputDir = layout.buildDirectory.dir("generated/cli-version-resource")
    val cliVersion = project.version.toString()
    // The `xr-composite` release to fetch: a catalog pin, not this CLI's version. Baked because the
    // installed CLI can't read the catalog; the plugin bakes the same value. See
    // `XrCompositeProvision`.
    val xrCompositeVersion = libs.versions.xr.composite.get()
    // The version of this repository's Maven artifacts the CLI asks Gradle for (auto-injected
    // plugin, `doctor` recommendations). Separate from `cliVersion` because a release may not
    // publish to Central; CI sets MAVEN_LINE_VERSION to the last published version. `takeIf {
    // isNotBlank() }` guards against an empty Actions expression.
    val mavenLineVersion =
      project.providers.environmentVariable("MAVEN_LINE_VERSION").orNull?.takeIf { it.isNotBlank() }
        ?: cliVersion
    // The compose-preview-daemon release `DaemonSidecarProvision` fetches; a catalog pin, as above.
    val previewDaemonVersion = libs.versions.composeai.preview.daemon.get()
    inputs.property("version", cliVersion)
    inputs.property("xrCompositeVersion", xrCompositeVersion)
    inputs.property("mavenLineVersion", mavenLineVersion)
    inputs.property("previewDaemonVersion", previewDaemonVersion)
    outputs.dir(outputDir)
    doLast {
      val file = outputDir.get().file("ee/schimke/composeai/cli/cli-version.properties").asFile
      file.parentFile.mkdirs()
      file.writeText(
        "version=$cliVersion\n" +
          "xrCompositeVersion=$xrCompositeVersion\n" +
          "mavenLineVersion=$mavenLineVersion\n" +
          "previewDaemonVersion=$previewDaemonVersion\n"
      )
    }
  }

sourceSets.main.get().resources.srcDir(generateCliVersionResource)
