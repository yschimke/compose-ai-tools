package ee.schimke.composeai.cli

import ee.schimke.composeai.buildartifacts.protocol.AndroidBuildArtifacts
import ee.schimke.composeai.buildartifacts.protocol.BuildArtifacts
import java.io.File
import java.net.URI
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/** Local CLI configuration, not a JVM plugin or a dependency on an IDE's aspect SDK. */
@Serializable
internal data class BazelBuildConfig(
  val schemaVersion: Int = 1,
  val target: String? = null,
  /**
   * Labels and target patterns (`//app/...`, `//pkg:all`, `-//app/legacy/...`) selecting several
   * targets, as on a `bazel build` command line. `--target` flags replace them.
   */
  val targets: List<String> = emptyList(),
  /**
   * The `kind()` regex a pattern's matches are filtered by: rules the configured aspect can read.
   * Defaults to JVM rules, or Android library rules for an Android aspect ([defaultTargetKinds]).
   */
  val targetKinds: String? = null,
  val buildCommand: List<String> = listOf("bazel"),
  val startupArgs: List<String> = emptyList(),
  /** Empty by default: not every Bazel-family tool, version or wrapper accepts the same flags. */
  val buildArgs: List<String> = emptyList(),
  val aspect: String = DEFAULT_BAZEL_ASPECT,
  /**
   * When false, the default aspect is never copied into the source tree; a missing package fails
   * and asks for a configured `aspect` label instead.
   */
  val installAspect: Boolean = true,
  val metadataOutputGroup: String = "compose_preview_metadata",
  val runtimeOutputGroup: String = "compose_preview_runtime",
)

internal const val DEFAULT_BAZEL_ASPECT =
  "//.compose-preview/bazel:runtime.bzl%compose_preview_aspect"

/**
 * Producer contract for an aspect or an external build wrapper; paths are execution-root relative.
 * The shape is compose-preview-contracts' `build-artifacts-protocol`; resolving it is ours.
 */
internal typealias BazelArtifacts = BuildArtifacts

/** Builds [BazelArtifacts]; [schemaVersion] follows from whether [android] is set. */
internal fun BazelArtifacts(
  target: String,
  configuration: String,
  projectJars: List<String>,
  runtimeJars: List<String>,
  sourceFiles: List<String> = emptyList(),
  dataFiles: List<String> = emptyList(),
  android: AndroidBuildArtifacts? = null,
): BazelArtifacts =
  BuildArtifacts.Builder(target, configuration, projectJars, runtimeJars)
    .apply {
      this.sourceFiles = sourceFiles
      this.dataFiles = dataFiles
      this.android = android
    }
    .build()

