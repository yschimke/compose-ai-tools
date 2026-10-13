package ee.schimke.composeai.cli

import ee.schimke.composeai.bundle.locateBundleSidecarJars
import ee.schimke.composeai.daemonlaunch.DaemonLaunchBuilder
import ee.schimke.composeai.discovery.ComponentRecords
import ee.schimke.composeai.discovery.PreviewDiscovery
import ee.schimke.composeai.discovery.PreviewManifest
import ee.schimke.composeai.io.composeAiCacheDir
import ee.schimke.composeai.render.session.RenderSessionConfig
import ee.schimke.composeai.render.session.subprocess.SubprocessRenderSessions
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/** Explicit Bazel-family entry point; opening a repository never runs its build configuration. */
internal class BazelCommand(private val args: List<String>) {
  fun run() {
    if (args.isEmpty() || args.any { it in setOf("help", "--help", "-h") }) {
      printHelp()
      return
    }
    try {
      execute()
    } catch (e: Exception) {
      System.err.println("compose-preview bazel: ${e.message}")
      exitProcess(1)
    }
  }

  internal fun execute() {
    val unknown = CliFlagValidation.unknownFlags("bazel", args)
    require(unknown.isEmpty()) { "Unknown options: ${unknown.joinToString()}" }
    val operation = args.getOrNull(CliFlags.findCommandIndex(args.toTypedArray()))
    require(operation in setOf("init", "prepare", "list", "render")) {
      "Expected init, prepare, list or render"
    }
    val root = File(args.flagValue("--project") ?: ".").canonicalFile
    require(root.isDirectory) { "Project directory missing: $root" }
    val timeout =
      args.flagValue("--timeout")?.let {
        it.toLongOrNull() ?: error("--timeout must be a positive number of seconds")
      } ?: 300L
    require(timeout > 0) { "--timeout must be positive" }
    if (operation == "init") {
      installBazelAspect(root, args.flagValue("--java-info-load"))
      println("Installed //.compose-preview/bazel:runtime.bzl%compose_preview_aspect")
      return
    }
    val configFile =
      args.flagValue("--config")?.let { resolve(root, it) }
        ?: root.resolve(".compose-preview/bazel.json")
    require(args.flagValue("--config") == null || configFile.isFile) {
      "Config missing: $configFile"
    }
    var config =
      if (configFile.isFile) bazelJson.decodeFromString<BazelBuildConfig>(configFile.readText())
      else BazelBuildConfig()
    args.flagValue("--build-command")?.let { config = config.copy(buildCommand = listOf(it)) }
    args.flagValue("--aspect")?.let { config = config.copy(aspect = it) }
    config = config.copy(buildArgs = config.buildArgs + args.flagValuesAll("--bazel-arg"))
    val imported = args.flagValue("--artifacts")
    val artifacts =
      if (imported != null) {
        val executionRoot =
          args.flagValue("--execution-root")?.let { resolve(root, it) }
            ?: error("--artifacts requires --execution-root")
        val metadata =
          bazelJson.decodeFromString<BazelArtifacts>(resolve(root, imported).readText())
        args.flagValue("--target")?.let {
          require(metadata.target.removePrefix("@@") == it.removePrefix("@@")) {
            "Imported artifacts do not match --target"
          }
        }
        metadata.resolve(executionRoot)
      } else {
        val target = args.flagValue("--target") ?: error("--target is required")
        if (config.aspect == BazelBuildConfig().aspect) {
          check(root.resolve(".compose-preview/bazel/runtime.bzl").isFile) {
            "Run `compose-preview bazel init --project $root` once, or configure an existing aspect / use --artifacts."
          }
        }
        BazelBuild().collect(root, target, config, timeout)
      }
    if ("--verbose" in args || "-v" in args) {
      System.err.println(
        "[bazel] ${artifacts.metadata.target} (${artifacts.metadata.configuration}): " +
          "${artifacts.projectJars.size} application jars, ${artifacts.runtimeJars.size} runtime jars, " +
          "${artifacts.sourceFiles.size} sources, ${artifacts.dataFiles.size} runfiles"
      )
    }
    val targetKey =
      MessageDigest.getInstance("SHA-256")
        .digest((artifacts.metadata.target + "\n" + artifacts.metadata.configuration).toByteArray())
        .take(8)
        .joinToString("") { "%02x".format(it) }
    val output =
      args.flagValue("--out")?.let { resolve(root, it) }
        ?: root.resolve(".compose-preview/outputs/$targetKey")
    val prepared = prepareBazelPreviews(artifacts, root, output)
    when (operation) {
      "list" -> {
        if ("--json" in args) println(bazelJson.encodeToString(prepared.manifest))
        else
          prepared.manifest.previews.forEach {
            println("${it.id}\t${it.className}.${it.functionName}")
          }
      }
      "prepare",
      "render" -> {
        val descriptor = prepareBazelDesktopDaemon(artifacts, prepared, root)
        if (operation == "prepare") println(descriptor.absolutePath)
        else renderBazelPreviews(prepared, descriptor, root, timeout)
      }
    }
  }

