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
import ee.schimke.composeai.daemon.client.SocketDaemonClientFactory
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

  /** Runs the operation; returns each target's prepared previews (none for `init`). */
  internal fun execute(): List<PreparedBazelPreviews> {
    val unknown = CliFlagValidation.unknownFlags("bazel", args)
    require(unknown.isEmpty()) { "Unknown options: ${unknown.joinToString()}" }
    val operation = args.getOrNull(CliFlags.findCommandIndex(args.toTypedArray()))
    if (operation == "daemon") {
      // No build: the shared daemons are found by their sockets.
      BazelKeepDaemon.manage(args.getOrNull(args.indexOf("daemon") + 1))
      return emptyList()
    }
    require(operation in setOf("init", "prepare", "list", "render", "show", "bundle")) {
      "Expected init, prepare, list, render, show, bundle or daemon"
    }
    require("--reuse-renders" !in args || operation in setOf("render", "show", "bundle")) {
      "--reuse-renders requires imported artifacts and render, show or bundle"
    }
    require("--no-render" !in args || operation == "bundle") {
      "--no-render is only supported for bazel bundle"
    }
    val format = args.flagValue("--format")
    require(format == null || format == "png") {
      "Bazel rendering currently supports --format png; '$format' needs an additional export capability"
    }
    // Buck2 shares everything after collection: a BXL script prints the same artifacts documents.
    val buck = args.flagValue("--build-system") == "buck"
    val requestedDirectory = File(args.flagValue("--project") ?: ".").canonicalFile
    val root =
      if (args.flagValue("--artifacts") != null) requestedDirectory
      else if (buck) detectBuckRoot(requestedDirectory) ?: requestedDirectory
      else detectBazelRoot(requestedDirectory) ?: requestedDirectory
    require(root.isDirectory) { "Project directory missing: $root" }
    val timeout =
      args.flagValue("--timeout")?.let {
        it.toLongOrNull() ?: error("--timeout must be a positive number of seconds")
      } ?: 300L
    require(timeout > 0) { "--timeout must be positive" }
    require(!buck || args.flagValue("--aspect") == null) {
      "--aspect is Bazel-only; set bxl in .compose-preview/buck.json for another BXL script"
    }
    if (operation == "init" && buck) {
      installBuckBxl(root, strict = true)
      println("Installed $DEFAULT_BUCK_BXL")
      return emptyList()
    }
    if (operation == "init") {
      installBazelAspect(root, args.flagValue("--java-info-load"))
      println("Installed //.compose-preview/bazel:runtime.bzl%compose_preview_aspect")
      if ("--android" in args) {
        val providers =
          root
            .resolve(".compose-preview/bazel.json")
            .takeIf { it.isFile }
            ?.let { bazelJson.decodeFromString<BazelBuildConfig>(it.readText()).providers }
            .orEmpty()
        installBazelAndroidAspect(root, providers, strict = true)
        println("Installed $DEFAULT_BAZEL_ANDROID_ASPECT")
      }
      return emptyList()
    }
    val configFile =
      args.flagValue("--config")?.let { resolve(root, it) }
        ?: root.resolve(".compose-preview/${if (buck) "buck" else "bazel"}.json")
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
        buck -> {
          val override = System.getenv(BUILD_COMMAND_ENV)?.takeIf { it.isNotBlank() }
          config = config.copy(buildCommand = listOf(override ?: "buck2"))
          if (override != null) "\$$BUILD_COMMAND_ENV" else "default buck2"
        }
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
    // Unconfigured, in a workspace that can load an Android aspect: the selection takes JVM and
    // Android rules alike, and each kind gets its aspect. Otherwise (Blaze, say) it stays JVM-only.
    val autoAndroidAspect =
      if (
        !buck &&
          args.flagValue("--aspect") == null &&
          (configText == null ||
            "aspect" !in (bazelJson.parseToJsonElement(configText) as JsonObject))
      )
        detectAndroidAspect(root, config.providers)
      else null
    args.flagValue("--aspect")?.let { config = config.copy(aspect = it) }
    config =
      config.copy(
        buildArgs = config.buildArgs + args.flagValuesAll(if (buck) "--buck-arg" else "--bazel-arg")
      )
    val imported = args.flagValue("--artifacts")
    // A pattern's or a package's matches are candidates: one without previews is left out. An
    // explicitly named target is a requirement and fails as before.
    var candidates = false
    var selectionLabel: String? = null
    val built =
      if (imported != null) {
        val executionRoot =
          args.flagValue("--execution-root")?.let { resolve(root, it) }
            ?: error("--artifacts requires --execution-root")
        val metadataText = resolve(root, imported).readText()
        val metadata = bazelJson.decodeFromString<BazelArtifacts>(metadataText)
        args.flagValue("--target")?.let {
          require(normalizeBazelLabel(metadata.target) == normalizeBazelLabel(it)) {
            "Imported artifacts do not match --target"
          }
        }
        listOf(
          metadata.resolve(
            executionRoot,
            root,
            config.androidCompileSdk,
            bazelRuntimeJarOwners(metadataText),
          )
        )
      } else if (buck) {
        if ("--verbose" in args || "-v" in args) {
          System.err.println("[buck] build command ${config.buildCommand} ($commandSource)")
        }
        val requested = requestedTargets()
        val selection =
          if (requested.isNotEmpty()) BazelTargetSelection(requested.joinToString(" "), requested)
          else BuckBuild().selectTargets(root, invocationDirectory ?: requestedDirectory, config)
        selectionLabel = selection.label
        candidates = selection.targets.size > 1 || selection.targets.any(::isBuckTargetPattern)
        // A configured (non-default) BXL label is used as-is; nothing is written to the tree. The
        // bundled files are kept current: a missing or CLI-written one is (re)installed, one
        // edited by hand is left alone.
        if (config.bxl == DEFAULT_BUCK_BXL) {
          if (config.installAspect) {
            installBuckBxl(root).forEach { System.err.println("[buck] keeping locally edited $it") }
          } else {
            require(root.resolve("$BUCK_BXL_DIR/compose_preview.bxl").isFile) {
              "installAspect is false and $DEFAULT_BUCK_BXL is missing; configure bxl"
            }
          }
        }
        reportBuild("buck", selection.targets) {
            BuckBuild()
              .collectAll(
                root,
                selection.targets,
                config,
                timeout,
                skipWithoutMetadata = candidates,
              )
          }
          .also { built ->
            if (candidates) {
              System.err.println(
                "[buck] ${built.size} target(s): ${built.joinToString(" ") { it.metadata.target }}"
              )
            }
          }
      } else {
        // Report before target selection, which may already run the chosen tool.
        if ("--verbose" in args || "-v" in args || commandSource.startsWith("detected")) {
          System.err.println("[bazel] build command ${config.buildCommand} ($commandSource)")
        }
        val requested = requestedTargets()
        val selectionConfig =
          if (autoAndroidAspect != null)
            config.copy(targetKinds = config.targetKinds ?: AUTO_TARGET_KINDS)
          else config
        val selection =
          if (requested.isNotEmpty())
            BazelTargetSelection(
              requested.joinToString(" "),
              BazelBuild().expandTargets(root, requested, selectionConfig, timeout),
            )
          else
            BazelBuild()
              .selectTargets(
                root,
                invocationDirectory ?: requestedDirectory,
                selectionConfig,
                timeout,
              )
        selectionLabel = selection.label
        candidates =
          selection.targets.size > 1 ||
            selection.label.split(Regex("\\s+")).any(::isBazelTargetPattern)
        // Each aspect builds the targets it reads: (aspect config, targets) pairs.
        val groups =
          if (autoAndroidAspect != null) {
            val (jvm, android) = BazelBuild().splitAndroid(root, selection.targets, config, timeout)
            val androidConfig = config.copy(aspect = autoAndroidAspect)
            if (android.isNotEmpty() && ("--verbose" in args || "-v" in args)) {
              System.err.println("[bazel] Android targets use ${androidConfig.aspect}")
            }
            listOf(config to jvm, androidConfig to android).filter { it.second.isNotEmpty() }
          } else listOf(config to selection.targets)
        if (candidates) {
          val targets = groups.flatMap { it.second }
          System.err.println("[bazel] ${targets.size} target(s): ${targets.joinToString(" ")}")
        }
        groups.flatMap { (groupConfig, targets) ->
          // The bundled Android aspect is kept current: a missing or CLI-written file is
          // (re)installed, one edited by hand is left alone.
          if (groupConfig.aspect == DEFAULT_BAZEL_ANDROID_ASPECT && groupConfig.installAspect) {
            installBazelAndroidAspect(root, groupConfig.providers).forEach {
              System.err.println("[bazel] keeping locally edited $it")
            }
          }
          // A configured (non-default) aspect label is used as-is; nothing is written to the tree.
          if (
            groupConfig.aspect == DEFAULT_BAZEL_ASPECT &&
              !root.resolve(".compose-preview/bazel/runtime.bzl").isFile
          ) {
            require(groupConfig.installAspect) {
              "installAspect is false and $DEFAULT_BAZEL_ASPECT is missing; configure aspect, " +
                "e.g. @rules_compose_preview//:runtime.bzl%compose_preview_aspect"
            }
            installBazelAspect(root)
          }
          if (groupConfig.aspect == DEFAULT_BAZEL_ASPECT && groupConfig.installAspect) {
            migrateBazelAspectBuildFile(root)
          }
          reportBuild("bazel", targets) {
            BazelBuild()
              .collectAll(root, targets, groupConfig, timeout, skipWithoutMetadata = candidates)
          }
        }
      }
    // A selected set is one module (one page in `browse`, one daemon), unless it cannot share one.
    val merged = selectionLabel?.let { label ->
      mergeBazelArtifacts(label, built, config.mergeAndroid) { plan ->
        val key = bazelTargetKey(label, built.first().metadata.configuration)
        System.err.println(
          "[bazel] linking ${built.size} Android targets' resources into one APK " +
            "(${plan.compiledResources.size} archives, ${plan.extraPackages.size + 1} R packages)"
        )
        linkAndroidMerge(plan, root.resolve(".compose-preview/outputs/$key/android-link"), timeout)
      }
    }
    if (merged == null && built.size > 1) {
      System.err.println(
        "[bazel] ${built.size} targets kept as separate modules: ${bazelMergeBlocker(built, config.mergeAndroid)}"
      )
    }
    val collected = merged?.let(::listOf) ?: built
    require(args.flagValue("--out") == null || collected.size == 1) {
      "--out names one target's output; select one with --target"
    }
    val selections = collected.mapNotNull { artifacts ->
      if ("--verbose" in args || "-v" in args) {
        System.err.println(
          "[bazel] ${artifacts.metadata.target} (${artifacts.metadata.configuration}): " +
            "${artifacts.projectJars.size} application jars, ${artifacts.runtimeJars.size} runtime jars, " +
            "${artifacts.sourceFiles.size} sources, ${artifacts.dataFiles.size} runfiles"
        )
      }
      val targetKey = bazelTargetKey(artifacts.metadata.target, artifacts.metadata.configuration)
      val outputBase =
        args.flagValue("--out")?.let { resolve(root, it) }
          ?: root.resolve(".compose-preview/outputs/$targetKey")
      val output = if (normalSurface) outputBase.resolve("build/compose-previews") else outputBase
      val prepared = prepareBazelPreviews(artifacts, root, output, failOnEmpty = !candidates)
      if (prepared.manifest.previews.isEmpty()) {
        System.err.println("[bazel] ${artifacts.metadata.target}: no previews, skipped")
        null
      } else BazelSelection(artifacts, prepared, output)
    }
    check(selections.isNotEmpty()) {
      "No previews in " +
        collected.joinToString(" ") { it.metadata.target }.ifEmpty { selectionLabel.orEmpty() }
    }
    when (operation) {
      "list" -> {
        if (normalSurface) emitNormalResults(selections.map { it.prepared }, rendering = false)
        else if ("--json" in args) {
          val manifests = selections.map { it.prepared.manifest }
          println(
            if (manifests.size == 1) bazelJson.encodeToString(manifests.single())
            else bazelJson.encodeToString(manifests)
          )
        } else
          selections
            .flatMap { it.prepared.manifest.previews }
            .forEach { println("${it.id}\t${it.className}.${it.functionName}") }
      }
      "prepare" -> {
        selections.forEach { (artifacts, prepared) ->
          println(
            prepareBazelDaemon(
                artifacts,
                prepared,
                root,
                bazelClockPackages(config, artifacts.android),
              )
              .absolutePath
          )
        }
      }
      "show",
      "render",
      "bundle" -> {
        val reuse = "--reuse-renders" in args
        require(!reuse || imported != null) {
          "--reuse-renders requires imported artifacts and render, show or bundle"
        }
        val bundling = operation == "bundle" || "--bundle" in args
        require(!bundling || selections.size == 1) {
          "A bundle packs one target; ${selections.size} have previews " +
            "(${selections.joinToString(" ") { it.artifacts.metadata.target }}). Select one with --target."
        }
        val noRender = operation == "bundle" && "--no-render" in args
        require(!noRender || !reuse) { "--no-render and --reuse-renders are mutually exclusive" }
        // The selection flags apply across targets; --output still names one preview in all.
        val selected = selections.associateWith { selectedPreviews(it.prepared) }
        require(selected.values.any { it.isNotEmpty() }) { "No previews match the selection" }
        require(
          args.flagValue("--output") == null ||
            operation == "bundle" ||
            selected.values.sumOf { it.size } == 1
        ) {
          "--output requires one preview; select it with --id or --filter"
        }
        selected.forEach { (selection, previews) ->
          val (artifacts, prepared, output) = selection
          if (previews.isEmpty() && !bundling) return@forEach
          if (noRender) {
            // Discovery and packaging require no renderer provisioning.
          } else if (
            reuse &&
              reuseBazelCaptures(prepared, artifacts, root, copyOutput = operation != "bundle")
          ) {
            // Every selected capture was rendered from these exact artifacts.
          } else {
            // A kept daemon serves every later render, so its pool is sized once, not per call.
            val keep = !bundling && BazelKeepDaemon.requested(args, config)
            val descriptor =
              prepareBazelDaemon(
                artifacts,
                prepared,
                root,
                bazelClockPackages(config, artifacts.android),
                sandboxCount = if (keep) 1 else oneShotSandboxCount(previews.size),
                // `render` and `show` read only the PNG; a bundle may pack sidecars.
                pngOnly = !bundling,
              )
            val shared =
              if (keep)
                BazelKeepDaemon.prepare(
                  descriptor,
                  launchInputs =
                    artifacts.android
                      ?.let { listOfNotNull(it.resourceApk, it.manifest, it.rJar) }
                      .orEmpty(),
                  previewsManifest = prepared.manifestFile,
                  idleTimeoutSeconds = config.daemonIdleTimeoutSeconds,
                )
              else null
            claimBazelCaptures(prepared, bazelArtifactsFingerprint(artifacts))
            renderBazelPreviews(
              prepared,
              shared?.descriptor ?: descriptor,
              root,
              timeout,
              copyOutput = operation != "bundle",
              shared = shared,
            )
          }
          if (bundling) {
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
            val manifest =
              writeBazelBundle(
                artifacts,
                prepared,
                previews.map { it.id }.toSet(),
                locks,
                bundle,
                "--embed-deps" in args,
                includeRenders = !noRender,
                dependencies = bazelDependencyOptions(config.dependencyPolicy, artifacts),
              )
            System.err.println(
              "Bundle: $bundle (${manifest.resolution}, ${manifest.classpath.size} runtime entries)"
            )
          }
        }
        if (normalSurface) emitNormalResults(selections.map { it.prepared }, rendering = true)
      }
    }
    return selections.map { it.prepared }
  }

  /** One target's artifacts, its discovered previews, and the directory they were written to. */
  private data class BazelSelection(
    val artifacts: ResolvedBazelArtifacts,
    val prepared: PreparedBazelPreviews,
    val output: File,
  )

  /**
   * Labels and patterns from every `--target` (each may hold several, space separated, as on a
   * `bazel build` line), else a label passed as `--module`. Empty leaves the choice to
   * [BazelBuild.selectTargets]: the configured `target`, then `targets`, then the current package.
   */
  private fun requestedTargets(): List<String> =
    args
      .flagValuesAll("--target")
      .flatMap { it.split(Regex("\\s+")) }
      .filter { it.isNotBlank() }
      .ifEmpty { listOfNotNull(args.flagValue("--module")) }

  /** The previews of [prepared] that `--preview`, `--id` and `--filter` select. */
  private fun selectedPreviews(prepared: PreparedBazelPreviews) =
    prepared.manifest.previews.filter {
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
    /** A kept daemon ([BazelKeepDaemon]) to connect to, starting it if none is running. */
    shared: BazelKeepDaemon.Prepared? = null,
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
    val sessionConfig =
      RenderSessionConfig(
        descriptorPath = descriptor,
        workspaceRoot = root,
        initializeTimeout = timeout.seconds,
        maxRenderTime = timeout.seconds,
      )
    (if (shared == null) SubprocessRenderSessions.open(sessionConfig)
      else SubprocessRenderSessions.open(sessionConfig, factory = SocketDaemonClientFactory()))
      .use { session ->
        // Rebuilt user jars: the kept daemon swaps its user class loader and keeps its sandbox.
        shared?.changedJar?.let { session.fileChanged(it) }
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

  private fun emitNormalResults(prepared: List<PreparedBazelPreviews>, rendering: Boolean) {
    val all =
      buildResults(
        prepared.map {
          val moduleDirectory = it.manifestFile.parentFile.parentFile.parentFile
          val manifest = bazelJson.decodeFromString<ResultManifest>(it.manifestFile.readText())
          PreviewModule(manifest.module, moduleDirectory) to manifest
        }
      )
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
        compose-preview bazel init [--project <workspace>] [--java-info-load <provider.bzl>] [--android]
        compose-preview list [--target //app:ui] [--json]
        compose-preview list --target //app/... [--target -//app/legacy/...] [--json]
        compose-preview bazel list [--target //app:ui] [--json]
        compose-preview bazel prepare --target //app:ui
        compose-preview bazel bundle --target //app:ui --maven-install maven_install.json --output bundle.png
        compose-preview bazel render --target //app:ui [--id <id> | --filter <text>] [--output <png>]
        compose-preview bazel daemon status|stop

      Normal list/render/show prefer Gradle settings; otherwise they use Bazel/Blaze indicators.
      The default aspect package is installed on first use; application BUILD files are untouched.
      --target is repeatable and takes labels or patterns, as bazel build does: //app/...,
      //app:all, //app:*, and -//pattern exclusions. A pattern is expanded with query and filtered
      to the aspect's rule kinds (targetKinds in bazel.json overrides them). The selection is one
      module rendered by one daemon (Android targets stay separate, one per target). Without --target,
      target/targets in .compose-preview/bazel.json win; otherwise the current package (//pkg:all),
      or outside any package everything below the directory (//dir/...), is selected.
      Default provider: @rules_java//java/common:java_info.bzl (requires visible rules_java).
      Configure .compose-preview/bazel.json for a different build command/provider deployment.
      --config <file>             Read explicit JSON config (paths relative to --project).
      --build-command <path>      Use bazelisk, blaze or an executable wrapper; no shell parsing.
                                  Otherwise: configured buildCommand, then
                                  ${'$'}COMPOSE_PREVIEW_BUILD_COMMAND, .blazerc, .bazelversion
                                  (bazelisk), then bazel/bazelisk/blaze on PATH.
      --bazel-arg=<argument>      Repeatable build flags, e.g. --bazel-arg=--config=desktop.
      --aspect <label.bzl%name>   Pin one aspect entry point. Unset, JVM targets use the JVM
                                  aspect and android_library targets an Android one:
                                  @rules_compose_preview's when MODULE.bazel declares it, else
                                  $DEFAULT_BAZEL_ANDROID_ASPECT,
                                  which the CLI installs (init --android does it up front), when
                                  rules_android and rules_java are visible (MODULE.bazel or
                                  WORKSPACE) or
                                  "providers" in bazel.json points its shims at internal rules.
                                  Neither (Blaze): unconfigured selections stay JVM-only.
      --build-system buck         Buck2 (compose-preview buck … is the same): a BXL script reads
                                  the JVM and Android providers instead of an aspect, linking an
                                  android_library's resources; .compose-preview/buck.json
                                  configures it. --target takes //app:lib, //pkg: and //dir/...;
                                  without one the current package (//pkg:) is selected.
      --buck-arg=<argument>       Repeatable buck2 bxl flags, e.g. --buck-arg=@mode/desktop.
      --artifacts <metadata.json> Use already-built artifacts from any build system; no build runs.
      --execution-root <dir>     Required for --artifacts; resolves relative artifact paths.
      --reuse-renders             Reuse captures rendered from the same imported artifacts; else render.
      --keep-daemon               render/show: reuse a warm daemon across runs (also
                                  ${'$'}COMPOSE_PREVIEW_KEEP_DAEMON=1 or keepDaemon in bazel.json). It
                                  exits after daemonIdleTimeoutSeconds (default 300) idle, or on
                                  bazel daemon stop. Rebuilt jars reload without a restart; a
                                  changed preview set, resources or settings start a fresh one.
      --bundle                   render: additionally pack the portable PNG/ZIP bundle.
      --maven-install <file>     Repeatable rules_jvm_external lockfiles; Recover published coordinates, including transformed runtime jars.
      --no-render                bundle: prepare a runnable bundle with a stub cover, offline.
      --embed-deps               Embed all dependencies for offline bundle use.
                                 (dependencyPolicy in bazel.json chooses per group otherwise.)
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

private fun bundledBazelAspectFile(name: String): String =
  requireNotNull(BazelCommand::class.java.getResourceAsStream("bazel/$name")) {
      "Missing bundled aspect $name"
    }
    .bufferedReader()
    .use { it.readText() }

internal fun installBazelAspect(root: File, javaInfoLoad: String? = null) {
  val directory = root.resolve(".compose-preview/bazel")
  migrateBazelAspectBuildFile(root)
  val contents =
    listOf("BUILD.bazel", "runtime.bzl", "java_info.bzl").associate { name ->
      val text = bundledBazelAspectFile(name)
      installedBazelFileName(name) to
        if (name == "java_info.bzl" && javaInfoLoad != null) {
          "load(${bazelJson.encodeToString(javaInfoLoad)}, _JavaInfo = \"JavaInfo\")\n\nJavaInfo = _JavaInfo\n"
        } else text
    }
  // Never overwrite a locally adapted provider or aspect. Repeated identical init is harmless.
  // An edited BUILD.bazel left by an earlier CLI would shadow the BUILD written here.
  val legacy = directory.resolve("BUILD.bazel")
  require(!legacy.exists()) {
    "Existing aspect differs: $legacy. Keep it and configure --aspect, or move it before reinitializing."
  }
  contents.forEach { (name, text) ->
    val file = directory.resolve(name)
    require(!file.exists() || (file.isFile && file.readText() == text)) {
      "Existing aspect differs: $file. Keep it and configure --aspect, or move it before reinitializing."
    }
  }
  directory.mkdirs()
  contents.forEach { (name, text) -> directory.resolve(name).writeText(text) }
}

/**
 * Runs [build] between a start and an end line on stderr: a cold aspect or BXL build can take a
 * minute with the build tool's own output going to a log, and a CLI that prints nothing meanwhile
 * looks hung.
 */
internal fun <T> reportBuild(tool: String, targets: List<String>, build: () -> T): T {
  val shown = targets.take(3).joinToString(" ") + if (targets.size > 3) " …" else ""
  System.err.println("[$tool] Building $shown")
  val started = System.nanoTime()
  return build().also {
    val seconds = (System.nanoTime() - started) / 1_000_000_000.0
    System.err.println("[$tool] Build complete (${"%.1f".format(seconds)}s)")
  }
}

/**
 * Renames the JVM aspect package file an earlier CLI wrote as `BUILD.bazel` to `BUILD`, which Blaze
 * deployments that read no `BUILD.bazel` also see, or removes it when a `BUILD` is already there.
 * One edited by hand is left alone.
 */
internal fun migrateBazelAspectBuildFile(root: File) {
  val directory = root.resolve(".compose-preview/bazel")
  val legacy = directory.resolve("BUILD.bazel")
  val build = directory.resolve("BUILD")
  if (!legacy.isFile || legacy.readText() != bundledBazelAspectFile("BUILD.bazel")) return
  // Already beside a BUILD (an older CLI run after a newer one): the CLI's copy simply goes.
  if (build.exists()) check(legacy.delete()) { "Could not remove $legacy" }
  else check(legacy.renameTo(build)) { "Could not rename $legacy to $build" }
}

internal data class PreparedBazelPreviews(val manifest: DiscoveryManifest, val manifestFile: File)

internal fun prepareBazelPreviews(
  artifacts: ResolvedBazelArtifacts,
  root: File,
  output: File,
  /** False for a pattern's candidate targets, which may hold no previews and are then skipped. */
  failOnEmpty: Boolean = true,
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
        failOnEmpty = failOnEmpty,
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

/** A module's output directory name: its target and configuration, hashed. */
internal fun bazelTargetKey(target: String, configuration: String): String =
  MessageDigest.getInstance("SHA-256")
    .digest((target + "\n" + configuration).toByteArray())
    .take(8)
    .joinToString("") { "%02x".format(it) }

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
  /** [bazelClockPackages]; Android only, since pinning works through Robolectric. */
  clockPackages: List<String> = emptyList(),
  /** [oneShotSandboxCount] for a one-shot render; null keeps the daemon's own pool size. */
  sandboxCount: Int? = null,
  /** Skip every post-capture data product ([pngOnlySystemProperties]); the caller reads the PNG. */
  pngOnly: Boolean = false,
): File =
  if (artifacts.android != null)
    prepareBazelAndroidDaemon(artifacts, prepared, root, clockPackages, sandboxCount, pngOnly)
  else prepareBazelDesktopDaemon(artifacts, prepared, root, pngOnly)

/**
 * The daemon's post-capture allowlist (`DaemonProperties.Names.POST_CAPTURE_KINDS`) set to a kind
 * no processor writes, so a render runs none of them: the layout inspector, semantics, wireframe,
 * fonts, navigation, strings and resources extractors cost about 500 ms after each capture (#6104),
 * and a one-shot `render` reads only the PNG. A kind a request asks for still runs.
 */
internal fun pngOnlySystemProperties(pngOnly: Boolean): Map<String, String> =
  if (pngOnly) mapOf(BAZEL_POST_CAPTURE_KINDS_PROPERTY to BAZEL_NO_POST_CAPTURE_KIND)
  else emptyMap()

/** The daemon's post-capture allowlist (`DaemonProperties.Names.POST_CAPTURE_KINDS`). */
internal const val BAZEL_POST_CAPTURE_KINDS_PROPERTY = "composeai.daemon.postCaptureKinds"

/** A data-product kind no post-capture processor writes; an empty allowlist would mean "unset". */
internal const val BAZEL_NO_POST_CAPTURE_KIND = "none"

/**
 * Robolectric sandboxes for a one-shot render of [previews] previews: one per preview, up to the
 * daemon's warm-spare pool of five. The daemon boots and warm-renders every sandbox before it
 * serves the first render, so a pool larger than the work costs about five seconds per idle worker
 * JVM; a single preview paid ~20 s for four (compose-ai-tools#5945). A long-lived daemon
 * (`prepare`, for browse and MCP) keeps the pool, which held interactive sessions need.
 */
internal fun oneShotSandboxCount(previews: Int): Int = previews.coerceIn(1, 5)

/** The daemon's sandbox pool size (`DaemonProperties.Names.SANDBOX_COUNT`). */
internal const val BAZEL_SANDBOX_COUNT_PROPERTY = "composeai.daemon.sandboxCount"

/** The daemon's consumer-clock property (`ConsumerClock.PROPERTY` in the renderer). */
internal const val BAZEL_CLOCK_PACKAGES_PROPERTY = "composeai.render.clockPackages"

/** Pre-provisioned Robolectric runtime jars (`android-all-instrumented-*.jar`); unset fetches. */
internal const val ROBOLECTRIC_DEPS_DIR_ENV = "COMPOSE_PREVIEW_ROBOLECTRIC_DEPS_DIR"

/**
 * Robolectric resolves `android-all-instrumented` from Maven Central on first render. With
 * [ROBOLECTRIC_DEPS_DIR_ENV] set it runs offline against that directory instead, so a closed
 * network or a build that must not fetch at render time can supply the jars itself.
 *
 * Under [NetworkPolicy]'s switch ([noNetwork]) the directory is required, since without it
 * Robolectric would fetch, and font downloads are turned off too.
 */
internal fun robolectricOfflineProperties(
  dependencyDir: String? = System.getenv(ROBOLECTRIC_DEPS_DIR_ENV),
  noNetwork: Boolean = NetworkPolicy.disabled,
): Map<String, String> {
  if (dependencyDir.isNullOrBlank()) {
    NetworkPolicy.require(
      "Rendering Android previews on Robolectric (it fetches android-all-instrumented from Maven " +
        "Central)",
      "Set $ROBOLECTRIC_DEPS_DIR_ENV to a directory of the android-all-instrumented jars the " +
        "render daemon's Robolectric version expects.",
      disabled = noNetwork,
    )
    return emptyMap()
  }
  val dir = File(dependencyDir).absoluteFile
  require(dir.isDirectory) { "$ROBOLECTRIC_DEPS_DIR_ENV is not a directory: $dir" }
  return mapOf("robolectric.offline" to "true", "robolectric.dependency.dir" to dir.path) +
    NetworkPolicy.daemonSystemProperties(noNetwork)
}

/**
 * [level], or, when Robolectric runs offline from a provisioned directory ([offlineProperties],
 * from [robolectricOfflineProperties]) that has no `android-all` for it, the newest level that
 * directory does have, as the Robolectric in [robolectricJars] names its jars. A compileSdk newer
 * than any `android-all` published for that Robolectric (a preview platform on the build machine)
 * otherwise fails every render; this settles for the newest provisioned framework and says so, as
 * [bazelAndroidSdkLevel] does for a JDK too old for the requested level. Unchanged when online,
 * when the level is provisioned, or when no lower level is either (Robolectric then reports it).
 */
internal fun provisionedSdkLevel(
  level: Int,
  robolectricJars: List<File>,
  offlineProperties: Map<String, String>,
  log: (String) -> Unit = { System.err.println(it) },
): Int {
  val directory = offlineProperties["robolectric.dependency.dir"]?.let(::File) ?: return level
  val provisioned =
    runCatching {
      RobolectricDepsProvision.highestProvisionedLevel(
        robolectricJars,
        directory,
        requested = level,
        floor = AndroidBundleLaunch.MIN_SDK,
      )
    }
      .getOrNull() ?: return level
  if (provisioned != level) {
    log(
      "[android] no android-all for SDK $level in $ROBOLECTRIC_DEPS_DIR_ENV ($directory); " +
        "rendering on SDK $provisioned, the newest provisioned (compose-preview provision " +
        "--android --android-sdk $level fetches $level where Robolectric publishes it)"
    )
  }
  return provisioned
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
  clockPackages: List<String> = emptyList(),
  sandboxCount: Int? = null,
  pngOnly: Boolean = false,
): File {
  val android = requireNotNull(artifacts.android) { "Not an Android target" }
  // First: under the network switch a missing android-all directory refuses before any sidecar is
  // provisioned.
  val offlineProperties = robolectricOfflineProperties()
  DaemonSidecarProvision.install(DaemonSidecarProvision.Sidecar.ANDROID)
  val daemonJars = locateBundleSidecarJars("lib-daemon-android")
  check(daemonJars.isNotEmpty()) {
    "Android daemon sidecar missing; looked in ${bundleSidecarSearchDescription("lib-daemon-android")}"
  }
  // The android.jar the target linked against, when the aspect reported it: the host SDK's newest
  // platform may be one Robolectric or the target does not match.
  val androidJar =
    android.androidJar?.takeIf { it.isFile }
      ?: checkNotNull(
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
  // The sandbox follows the target's compileSdk (`composeai.daemon.sandboxSdk`), as a Gradle
  // module's does. The level is clamped to what this JVM's Robolectric can render, so the
  // descriptor pins this JVM as its launcher: a consumer on another JDK must not render it.
  val launch =
    AndroidBundleLaunch(
      sdkLevel =
        provisionedSdkLevel(
          bazelAndroidSdkLevel(
            android.compileSdk,
            target = artifacts.metadata.target,
            source = android.compileSdkSource,
          ),
          daemonJars,
          offlineProperties,
        )
    )
  val descriptor =
    DaemonLaunchBuilder.build(
      modulePath = artifacts.metadata.target,
      variant = artifacts.metadata.configuration,
      mainClass = "ee.schimke.composeai.daemon.DaemonMain",
      classpath = (resources + daemonJars + androidJar).map { it.absolutePath },
      jvmArgs = launch.jvmArgs() + "-Xmx2048m",
      systemProperties =
        launch.robolectricSystemProperties() +
          offlineProperties +
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
          ) +
          // `pinAppClock`: the daemon instruments these prefixes in its sandbox and user-class
          // loader from this one property. Absent when off, so the descriptor is unchanged.
          (if (clockPackages.isEmpty()) emptyMap()
          else mapOf(BAZEL_CLOCK_PACKAGES_PROPERTY to clockPackages.joinToString(","))) +
          // Explicit, so it wins over the warm-spare default the daemon would otherwise boot.
          (sandboxCount?.let { mapOf(BAZEL_SANDBOX_COUNT_PROPERTY to it.toString()) }.orEmpty()) +
          pngOnlySystemProperties(pngOnly),
      workingDirectory = root.absolutePath,
      manifestPath = prepared.manifestFile.absolutePath,
      javaLauncher = currentJavaLauncher(),
    )
  return output.resolve("daemon-launch.json").also {
    it.writeText(DaemonLaunchBuilder.encode(descriptor))
  }
}

/** The `java` executable this CLI runs on, or null when it cannot be found. */
internal fun currentJavaLauncher(javaHome: String? = System.getProperty("java.home")): String? {
  val home = javaHome?.let(::File) ?: return null
  return listOf("bin/java", "bin/java.exe")
    .map { home.resolve(it) }
    .firstOrNull { it.isFile && it.canExecute() }
    ?.absolutePath
}

internal fun prepareBazelDesktopDaemon(
  artifacts: ResolvedBazelArtifacts,
  prepared: PreparedBazelPreviews,
  root: File,
  pngOnly: Boolean = false,
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
        ) + NetworkPolicy.daemonSystemProperties() + pngOnlySystemProperties(pngOnly),
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