internal fun BazelArtifacts.resolve(
  executionRoot: File,
  workspaceRoot: File? = null,
): ResolvedBazelArtifacts {
  when (schemaVersion) {
    BuildArtifacts.SCHEMA_VERSION_JVM ->
      require(android == null) { "schemaVersion 1 artifacts cannot carry an android block" }
    BuildArtifacts.SCHEMA_VERSION_ANDROID ->
      require(android != null) { "schemaVersion 2 artifacts need an android block" }
    else ->
      throw IllegalArgumentException("Unsupported Bazel artifacts schemaVersion $schemaVersion")
  }
  require(target.isNotBlank() && configuration.isNotBlank()) {
    "Missing target/configuration identity"
  }
  require(projectJars.isNotEmpty()) { "No project runtime jars for $target" }
  fun resolvePath(path: String): File {
    require(path.isNotBlank()) { "Empty artifact path" }
    val file = File(path).let { if (it.isAbsolute) it else File(executionRoot, path) }.absoluteFile
    require(file.exists()) {
      "Build artifact is not materialized: $file. Request runtime outputs/downloads from the build system."
    }
    return file
  }
  fun resolvePaths(paths: List<String>): List<File> = paths.distinct().map(::resolvePath)
  val project = resolvePaths(projectJars)
  val runtime = resolvePaths(runtimeJars)
  require(project.all { it.isFile && it.extension == "jar" }) {
    "projectJars must contain compiled application jars"
  }
  require(runtime.all { it.isFile && it.extension == "jar" }) {
    "runtimeJars must contain JVM runtime jars"
  }
  require(project.all { it in runtime }) { "runtimeJars must include every project jar" }
  val resolvedAndroid = android?.let { block ->
    require(block.packageName.isNotBlank()) { "android.packageName is required" }
    // Placed ahead of every library's non-final R classes by the launcher, whether or not the
    // producer also listed it in runtimeJars.
    val rJar = block.rJar?.let(::resolvePath)
    ResolvedAndroidArtifacts(
      packageName = block.packageName,
      resourceApk =
        resolvePath(block.resourceApk).also {
          require(it.isFile) { "android.resourceApk is not a file: $it" }
        },
      manifest =
        resolvePath(block.manifest).also {
          require(it.isFile) { "android.manifest is not a file: $it" }
        },
      rJar = rJar,
      compileSdk = block.compileSdk,
      // Only a merge uses these, so a missing one keeps this target on its own APK rather than
      // failing it: a merge then reports that the link inputs are missing.
      link =
        block.link?.let { link ->
          runCatching {
            ResolvedAndroidLink(
              script = resolvePath(link.script),
              aapt2 = resolvePath(link.aapt2),
              javaHome = resolvePath(link.javaHome),
              androidJar = resolvePath(link.androidJar),
              packages = link.packages,
              compiledResources = resolvePaths(link.compiledResources),
              assets = link.assets.map { it.path to resolvePath(it.file) },
            )
          }
            .onFailure {
              System.err.println("[bazel] $target: link inputs unusable: ${it.message}")
            }
            .getOrNull()
        },
    )
  }
  return ResolvedBazelArtifacts(
    this,
    project,
    runtime,
    resolvePaths(sourceFiles).mapIndexed { index, file ->
      val path = sourceFiles.distinct()[index]
      val local = workspaceRoot?.resolve(path)
      if (
        !File(path).isAbsolute &&
          !path.startsWith("bazel-out/") &&
          !path.startsWith("external/") &&
          !path.startsWith("../") &&
          local?.isFile == true
      )
        local.absoluteFile
      else file
    },
    resolvePaths(dataFiles),
    resolvedAndroid,
  )
}

/** The [AndroidBuildArtifacts] block with every path resolved and materialized. */
internal data class ResolvedAndroidArtifacts(
  val packageName: String,
  val resourceApk: File,
  val manifest: File,
  val rJar: File?,
  val compileSdk: Int?,
  /** What [resourceApk] was linked from, so several targets can be linked into one APK. */
  val link: ResolvedAndroidLink? = null,
)

internal data class ResolvedBazelArtifacts(
  val metadata: BazelArtifacts,
  val projectJars: List<File>,
  val runtimeJars: List<File>,
  val sourceFiles: List<File>,
  val dataFiles: List<File>,
  val android: ResolvedAndroidArtifacts? = null,
)

internal val bazelJson = Json {
  prettyPrint = true
  encodeDefaults = true
  ignoreUnknownKeys = true
}

/**
 * Files avoid pipe-buffer deadlocks. A deadline or parent shutdown also tears down child processes.
 */