  private fun renderBazelPreviews(
    prepared: PreparedBazelPreviews,
    descriptor: File,
    root: File,
    timeout: Long,
  ) {
    val exactId = args.flagValue("--id")
    val filter = args.flagValue("--filter")
    val previews =
      prepared.manifest.previews.filter {
        (exactId == null || it.id == exactId) &&
          (filter == null || it.id.contains(filter, ignoreCase = true))
      }
    require(previews.isNotEmpty()) { "No previews match the selection" }
    val output = args.flagValue("--output")?.let { resolve(root, it) }
    require(output == null || previews.size == 1) {
      "--output requires one preview; select it with --id or --filter"
    }
    val finished = ConcurrentHashMap<String, JsonObject>()
    val latch = CountDownLatch(previews.size)
    val ids = previews.map { it.id }.toSet()
    SubprocessRenderSessions.open(
        RenderSessionConfig(
          descriptorPath = descriptor,
          workspaceRoot = root,
          initializeTimeout = timeout.seconds,
          maxRenderTime = timeout.seconds,
        )
      )
      .use { session ->
        session
          .onNotification { method, params ->
            val id = params?.get("id")?.jsonPrimitive?.contentOrNull
            if (
              method in setOf("renderFinished", "renderFailed") &&
                id in ids &&
                params != null &&
                finished.putIfAbsent(id!!, params) == null
            )
              latch.countDown()
          }
          .use {
            val ack = session.renderNow(previews.map { it.id }, timeout = timeout.seconds)
            check(ack.rejected.isEmpty()) { "Renderer rejected previews: ${ack.rejected}" }
            check(latch.await(timeout, TimeUnit.SECONDS)) {
              "Rendering timed out after ${timeout}s"
            }
          }
      }
    previews.forEach { preview ->
      val notification = finished.getValue(preview.id)
      val png = notification["pngPath"]?.jsonPrimitive?.contentOrNull?.let(::File)
      check(png != null && png.isFile && png.length() > 0) {
        "Render failed for ${preview.id}: $notification"
      }
      if (output != null) {
        output.parentFile?.mkdirs()
        Files.copy(png.toPath(), output.toPath(), StandardCopyOption.REPLACE_EXISTING)
      }
      println("${preview.id}\t${output?.absolutePath ?: png.absolutePath}")
    }
  }

  private fun resolve(root: File, path: String): File =
    File(path).let { if (it.isAbsolute) it else File(root, path) }.absoluteFile

  private fun printHelp() {
    println(
      """
      compose-preview bazel — discover/render an existing Compose Multiplatform JVM target

      Usage:
        compose-preview bazel init [--project <workspace>] [--java-info-load <provider.bzl>]
        compose-preview bazel list --target //app:ui [--json]
        compose-preview bazel prepare --target //app:ui
        compose-preview bazel render --target //app:ui [--id <id> | --filter <text>] [--output <png>]

      init creates a small aspect package; existing application BUILD files are untouched.
      Default provider: @rules_java//java/common:java_info.bzl (requires visible rules_java).
      Configure .compose-preview/bazel.json for a different build command/provider deployment.
      --config <file>             Read explicit JSON config (paths relative to --project).
      --build-command <path>      Use bazelisk, blaze or an executable wrapper; no shell parsing.
      --bazel-arg=<argument>      Repeatable build flags, e.g. --bazel-arg=--config=desktop.
      --aspect <label.bzl%name>   Override the aspect entry point.
      --artifacts <metadata.json> Use already-built artifacts from any build system; no build runs.
      --execution-root <dir>     Required for --artifacts; resolves relative artifact paths.
      --out <dir>                Prepared manifest/descriptor directory; default per target/config.
      --timeout <seconds>        Build/render deadline (default 300).
      --project <workspace>      Default current directory.

      list only builds/discovers. prepare/render provision the existing desktop daemon on first use.
      Desktop JVM rendering is supported; Android resources/Robolectric and iOS/Wasm are separate.
      Build arguments/startup flags and output-group names can be configured for internal systems.
      """
        .trimIndent()
    )
  }
}

