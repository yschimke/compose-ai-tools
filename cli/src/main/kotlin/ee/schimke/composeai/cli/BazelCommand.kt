package ee.schimke.composeai.cli

import ee.schimke.composeai.bundle.AndroidBundleLaunch
import ee.schimke.composeai.bundle.AndroidBundleResources
import ee.schimke.composeai.bundle.bundleSidecarSearchDescription
import ee.schimke.composeai.bundle.locateBundleSidecarJars
import ee.schimke.composeai.bundle.producer.bazelBundleSha
import ee.schimke.composeai.daemonlaunch.DaemonLaunchBuilder
import ee.schimke.composeai.discovery.ComponentRecords
import ee.schimke.composeai.discovery.PreviewDiscovery
import ee.schimke.composeai.discovery.PreviewManifest as DiscoveryManifest
import ee.schimke.composeai.io.composeAiCacheDir
import ee.schimke.composeai.previewdata.PreviewManifest as ResultManifest
import ee.schimke.composeai.previewdata.PreviewModule
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
internal class BazelCommand(
  args: List<String>,
  private val normalSurface: Boolean = true,
  private val invocationDirectory: File? = null,
) : Command(args) {
  override val rowAwareSelection: Boolean
    get() = true

  override fun run() {
    if (bazelHelpRequested(args)) {
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

  /** Runs the operation; returns the prepared previews (null for `init`) for in-process callers. */
  internal fun execute(): PreparedBazelPreviews? {
    val unknown = CliFlagValidation.unknownFlags("bazel", args)
    require(unknown.isEmpty()) { "Unknown options: ${unknown.joinToString()}" }
    val operation = args.getOrNull(CliFlags.findCommandIndex(args.toTypedArray()))
    require(operation in setOf("init", "prepare", "list", "render", "show", "bundle")) {
      "Expected init, prepare, list, render, show or bundle"
    }
    require("--no-render" !in args || operation == "bundle") {
      "--no-render is only supported for bazel bundle"
    }
    val format = args.flagValue("--format")
    require(format == null || format == "png") {
      "Bazel rendering currently supports --format png; '$format' needs an additional export capability"
    }
    val requestedDirectory = File(args.flagValue("--project") ?: ".").canonicalFile
    val root =
      if (args.flagValue("--artifacts") == null)
        detectBazelRoot(requestedDirectory) ?: requestedDirectory
      else requestedDirectory
    require(root.isDirectory) { "Project directory missing: $root" }
    val timeout =
      args.flagValue("--timeout")?.let {
        it.toLongOrNull() ?: error("--timeout must be a positive number of seconds")
      } ?: 300L
    require(timeout > 0) { "--timeout must be positive" }
    if (operation == "init") {
      installBazelAspect(root, args.flagValue("--java-info-load"))
      println("Installed //.compose-preview/bazel:runtime.bzl%compose_preview_aspect")
      return null
    }
    val configFile =
      args.flagValue("--config")?.let { resolve(root, it) }
        ?: root.resolve(".compose-preview/bazel.json")
    require(args.flagValue("--config") == null || configFile.isFile) {
      "Config missing: $configFile"
    }
    val configText = configFile.takeIf { it.isFile }?.readText()
    var config =
      if (configText != null) bazelJson.decodeFromString<BazelBuildConfig>(configText)
      else BazelBuildConfig()
    // Precedence: --build-command, a committed buildCommand, --build-system blaze, then detection.
    val commandSource =
      when {
        args.flagValue("--build-command") != null -> {
          config = config.copy(buildCommand = listOf(args.flagValue("--build-command")!!))
          "--build-command"
        }
        configText != null &&
          "buildCommand" in (bazelJson.parseToJsonElement(configText) as JsonObject) ->
          "buildCommand in $configFile"
        args.flagValue("--build-system") == "blaze" -> {
          config = config.copy(buildCommand = listOf("blaze"))
          "--build-system blaze"
        }
        else -> {
          val choice = selectBazelBuildCommand(root)
          config = config.copy(buildCommand = listOf(choice.command))
          "detected: ${choice.reason}; commit buildCommand in .compose-preview/bazel.json to pin it"
        }
      }
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
          require(normalizeBazelLabel(metadata.target) == normalizeBazelLabel(it)) {
            "Imported artifacts do not match --target"
          }
        }
        metadata.resolve(executionRoot, root)
      } else {
        // Report before target selection, which may already run the chosen tool.
        if ("--verbose" in args || "-v" in args || commandSource.startsWith("detected")) {
          System.err.println("[bazel] build command ${config.buildCommand} ($commandSource)")
        }
        val explicitTarget = args.flagValue("--target") ?: args.flagValue("--module")
        val target =
          explicitTarget
            ?: BazelBuild()
              .selectTarget(root, invocationDirectory ?: requestedDirectory, config, timeout)
        // A configured (non-default) aspect label is used as-is; nothing is written to the tree.
        if (
          config.aspect == DEFAULT_BAZEL_ASPECT &&
            !root.resolve(".compose-preview/bazel/runtime.bzl").isFile
        ) {
          require(config.installAspect) {
            "installAspect is false and $DEFAULT_BAZEL_ASPECT is missing; configure aspect, " +
              "e.g. @rules_compose_preview//:runtime.bzl%compose_preview_aspect"
          }
          installBazelAspect(root)
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
    val outputBase =
      args.flagValue("--out")?.let { resolve(root, it) }
        ?: root.resolve(".compose-preview/outputs/$targetKey")
    val output = if (normalSurface) outputBase.resolve("build/compose-previews") else outputBase
    val prepared = prepareBazelPreviews(artifacts, root, output)
    when (operation) {
      "list" -> {
        if (normalSurface) emitNormalResults(prepared, rendering = false)
        else if ("--json" in args) println(bazelJson.encodeToString(prepared.manifest))
        else
          prepared.manifest.previews.forEach {
            println("${it.id}\t${it.className}.${it.functionName}")
          }
      }
      "prepare",
      "show",
      "render",
      "bundle" -> {
        val reuse = "--reuse-renders" in args
        require(!reuse || (imported != null && operation != "prepare")) {
          "--reuse-renders requires imported artifacts and render, show or bundle"
        }
        if (operation == "prepare") {
          println(prepareBazelDaemon(artifacts, prepared, root).absolutePath)
        } else {
          val noRender = operation == "bundle" && "--no-render" in args
          require(!noRender || !reuse) { "--no-render and --reuse-renders are mutually exclusive" }
          if (noRender) {
            // Discovery and packaging require no renderer provisioning.
          } else if (
            reuse &&
              reuseBazelCaptures(prepared, artifacts, root, copyOutput = operation != "bundle")
          ) {
            // Every selected capture was rendered from these exact artifacts.
          } else {
            val descriptor = prepareBazelDaemon(artifacts, prepared, root)
            claimBazelCaptures(prepared, bazelArtifactsFingerprint(artifacts))
            renderBazelPreviews(
              prepared,
              descriptor,
              root,
              timeout,
              copyOutput = operation != "bundle",
            )
          }
          if (operation == "bundle" || "--bundle" in args) {
            val locks =
              args
                .flagValuesAll("--maven-install")
                .map { resolve(root, it) }
                .ifEmpty {
                  if (imported == null)
                    listOfNotNull(root.resolve("maven_install.json").takeIf { it.isFile })
                  else emptyList()
                }
            val bundle =
              if (operation == "bundle")
                args.flagValue("--output")?.let { resolve(root, it) }
                  ?: output.resolve("bundle.png")
              else output.resolve("bundle.png")
            val selected =
              prepared.manifest.previews
                .filter {
                  (args.flagValue("--preview") == null ||
                    previewMatchesReference(
                      args.flagValue("--preview")!!,
                      it.id,
                      it.className,
                      it.functionName,
                    )) &&
                    (args.flagValue("--id") == null || it.id == args.flagValue("--id")) &&
                    (args.flagValue("--filter") == null ||
                      it.id.contains(args.flagValue("--filter")!!, ignoreCase = true))
                }
                .map { it.id }
                .toSet()
            val manifest =
              writeBazelBundle(
                artifacts,
                prepared,
                selected,
                locks,
                bundle,
                "--embed-deps" in args,
                includeRenders = !noRender,
              )
            System.err.println(
              "Bundle: $bundle (${manifest.resolution}, ${manifest.classpath.size} runtime entries)"
            )
          }
          if (normalSurface) emitNormalResults(prepared, rendering = true)
        }
      }
    }
    return prepared
  }

  /**
   * Returns false, without copying anything, when a selected capture is missing or was rendered
   * from different artifacts; the caller then renders instead of returning stale PNGs.
   */
  private fun reuseBazelCaptures(
    prepared: PreparedBazelPreviews,
    artifacts: ResolvedBazelArtifacts,
    root: File,
    copyOutput: Boolean,
  ): Boolean {
    val selected =
      prepared.manifest.previews.filter {
        (args.flagValue("--id") == null || it.id == args.flagValue("--id")) &&
          (args.flagValue("--filter") == null ||
            it.id.contains(args.flagValue("--filter")!!, ignoreCase = true)) &&
          (args.flagValue("--preview") == null ||
            previewMatchesReference(
              args.flagValue("--preview")!!,
              it.id,
              it.className,
              it.functionName,
            ))
      }
    require(selected.isNotEmpty()) { "No previews match the selection" }
    val output = if (copyOutput) args.flagValue("--output")?.let { resolve(root, it) } else null
    require(output == null || selected.size == 1) { "--output requires one preview" }
    if (!bazelCapturesMatch(prepared, bazelArtifactsFingerprint(artifacts))) {
      System.err.println("Captures were not rendered from the imported artifacts; rendering")
      return false
    }
    selected.forEach { preview ->
      preview.captures.forEach { capture ->
        val png = prepared.manifestFile.parentFile.resolve(capture.renderOutput)
        if (!png.isFile || png.length() == 0L) {
          System.err.println("Prepared capture missing: $png; rendering")
          return false
        }
      }
    }
    selected.forEach { preview ->
      if (output != null) {
        output.parentFile?.mkdirs()
        Files.copy(
          prepared.manifestFile.parentFile.resolve(preview.captures.first().renderOutput).toPath(),
          output.toPath(),
          StandardCopyOption.REPLACE_EXISTING,
        )
      }
    }
    return true
  }

  private fun renderBazelPreviews(
    prepared: PreparedBazelPreviews,
    descriptor: File,
    root: File,
    timeout: Long,
    copyOutput: Boolean = true,
  ) {
    val exactId = args.flagValue("--id")
    val filter = args.flagValue("--filter")
    val previews =
      prepared.manifest.previews.filter {
        (args.flagValue("--preview") == null ||
          previewMatchesReference(
            args.flagValue("--preview")!!,
            it.id,
            it.className,
            it.functionName,
          )) &&
          (exactId == null || it.id == exactId) &&
          (filter == null || it.id.contains(filter, ignoreCase = true))
      }
    require(previews.isNotEmpty()) { "No previews match the selection" }
    val output = if (copyOutput) args.flagValue("--output")?.let { resolve(root, it) } else null
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
      // The daemon reports its actual filename. Stage the returned artifact at the
      // discovery manifest's declared path so every normal result consumer sees it.
      preview.captures.firstOrNull()?.let { capture ->
        val declared = prepared.manifestFile.parentFile.resolve(capture.renderOutput)
        declared.parentFile.mkdirs()
        if (declared.canonicalFile != png.canonicalFile) {
          Files.copy(png.toPath(), declared.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
      }
      if (output != null) {
        output.parentFile?.mkdirs()
        Files.copy(png.toPath(), output.toPath(), StandardCopyOption.REPLACE_EXISTING)
      }
      if (!normalSurface) println("${preview.id}\t${output?.absolutePath ?: png.absolutePath}")
    }
  }

  private fun emitNormalResults(prepared: PreparedBazelPreviews, rendering: Boolean) {
    val moduleDirectory = prepared.manifestFile.parentFile.parentFile.parentFile
    val manifest = bazelJson.decodeFromString<ResultManifest>(prepared.manifestFile.readText())
    val module = PreviewModule(manifest.module, moduleDirectory)
    val all = buildResults(listOf(module to manifest))
    val requested = selectRequested(all)
    val results =
      if (rendering && changedOnly) requested.filter { it.hasChangedCapture() } else requested
    if ("--json" in args)
      println(encodeResponse(results, countsScope = if (rendering) requested else null))
    else if (!rendering) results.forEach { println("${it.id}  (${it.sourceFile ?: "unknown"})") }
    else
      results.forEach { result ->
        println(
          "${result.functionName} (${result.id})${previewStatusTag(result)}${result.sha256?.let { "  sha=${it.take(12)}" } ?: ""}"
        )
        result.captures.forEach { capture -> capture.pngPath?.let { println("  $it") } }
      }
    if (results.isEmpty() && !changedOnly) error("No previews match the selection")
  }

  private fun resolve(root: File, path: String): File =
    File(path).let { if (it.isAbsolute) it else File(root, path) }.absoluteFile

  private fun printHelp() {
    println(
      """
      compose-preview bazel — discover/render an existing Compose Multiplatform JVM target

      Usage:
        compose-preview bazel init [--project <workspace>] [--java-info-load <provider.bzl>]
        compose-preview list [--target //app:ui] [--json]
        compose-preview bazel list [--target //app:ui] [--json]
        compose-preview bazel prepare --target //app:ui
        compose-preview bazel bundle --target //app:ui --maven-install maven_install.json --output bundle.png
        compose-preview bazel render --target //app:ui [--id <id> | --filter <text>] [--output <png>]

      Normal list/render/show prefer Gradle settings; otherwise they use Bazel/Blaze indicators.
      The default aspect package is installed on first use; application BUILD files are untouched.
      A configured target wins; otherwise a single JVM target in the current package is selected.
      Default provider: @rules_java//java/common:java_info.bzl (requires visible rules_java).
      Configure .compose-preview/bazel.json for a different build command/provider deployment.
      --config <file>             Read explicit JSON config (paths relative to --project).
      --build-command <path>      Use bazelisk, blaze or an executable wrapper; no shell parsing.
                                  Otherwise: configured buildCommand, then
                                  ${'$'}COMPOSE_PREVIEW_BUILD_COMMAND, .blazerc, .bazelversion
                                  (bazelisk), then bazel/bazelisk/blaze on PATH.
      --bazel-arg=<argument>      Repeatable build flags, e.g. --bazel-arg=--config=desktop.
      --aspect <label.bzl%name>   Override the aspect entry point.
      --artifacts <metadata.json> Use already-built artifacts from any build system; no build runs.
      --execution-root <dir>     Required for --artifacts; resolves relative artifact paths.
      --reuse-renders             Reuse captures rendered from the same imported artifacts; else render.
      --bundle                   render: additionally pack the portable PNG/ZIP bundle.
      --maven-install <file>     Repeatable rules_jvm_external lockfiles; Recover published coordinates, including transformed runtime jars.
      --no-render                bundle: prepare a runnable bundle with a stub cover, offline.
      --embed-deps               Embed all dependencies for offline bundle use.
      --out <dir>                Prepared manifest/descriptor directory; default per target/config.
      --timeout <seconds>        Build/render deadline (default 300).
      --project <workspace>      Default current directory.

      list only builds/discovers. prepare/render provision the matching daemon on first use.
      Desktop JVM targets render on the desktop daemon. Android targets (artifacts schema 2, from
      @rules_compose_preview//:android.bzl%compose_preview_android_aspect) render on Robolectric;
      set $ROBOLECTRIC_DEPS_DIR_ENV to pre-provisioned android-all jars to stay offline.
      iOS/Wasm are separate.
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

internal data class PreparedBazelPreviews(val manifest: DiscoveryManifest, val manifestFile: File)

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
        // Drives activity/app-tour previews and Wear detection, as AGP's merged manifest does.
        mergedManifest = artifacts.android?.manifest,
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

/**
 * Identifies the runtime inputs a capture is rendered from by content, so a same-size,
 * timestamp-preserving replacement (cache restore, copy) still invalidates reuse. Classpath order
 * is kept because it decides which class or resource wins; hashing is far cheaper than rendering.
 */
internal fun bazelArtifactsFingerprint(artifacts: ResolvedBazelArtifacts): String {
  val digest = MessageDigest.getInstance("SHA-256")
  (artifacts.runtimeJars +
      artifacts.dataFiles +
      listOfNotNull(
        artifacts.android?.rJar,
        artifacts.android?.resourceApk,
        artifacts.android?.manifest,
      ))
    .flatMap {
      if (it.isDirectory) it.walkTopDown().filter(File::isFile).sorted().toList() else listOf(it)
    }
    .forEach { digest.update("${it.absolutePath}\t${bazelBundleSha(it)}\n".toByteArray()) }
  return digest.digest().joinToString("") { "%02x".format(it) }
}

private fun PreparedBazelPreviews.capturesFingerprintFile(): File =
  manifestFile.resolveSibling("captures-artifacts.sha256")

internal fun bazelCapturesMatch(prepared: PreparedBazelPreviews, fingerprint: String): Boolean =
  prepared.capturesFingerprintFile().let { it.isFile && it.readText() == fingerprint }

/**
 * Records that captures in this output come from [fingerprint]. The output directory is keyed only
 * by target/config, so captures left by different artifacts are deleted first; a partial render
 * then can never lend its fresh fingerprint to an unrendered preview's stale PNG.
 */
internal fun claimBazelCaptures(prepared: PreparedBazelPreviews, fingerprint: String) {
  if (bazelCapturesMatch(prepared, fingerprint)) return
  val directory = prepared.manifestFile.parentFile
  prepared.manifest.previews.forEach { preview ->
    preview.captures
      .filter { it.renderOutput.isNotEmpty() }
      .forEach { directory.resolve(it.renderOutput).delete() }
  }
  prepared.capturesFingerprintFile().writeText(fingerprint)
}

/** Writes the launch descriptor for the renderer the artifacts call for. */
internal fun prepareBazelDaemon(
  artifacts: ResolvedBazelArtifacts,
  prepared: PreparedBazelPreviews,
  root: File,
): File =
  if (artifacts.android != null) prepareBazelAndroidDaemon(artifacts, prepared, root)
  else prepareBazelDesktopDaemon(artifacts, prepared, root)

/** Pre-provisioned Robolectric runtime jars (`android-all-instrumented-*.jar`); unset fetches. */
internal const val ROBOLECTRIC_DEPS_DIR_ENV = "COMPOSE_PREVIEW_ROBOLECTRIC_DEPS_DIR"

/**
 * Robolectric resolves `android-all-instrumented` from Maven Central on first render. With
 * [ROBOLECTRIC_DEPS_DIR_ENV] set it runs offline against that directory instead, so a closed
 * network or a build that must not fetch at render time can supply the jars itself.
 */
internal fun robolectricOfflineProperties(
  dependencyDir: String? = System.getenv(ROBOLECTRIC_DEPS_DIR_ENV)
): Map<String, String> {
  if (dependencyDir.isNullOrBlank()) return emptyMap()
  val dir = File(dependencyDir).absoluteFile
  require(dir.isDirectory) { "$ROBOLECTRIC_DEPS_DIR_ENV is not a directory: $dir" }
  return mapOf("robolectric.offline" to "true", "robolectric.dependency.dir" to dir.path)
}

/**
 * The Robolectric daemon launch for an Android target, built from the linked resources the
 * artifacts carry rather than from AGP's unit-test config. The resource APK, manifest and package
 * reach Robolectric through a synthesized `test_config.properties`, exactly as the bundle lanes do
 * ([AndroidBundleResources]); the final-ID R classes lead both the daemon and the user classpath.
 */
internal fun prepareBazelAndroidDaemon(
  artifacts: ResolvedBazelArtifacts,
  prepared: PreparedBazelPreviews,
  root: File,
): File {
  val android = requireNotNull(artifacts.android) { "Not an Android target" }
  DaemonSidecarProvision.install(DaemonSidecarProvision.Sidecar.ANDROID)
  val daemonJars = locateBundleSidecarJars("lib-daemon-android")
  check(daemonJars.isNotEmpty()) {
    "Android daemon sidecar missing; looked in ${bundleSidecarSearchDescription("lib-daemon-android")}"
  }
  val androidJar =
    checkNotNull(
      AndroidBundleLaunch.resolveAndroidJar(
        localPropertiesFile = root.resolve("local.properties").takeIf { it.isFile }
      )
    ) {
      "Android rendering needs android.jar: set ANDROID_HOME or ANDROID_SDK_ROOT"
    }
  val output = prepared.manifestFile.parentFile
  val resources =
    AndroidBundleResources.daemonClasspath(
      AndroidBundleResources.Extracted(android.resourceApk, android.manifest, android.rJar),
      output.resolve("android-resources"),
      android.packageName,
    )
  val userClasspath =
    (listOfNotNull(android.rJar) + artifacts.runtimeJars).distinct().joinToString(
      File.pathSeparator
    ) {
      it.absolutePath
    }
  val launch = AndroidBundleLaunch()
  val descriptor =
    DaemonLaunchBuilder.build(
      modulePath = artifacts.metadata.target,
      variant = artifacts.metadata.configuration,
      mainClass = "ee.schimke.composeai.daemon.DaemonMain",
      classpath = (resources + daemonJars + androidJar).map { it.absolutePath },
      jvmArgs = launch.jvmArgs() + "-Xmx2048m",
      systemProperties =
        launch.robolectricSystemProperties() +
          robolectricOfflineProperties() +
          mapOf(
            "composeai.daemon.protocolVersion" to "1",
            "composeai.daemon.modulePath" to artifacts.metadata.target,
            "composeai.daemon.moduleId" to artifacts.metadata.target,
            "composeai.daemon.moduleProjectDir" to root.absolutePath,
            "composeai.daemon.workspaceRoot" to root.absolutePath,
            "composeai.daemon.previewsJsonPath" to prepared.manifestFile.absolutePath,
            "composeai.harness.previewsManifest" to prepared.manifestFile.absolutePath,
            "composeai.render.manifest" to prepared.manifestFile.absolutePath,
            "composeai.render.outputDir" to output.resolve("renders").absolutePath,
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

internal fun bazelHelpRequested(args: List<String>): Boolean =
  args.isEmpty() ||
    args.any { it == "--help" || it == "-h" } ||
    args.getOrNull(CliFlags.findCommandIndex(args.toTypedArray())) == "help"