internal object BazelProcess {
  fun run(argv: List<String>, directory: File, timeoutSeconds: Long): String {
    require(argv.isNotEmpty() && argv.first().isNotBlank()) {
      "buildCommand must be a nonempty argv array"
    }
    require(timeoutSeconds > 0) { "--timeout must be positive" }
    val output = Files.createTempFile("compose-preview-build-", ".log").toFile()
    try {
      val process =
        ProcessBuilder(argv)
          .directory(directory)
          .redirectOutput(output)
          .redirectError(ProcessBuilder.Redirect.INHERIT)
          .start()
      process.outputStream.close()
      fun stop() {
        val descendants = process.descendants().use { it.toList() }
        descendants.reversed().forEach { it.destroy() }
        process.destroy()
        if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly()
        descendants.reversed().filter { it.isAlive }.forEach { it.destroyForcibly() }
      }
      val hook = Thread { runCatching { stop() } }
      Runtime.getRuntime().addShutdownHook(hook)
      try {
        check(process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
          "Build command timed out after ${timeoutSeconds}s"
        }
        check(process.exitValue() == 0) {
          "Build command exited ${process.exitValue()}: ${output.readText().takeLast(8000)}"
        }
        return output.readText()
      } catch (e: InterruptedException) {
        stop()
        Thread.currentThread().interrupt()
        throw IllegalStateException("Build command cancelled", e)
      } finally {
        if (process.isAlive) stop()
        runCatching { Runtime.getRuntime().removeShutdownHook(hook) }
      }
    } finally {
      output.delete()
    }
  }
}

/**
 * Resolve only the selected target's metadata output group, including forward-referenced sets.
 * Non-`file` URIs (remote caches, `bytestream://`) resolve through `pathPrefix` + `name` against
 * [executionRoot] when the output was materialized there.
 */
internal fun bazelMetadataFile(
  events: String,
  target: String,
  outputGroup: String,
  executionRoot: File? = null,
): File = bazelMetadataFile(events.lineSequence(), target, outputGroup, executionRoot)

private fun bazelMetadataFile(
  events: Sequence<String>,
  target: String,
  outputGroup: String,
  executionRoot: File?,
): File {
  val files = bazelMetadataFiles(events, target, outputGroup, executionRoot)
  check(files.size == 1) {
    "Expected one '$outputGroup' metadata file for $target, found ${files.size}. Select one configured JVM target or use --artifacts."
  }
  return files.single().also { check(it.isFile) { "Metadata output missing: $it" } }
}

/** Every [outputGroup] file [target] produced; empty when the aspect reported nothing for it. */
internal fun bazelMetadataFiles(
  events: Sequence<String>,
  target: String,
  outputGroup: String,
  executionRoot: File?,
): List<File> {
  val sets = mutableMapOf<String, JsonObject>()
  val roots = mutableListOf<String>()
  // Stream the build log; retain only artifact sets and the selected completion's group.
  // Large workspace action/progress events need not be kept in memory.
  events
    .filter { it.isNotBlank() }
    .forEach { line ->
      val event = bazelJson.parseToJsonElement(line) as JsonObject
      val id = event["id"] as? JsonObject ?: return@forEach
      val setId = (id["namedSet"] as? JsonObject)?.get("id")?.jsonPrimitive?.contentOrNull
      if (setId != null) {
        sets[setId] = event["namedSetOfFiles"] as? JsonObject ?: error("Missing namedSetOfFiles")
      }
      val targetCompleted = id["targetCompleted"] as? JsonObject
      if (
        targetCompleted?.get("label")?.jsonPrimitive?.contentOrNull?.let(::normalizeBazelLabel) ==
          normalizeBazelLabel(target)
      ) {
        val completed = event["completed"] as? JsonObject ?: error("Missing target completion")
        check(completed["success"]?.jsonPrimitive?.booleanOrNull == true) {
          "Target did not build successfully: $target"
        }
        (completed["outputGroup"] as? JsonArray).orEmpty().forEach { value ->
          val group = value as JsonObject
          if (group["name"]?.jsonPrimitive?.contentOrNull == outputGroup) {
            roots +=
              (group["fileSets"] as? JsonArray).orEmpty().map {
                (it as JsonObject).getValue("id").jsonPrimitive.content
              }
          }
        }
      }
    }
  val files = linkedSetOf<File>()
  val visited = mutableSetOf<String>()
  fun visit(id: String) {
    if (!visited.add(id)) return
    val set = sets[id] ?: error("BEP references missing named set $id")
    (set["files"] as? JsonArray).orEmpty().forEach { value ->
      val file = value as JsonObject
      val uri = file["uri"]?.jsonPrimitive?.contentOrNull?.let(::URI)
      files +=
        if (uri?.scheme == "file") File(uri)
        else {
          val name = file["name"]?.jsonPrimitive?.contentOrNull
          val prefix = (file["pathPrefix"] as? JsonArray).orEmpty().map { it.jsonPrimitive.content }
          val local =
            if (executionRoot != null && !name.isNullOrBlank())
              (prefix + name).fold(executionRoot) { dir, part -> dir.resolve(part) }
            else null
          check(local?.isFile == true) {
            "Metadata output ${uri ?: name} is not materialized under the execution root" +
              "${local?.let { " ($it)" }.orEmpty()}; materialize it through your build system " +
              "first, e.g. --remote_download_outputs=toplevel"
          }
          local!!
        }
    }
    (set["fileSets"] as? JsonArray).orEmpty().forEach {
      visit((it as JsonObject).getValue("id").jsonPrimitive.content)
    }
  }
  roots.forEach(::visit)
  return files.toList()
}