internal fun installBazelAspect(root: File, javaInfoLoad: String? = null) {
  val directory = root.resolve(".compose-preview/bazel")
  val contents =
    listOf("BUILD.bazel", "runtime.bzl", "java_info.bzl").associateWith { name ->
      val text =
        requireNotNull(BazelCommand::class.java.getResourceAsStream("bazel/$name")) {
            "Missing bundled aspect $name"
          }
          .bufferedReader()
          .use { it.readText() }
      if (name == "java_info.bzl" && javaInfoLoad != null) {
        "load(${bazelJson.encodeToString(javaInfoLoad)}, _JavaInfo = \"JavaInfo\")\n\nJavaInfo = _JavaInfo\n"
      } else text
    }
  // Never overwrite a locally adapted provider or aspect. Repeated identical init is harmless.
  contents.forEach { (name, text) ->
    val file = directory.resolve(name)
    require(!file.exists() || (file.isFile && file.readText() == text)) {
      "Existing aspect differs: $file. Keep it and configure --aspect, or move it before reinitializing."
    }
  }
  directory.mkdirs()
  contents.forEach { (name, text) -> directory.resolve(name).writeText(text) }
}

internal data class PreparedBazelPreviews(val manifest: PreviewManifest, val manifestFile: File)

internal fun prepareBazelPreviews(
  artifacts: ResolvedBazelArtifacts,
  root: File,
  output: File,
): PreparedBazelPreviews {
  val outcome =
    PreviewDiscovery.discover(
      PreviewDiscovery.Input(
        classDirs = emptyList(),
        projectClassJars = artifacts.projectJars,
        dependencyJars = artifacts.runtimeJars.filter { it !in artifacts.projectJars },
        sourceFiles = artifacts.sourceFiles,
        moduleName = artifacts.metadata.target,
        variantName = artifacts.metadata.configuration,
        projectDirectory = root,
        failOnEmpty = true,
        catalogRenderSupported = false,
        animatedPreviewApngSupported = true,
      )
    )
  check(outcome is PreviewDiscovery.Outcome.Success) { "Preview discovery failed: $outcome" }
  outcome.warnings.forEach { System.err.println("WARN: $it") }
  outcome.infoMessages.forEach { System.err.println(it) }
  output.mkdirs()
  val file = output.resolve("previews.json")
  file.writeText(bazelJson.encodeToString(outcome.manifest))
  file
    .resolveSibling("components.json")
    .writeText(bazelJson.encodeToString(ComponentRecords.from(outcome.manifest)))
  file
    .resolveSibling("build-artifacts.json")
    .writeText(bazelJson.encodeToString(artifacts.metadata))
  return PreparedBazelPreviews(outcome.manifest, file)
}

internal fun prepareBazelDesktopDaemon(
  artifacts: ResolvedBazelArtifacts,
  prepared: PreparedBazelPreviews,
  root: File,
): File {
  DaemonSidecarProvision.install(DaemonSidecarProvision.Sidecar.DESKTOP)
  val sidecars =
    (locateBundleSidecarJars("lib-daemon-desktop") + locateBundleSidecarJars("lib-renderer"))
      .distinct()
  check(sidecars.isNotEmpty()) { "Desktop daemon sidecars missing" }
  val native = SkikoNativeProvision.prepare(sidecars)
  val output = prepared.manifestFile.parentFile
  val userClasspath = artifacts.runtimeJars.joinToString(File.pathSeparator) { it.absolutePath }
  val descriptor =
    DaemonLaunchBuilder.build(
      modulePath = artifacts.metadata.target,
      variant = artifacts.metadata.configuration,
      mainClass = "ee.schimke.composeai.daemon.DaemonMain",
      classpath = (listOf(native) + sidecars).map { it.absolutePath },
      jvmArgs =
        listOf("-Xmx1024m", "--enable-native-access=ALL-UNNAMED", "-Dapple.awt.UIElement=true"),
      systemProperties =
        mapOf(
          "java.awt.headless" to "true",
          "skiko.data.path" to composeAiCacheDir("skiko-extracted").absolutePath,
          "composeai.fonts.cacheDir" to composeAiCacheDir("fonts").absolutePath,
          "composeai.daemon.protocolVersion" to "1",
          "composeai.daemon.modulePath" to artifacts.metadata.target,
          "composeai.daemon.moduleId" to artifacts.metadata.target,
          "composeai.daemon.moduleProjectDir" to root.absolutePath,
          "composeai.daemon.workspaceRoot" to root.absolutePath,
          "composeai.daemon.previewsJsonPath" to prepared.manifestFile.absolutePath,
          "composeai.harness.previewsManifest" to prepared.manifestFile.absolutePath,
          "composeai.render.outputDir" to output.resolve("renders").absolutePath,
          // DesktopHost derives its cache/preview root here rather than from Gradle conventions.
          "composeai.daemon.userClassDirs" to userClasspath,
          "composeai.daemon.idleTimeoutMs" to "5000",
        ),
      workingDirectory = root.absolutePath,
      manifestPath = prepared.manifestFile.absolutePath,
    )
  return output.resolve("daemon-launch.json").also {
    it.writeText(DaemonLaunchBuilder.encode(descriptor))
  }
}