internal class BazelBuild(
  private val execute: (List<String>, File, Long) -> String = BazelProcess::run
) {
  /**
   * The targets of the nearest package at or above [directory], like `//pkg:all`: every rule there
   * of the aspect's kinds ([BazelBuildConfig.targetKinds]); outside any package, everything below
   * [directory] (`//dir/...`). A configured `target`, then `targets`, wins.
   */
  fun selectTargets(
    root: File,
    directory: File,
    config: BazelBuildConfig,
    timeoutSeconds: Long,
  ): BazelTargetSelection {
    config.target?.let {
      return BazelTargetSelection(it, listOf(it))
    }
    if (config.targets.isNotEmpty()) {
      return BazelTargetSelection(
        config.targets.joinToString(" "),
        expandTargets(root, config.targets, config, timeoutSeconds),
      )
    }
    var packageDirectory: File? = directory.canonicalFile
    while (packageDirectory != null && packageDirectory.toPath().startsWith(root.toPath())) {
      val pkg = packageDirectory
      if (listOf("BUILD.bazel", "BUILD").any { pkg.resolve(it).isFile }) {
        val path = pkg.relativeTo(root).invariantSeparatorsPath
        val kinds = config.targetKinds ?: defaultTargetKinds(config.aspect)
        val targets =
          execute(
              config.buildCommand +
                config.startupArgs +
                listOf("query", "kind('$kinds', //$path:*)", "--output=label"),
              root,
              timeoutSeconds,
            )
            .lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("//") || it.startsWith("@") }
            .distinct()
            .sorted()
            .toList()
        check(targets.isNotEmpty()) {
          "No targets of kind $kinds in //$path. Select --target <label or pattern>, or commit " +
            "target in .compose-preview/bazel.json."
        }
        return BazelTargetSelection(targets.singleOrNull() ?: "//$path:all", targets)
      }
      if (pkg == root) break
      packageDirectory = pkg.parentFile
    }
    // Not inside a package (a workspace root whose BUILD files are all below it): everything under
    // the directory, `//dir/...`, the way a build of the whole tree is usually written.
    val below = directory.canonicalFile.relativeTo(root.canonicalFile).invariantSeparatorsPath
    val pattern = if (below.isEmpty()) "//..." else "//$below/..."
    return BazelTargetSelection(
      pattern,
      expandTargets(root, listOf(pattern), config, timeoutSeconds),
    )
  }

  /**
   * The targets [patterns] select, as `bazel build` reads them: labels, `...`, `:all`, `:*`, and
   * `-` prefixed exclusions. Plain labels alone are returned as given; otherwise `query` expands
   * the patterns, filtered by `kind()` to rules the aspect can read
   * ([BazelBuildConfig.targetKinds]), so a genrule or test under a `...` pattern is not handed to
   * an aspect that would fail on it.
   */
  fun expandTargets(
    root: File,
    patterns: List<String>,
    config: BazelBuildConfig,
    timeoutSeconds: Long,
  ): List<String> {
    if (patterns.size == 1 && !isBazelTargetPattern(patterns.single())) return patterns
    val included = patterns.filterNot { it.startsWith("-") }
    val excluded = patterns.filter { it.startsWith("-") }.map { it.removePrefix("-") }
    require(included.isNotEmpty()) {
      "Target patterns ${patterns.joinToString(" ")} include nothing; add a pattern to subtract from"
    }
    (included + excluded).forEach {
      require((it.startsWith("//") || it.startsWith("@")) && '"' !in it) {
        "Target patterns must be absolute (//pkg/..., @repo//pkg:all): $it"
      }
    }
    // Only patterns are filtered: a label named explicitly is a requirement and keeps its own
    // kind (an alias, a custom rule the configured aspect reads), as it does when named alone.
    val wildcards = included.filter(::isBazelTargetPattern)
    val labels = included.filterNot(::isBazelTargetPattern)
    if (wildcards.isEmpty() && excluded.isEmpty()) return labels.distinct()
    val kinds = config.targetKinds ?: defaultTargetKinds(config.aspect)
    val selected =
      listOfNotNull(
        wildcards
          .takeIf { it.isNotEmpty() }
          ?.joinToString(" + ", prefix = "kind('$kinds', ", postfix = ")") { "\"$it\"" }
      ) + labels.map { "\"$it\"" }
    val expression =
      "(${selected.joinToString(" + ")})" + excluded.joinToString("") { " - \"$it\"" }
    val targets =
      execute(
          config.buildCommand + config.startupArgs + listOf("query", expression, "--output=label"),
          root,
          timeoutSeconds,
        )
        .lineSequence()
        .map { it.trim() }
        .filter { it.startsWith("//") || it.startsWith("@") }
        .distinct()
        .sorted()
        .toList()
    check(targets.isNotEmpty()) {
      "No targets of kind $kinds match ${patterns.joinToString(" ")}; set targetKinds in " +
        ".compose-preview/bazel.json for other rules"
    }
    return targets
  }

  fun collect(
    root: File,
    target: String,
    config: BazelBuildConfig,
    timeoutSeconds: Long,
  ): ResolvedBazelArtifacts = collectAll(root, listOf(target), config, timeoutSeconds).single()

  /**
   * Builds every target in one aspect invocation and resolves each one's artifacts. With
   * [skipWithoutMetadata], a target the aspect reported nothing for (an Android aspect over a
   * JVM-only library, say) is left out instead of failing the selection: a pattern's matches are
   * candidates, an explicitly named target is a requirement.
   */
  fun collectAll(
    root: File,
    targets: List<String>,
    config: BazelBuildConfig,
    timeoutSeconds: Long,
    skipWithoutMetadata: Boolean = false,
  ): List<ResolvedBazelArtifacts> {
    require(config.schemaVersion == 1) {
      "Unsupported build config schemaVersion ${config.schemaVersion}"
    }
    require(config.buildCommand.isNotEmpty() && config.buildCommand.first().isNotBlank()) {
      "buildCommand must be a nonempty argv array"
    }
    require(targets.isNotEmpty()) { "No targets selected" }
    targets.forEach { target ->
      require(target.startsWith("//") || target.startsWith("@")) {
        "--target must be an absolute Bazel label"
      }
      require(!isBazelTargetPattern(target)) { "Select one JVM target, not a target pattern" }
    }
    require(
      config.aspect.isNotBlank() &&
        config.metadataOutputGroup.isNotBlank() &&
        config.runtimeOutputGroup.isNotBlank()
    ) {
      "Aspect and output groups must be configured"
    }
    val invocation = config.buildCommand + config.startupArgs
    val selectedTargets = targets.map { target ->
      if (target.startsWith("@") && !target.startsWith("@@") && !target.startsWith("@//")) {
        val canonical =
          execute(
              invocation +
                listOf("cquery") +
                config.buildArgs +
                listOf(
                  target,
                  "--output=starlark",
                  "--starlark:expr=str(target.label)",
                ),
              root,
              timeoutSeconds,
            )
            .lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("//") || it.startsWith("@") }
            .distinct()
            .toList()
        check(canonical.size == 1) { "Expected one configured external target label for $target" }
        target to canonical.single()
      } else target to normalizeBazelLabel(target)
    }
    val bep = Files.createTempFile("compose-preview-bep-", ".json").toFile()
    try {
      execute(
        invocation +
          listOf("build") +
          config.buildArgs +
          listOf(
            "--aspects=${config.aspect}",
            "--output_groups=${config.metadataOutputGroup},${config.runtimeOutputGroup}",
            "--build_event_json_file=${bep.absolutePath}",
          ) +
          selectedTargets.map { it.second },
        root,
        timeoutSeconds,
      )
      val executionRoot =
        execute(invocation + listOf("info", "execution_root"), root, timeoutSeconds).trim()
      check(executionRoot.isNotBlank() && File(executionRoot).isDirectory) {
        "Build tool returned an invalid execution_root: $executionRoot"
      }
      return selectedTargets.mapNotNull { (target, selectedTarget) ->
        val metadata =
          if (skipWithoutMetadata) {
            val files =
              bep.bufferedReader().use {
                bazelMetadataFiles(
                  it.lineSequence(),
                  selectedTarget,
                  config.metadataOutputGroup,
                  File(executionRoot),
                )
              }
            if (files.isEmpty()) {
              System.err.println("[bazel] $selectedTarget: no preview metadata, skipped")
              return@mapNotNull null
            }
            check(files.size == 1) {
              "Expected one '${config.metadataOutputGroup}' metadata file for $selectedTarget, " +
                "found ${files.size}"
            }
            files.single()
          } else
            bep.bufferedReader().use {
              bazelMetadataFile(
                it.lineSequence(),
                selectedTarget,
                config.metadataOutputGroup,
                File(executionRoot),
              )
            }
        val artifacts = bazelJson.decodeFromString<BazelArtifacts>(metadata.readText())
        // Main-repository labels may be emitted with @@ canonical spelling by newer Bazel.
        require(normalizeBazelLabel(artifacts.target) == normalizeBazelLabel(selectedTarget)) {
          "Metadata target ${artifacts.target} does not match $target"
        }
        artifacts.resolve(File(executionRoot), root)
      }
    } finally {
      bep.delete()
    }
  }
}

/**
 * What was asked for ([label]: a label, a package's `//pkg:all`, or the patterns as written) and
 * the targets it selects.
 */
internal data class BazelTargetSelection(val label: String, val targets: List<String>)

/**
 * The selected targets as one module, so a browsed set shows on one page, rendered by one daemon:
 * every target's own jars are discovered, on the union of their runtime classpaths (in order, first
 * occurrence wins). Bazel resolves a workspace's dependencies to one version per configuration, so
 * the union does not mix versions. [label] names it, and rebuilding it means selecting it again.
 *
 * Android targets are linked into one resource APK by [linkAndroid] ([planAndroidMerge]): the
 * union's R classes then carry that APK's IDs in every package, so each target's own final-ID R jar
 * leaves the classpath and the merged one leads it.
 *
 * Null when there is one target, or [bazelMergeBlocker] gives a reason they cannot share a daemon.
 */
internal fun mergeBazelArtifacts(
  label: String,
  artifacts: List<ResolvedBazelArtifacts>,
  linkAndroid: ((AndroidMergePlan) -> ResolvedAndroidArtifacts)? = null,
): ResolvedBazelArtifacts? {
  if (artifacts.size < 2 || bazelMergeBlocker(artifacts) != null) return null
  val plan = planAndroidMerge(artifacts)
  val android =
    if (artifacts.first().android == null) null
    else linkAndroid?.invoke(checkNotNull(plan)) ?: return null
  val replaced = plan?.replacedRJars.orEmpty()
  // Resolved files line up with their metadata paths (each list is resolved from the distinct
  // paths in order), so pairs keep the two in step and dedupe by path.
  fun merged(
    files: (ResolvedBazelArtifacts) -> List<File>,
    paths: (BazelArtifacts) -> List<String>,
  ): Pair<List<File>, List<String>> {
    val pairs =
      artifacts
        .flatMap { files(it).zip(paths(it.metadata).distinct()) }
        .distinctBy { (_, path) -> path }
        .filterNot { (file, _) -> file in replaced }
    return pairs.map { it.first } to pairs.map { it.second }
  }
  val project = merged({ it.projectJars }, { it.projectJars })
  val runtime = merged({ it.runtimeJars }, { it.runtimeJars })
  val sources = merged({ it.sourceFiles }, { it.sourceFiles })
  val data = merged({ it.dataFiles }, { it.dataFiles })
  return ResolvedBazelArtifacts(
    metadata =
      BazelArtifacts(
        label,
        artifacts.first().metadata.configuration,
        project.second,
        // The launcher puts the merged R jar first; listing it keeps the metadata a whole
        // classpath.
        listOfNotNull(android?.rJar?.absolutePath) + runtime.second,
        sources.second,
        data.second,
        android =
          android?.let {
            AndroidBuildArtifacts.Builder(
                it.packageName,
                it.resourceApk.absolutePath,
                it.manifest.absolutePath,
              )
              .apply {
                rJar = it.rJar?.absolutePath
                compileSdk = it.compileSdk
              }
              .build()
          },
      ),
    projectJars = project.first,
    runtimeJars = listOfNotNull(android?.rJar) + runtime.first,
    sourceFiles = sources.first,
    dataFiles = data.first,
    android = android,
  )
}

/**
 * Why [artifacts] cannot be one module: Android and JVM targets render on different runtimes; an
 * Android target whose aspect predates `android.link` reports only its own linked APK, and
 * Robolectric loads one; targets from different configurations were compiled against different
 * dependency sets.
 */
internal fun bazelMergeBlocker(artifacts: List<ResolvedBazelArtifacts>): String? {
  val android = artifacts.count { it.android != null }
  return when {
    artifacts.size < 2 -> null
    android in 1 until artifacts.size -> "they mix Android and JVM targets"
    android > 0 && artifacts.any { it.android?.link == null } ->
      "their Android aspect does not report link inputs (rules_compose_preview with android.link)"
    artifacts.map { it.metadata.configuration }.distinct().size > 1 ->
      "they were built in different configurations"
    else -> null
  }
}

/** `...`, `:all`, `:*`, `:all-targets`, or a `-` exclusion: selects targets, never names one. */
internal fun isBazelTargetPattern(target: String): Boolean =
  target.startsWith("-") ||
    target.endsWith("...") ||
    "...:" in target ||
    target.endsWith(":all") ||
    target.endsWith(":*") ||
    target.endsWith(":all-targets")

private const val JVM_TARGET_KINDS = "(kt_jvm_library|kt_jvm_binary|java_library|java_binary)"
private const val ANDROID_TARGET_KINDS = "(kt_android_library|android_library)"

/** JVM library and binary rules, or Android library rules when [aspect] is an Android one. */
internal fun defaultTargetKinds(aspect: String): String =
  if ("android" in aspect.substringAfterLast('%') || aspect.contains("android.bzl"))
    ANDROID_TARGET_KINDS
  else JVM_TARGET_KINDS

/** Expand package shorthand and normalize the equivalent main/canonical repository spellings. */
internal fun normalizeBazelLabel(label: String): String {
  val repository = label.substringBefore("//", "").trimStart('@')
  val body = label.substringAfter("//", label)
  val expanded = if (':' in body) body else "$body:${body.substringAfterLast('/')}"
  return (if (repository.isBlank()) "" else "@@$repository") + "//" + expanded
}
