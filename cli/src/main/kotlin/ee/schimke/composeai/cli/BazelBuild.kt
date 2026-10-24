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
import okio.Path.Companion.toOkioPath

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
   * Buck2: a regex over rule names, default [DEFAULT_BUCK_TARGET_KINDS].
   */
  val targetKinds: String? = null,
  val buildCommand: List<String> = listOf("bazel"),
  val startupArgs: List<String> = emptyList(),
  /** Empty by default: not every Bazel-family tool, version or wrapper accepts the same flags. */
  val buildArgs: List<String> = emptyList(),
  /**
   * Arguments `bazel query` gets, after `query`, when a pattern or a directory is expanded. Null
   * forwards the Starlark language flags in [buildArgs] ([bazelQueryLanguageArgs]): a `query` run
   * without them changes Bazel's `BuildLanguageOptions`, and Bazel discards its analysis cache once
   * for the query and again for the build that follows (#6175).
   */
  val queryArgs: List<String>? = null,
  val aspect: String = DEFAULT_BAZEL_ASPECT,
  /**
   * Where the installed Android aspect's shims load their providers from, for a deployment whose
   * Java or Android rules live elsewhere: `JavaInfo`, `JavaRuntimeInfo`, `AndroidIdeInfo`,
   * `StarlarkAndroidResourcesInfo` name a `.bzl` (or `file.bzl%ExportedName`); `JDK_RUNTIME` and
   * `ANDROID_SDK_TOOLCHAIN_TYPE` name a label. Unset ones keep the rules' defaults
   * ([bazelProviderShim]).
   */
  val providers: Map<String, String> = emptyMap(),
  /** Buck2 only: the BXL entry point that prints the artifacts documents ([DEFAULT_BUCK_BXL]). */
  val bxl: String = DEFAULT_BUCK_BXL,
  /**
   * When false, the default aspect (Buck2: the default BXL script) is never copied into the source
   * tree; a missing one fails and asks for a configured `aspect` (`bxl`) label instead.
   */
  val installAspect: Boolean = true,
  /**
   * Link a selection of Android targets into one module on one resource APK ([planAndroidMerge]).
   * Off by default: each library then renders exactly as it does alone, on its own APK, in its own
   * module; merged, sibling libraries share one resource table, so where two define the same
   * resource the later wins for both.
   */
  val mergeAndroid: Boolean = false,
  /**
   * The API level Android targets render on, overriding the aspect's `android.compileSdk` and the
   * CLI's own detection: for an SDK provider that exposes no level and an `android.jar` that is a
   * generated build output.
   */
  val androidCompileSdk: Int? = null,
  /**
   * Pins the wall clock an Android target's own code reads (compose-ai-tools#5945): classes under
   * [appClockPackages] that call `System.currentTimeMillis()` get the render's fixed instant (the
   * daemon's `composeai.render.fixedTime`, default `10:10`; `off` still gives the host clock). On
   * by default, as the Gradle plugin's `composePreview.pinAppClock` is. It has Robolectric
   * instrument the target's own classes, so `"pinAppClock": false` is the escape hatch when a
   * preview misbehaves under that.
   */
  val pinAppClock: Boolean = true,
  /**
   * Class-name prefixes [pinAppClock] treats as the target's own code, matched with `startsWith`.
   * Null defaults to the Android target's `packageName` plus `"."` ([bazelClockPackages]).
   */
  val appClockPackages: List<String>? = null,
  /**
   * How a bundle carries each runtime jar (#6016): `ee.schimke.composeai.bundle.producer
   * .DependencyPolicy.parse`'s shape. Null keeps the original behaviour exactly.
   */
  val dependencyPolicy: JsonObject? = null,
  /**
   * Keep the render daemon warm between `render` / `show` runs ([BazelKeepDaemon], #6104), as
   * `--keep-daemon` or `COMPOSE_PREVIEW_KEEP_DAEMON=1` do. Off by default: a CI run should leave
   * nothing behind.
   */
  val keepDaemon: Boolean = false,
  /** How long a kept daemon waits after its last render before it exits. */
  val daemonIdleTimeoutSeconds: Int = 300,
  /**
   * The extended attribute (`user.` optional) a content-addressed output filesystem publishes each
   * file's content digest under, keying the host cache's per-jar memos on it rather than on mtime,
   * ctime and inode, which such a filesystem reassigns on every build (#6104; [FileStamper]).
   * `$COMPOSE_PREVIEW_BAZEL_DIGEST_XATTR` overrides it. Null (the default) never reads one.
   */
  val digestXattr: String? = null,
  val metadataOutputGroup: String = "compose_preview_metadata",
  val runtimeOutputGroup: String = "compose_preview_runtime",
  /**
   * The aspect's group holding a target's own jars alone (#6162). Built first, so a selected target
   * whose jars reference no Preview annotation ([BazelPreviewJars]) is dropped before its resources
   * are linked or its runtime closure built. An aspect without it (rules before the group) leaves
   * every target in; null or blank skips that first build.
   */
  val projectOutputGroup: String? = "compose_preview_project",
)

/**
 * The prefixes `composeai.render.clockPackages` names for [android] under [config]'s
 * [BazelBuildConfig.pinAppClock]: the configured [BazelBuildConfig.appClockPackages], else the
 * target's `packageName` with a trailing `.` plus [WEAR_COMPOSE_CLOCK_PACKAGE]. Empty when pinning
 * is off (`"pinAppClock": false`) or for a JVM target. The daemon drops any prefix overlapping the
 * JDK, Kotlin, Robolectric, the framework or itself.
 */
internal fun bazelClockPackages(
  config: BazelBuildConfig,
  android: ResolvedAndroidArtifacts?,
): List<String> {
  if (!config.pinAppClock || android == null) return emptyList()
  val default =
    android.packageName.trim().let { if (it.isEmpty()) emptyList() else listOf("$it.") } +
      WEAR_COMPOSE_CLOCK_PACKAGE
  // Never an empty (or dots-only) prefix: it would instrument every class.
  return (config.appClockPackages ?: default)
    .map { it.trim() }
    .filter { it.trimEnd('.').isNotEmpty() }
    .distinct()
}

/**
 * Pinned by default alongside the target's own package: Wear Compose's `TimeText` reads the clock
 * inside `androidx.wear.compose` (`materialcore.ResourcesKt.currentTimeMillis`), never in the app's
 * package, so a Wear preview otherwise renders the host's wall-clock time.
 */
internal const val WEAR_COMPOSE_CLOCK_PACKAGE = "androidx.wear.compose."

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
  compileSdkOverride: Int? = null,
  /** The raw `runtimeJarOwners` ([bazelRuntimeJarOwners]); empty when the aspect predates it. */
  runtimeJarOwners: Map<String, String> = emptyMap(),
  /**
   * Memoises pruning's per-jar reads and android.jar's level across runs; null reads every time.
   */
  hostCache: BazelHostCache? = BazelHostCache.shared(),
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
  /**
   * [path] in the execution root, or, for a file the CLI itself installed under `.compose-preview/`
   * (the Android aspect's link.sh), in the workspace: an execution root holds a source file only
   * when the build planted its package there (compose-ai-tools#5945). Nothing else falls back, so a
   * stale checkout file cannot stand in for a build output that was not materialized.
   */
  fun resolvePath(path: String): File {
    require(path.isNotBlank()) { "Empty artifact path" }
    val inRoot =
      File(path).let { if (it.isAbsolute) it else File(executionRoot, path) }.absoluteFile
    val file =
      inRoot.takeIf { it.exists() || !path.startsWith(".compose-preview/") }
        ?: workspaceRoot?.resolve(path)?.absoluteFile?.takeIf { it.exists() }
        ?: inRoot
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
    // Only a merge uses these, so a missing one keeps this target on its own APK rather than
    // failing it: a merge then reports that the link inputs are missing.
    val link =
      block.link?.let { link ->
        runCatching {
          ResolvedAndroidLink(
            script = resolvePath(link.script),
            aapt2 = resolvePath(link.aapt2),
            javaHome = resolvePath(link.javaHome),
            androidJar = resolvePath(link.androidJar),
            // A path is a library manifest standing for the package it declares (an AAR's,
            // which rules_android does not report); link.sh reads it, from any directory.
            packages =
              link.packages.map {
                val rootManifest = it.endsWith(".xml") && File(executionRoot, it).isFile
                if ('/' in it || rootManifest) resolvePath(it).path else it
              },
            compiledResources = resolvePaths(link.compiledResources),
            assets = link.assets.map { it.path to resolvePath(it.file) },
          )
        }
          .onFailure { System.err.println("[bazel] $target: link inputs unusable: ${it.message}") }
          .getOrNull()
      }
    // The aspect omits compileSdk when the target's android.jar path names no `android-<N>`; the
    // SDK platform that jar belongs to, or failing that the jar itself, still says which level it
    // is. A configured level overrides all three.
    // Resolved on its own, so the jar survives a link block whose other inputs are unusable.
    val androidJar =
      link?.androidJar
        ?: block.link?.androidJar?.let { runCatching { resolvePath(it) }.getOrNull() }
    val detected =
      compileSdkOverride?.let {
        AndroidPlatformLevel.Detected(it, "androidCompileSdk in bazel.json")
      }
        ?: block.compileSdk?.let { AndroidPlatformLevel.Detected(it, "the aspect") }
        ?: androidJar?.let { AndroidPlatformLevel.detect(it, hostCache) }
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
      compileSdk = detected?.level,
      compileSdkSource = detected?.source,
      link = link,
      androidJar = androidJar,
    )
  }
  val pruned = pruneRuntimeClasspath(runtime, project.toSet(), resolvedAndroid?.rJar, hostCache)
  if (pruned.empty.isNotEmpty() || pruned.supersededRJars.isNotEmpty()) {
    System.err.println(
      "[bazel] $target: ${pruned.jars.size} of ${runtime.size} runtime jars on the classpath " +
        "(${pruned.empty.size} empty, ${pruned.supersededRJars.size} R jars the final-ID R jar " +
        "covers)"
    )
  }
  // The metadata lists the same jars, so a merge can still pair each file with its path.
  val kept = pruned.jars.toSet()
  val metadata =
    if (pruned.jars.size == runtime.size) this
    else
      newBuilder()
        .apply {
          runtimeJars =
            this@resolve.runtimeJars
              .distinct()
              .zip(runtime)
              .filter { it.second in kept }
              .map { it.first }
        }
        .build()
  return ResolvedBazelArtifacts(
    metadata,
    project,
    pruned.jars,
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
    runtimeJarOwners =
      runtimeJarOwners.entries
        .mapNotNull { (path, label) ->
          // Owners only identify jars; one that names no listed runtime jar is ignored.
          val file =
            File(path).let { if (it.isAbsolute) it else File(executionRoot, path) }.absoluteFile
          file.takeIf { it in pruned.jars }?.let { it to label }
        }
        .toMap(),
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
  /** What [compileSdk] was read from, for the log line naming the SDK a render uses. */
  val compileSdkSource: String? = null,
  /**
   * The android.jar the target linked against, when the aspect reported it. Kept apart from [link],
   * which a merged target drops and an unusable link input discards whole.
   */
  val androidJar: File? = null,
)

internal data class ResolvedBazelArtifacts(
  val metadata: BazelArtifacts,
  val projectJars: List<File>,
  val runtimeJars: List<File>,
  val sourceFiles: List<File>,
  val dataFiles: List<File>,
  val android: ResolvedAndroidArtifacts? = null,
  /** Runtime jar → producing target label, from the artifacts' optional `runtimeJarOwners`. */
  val runtimeJarOwners: Map<File, String> = emptyMap(),
  /**
   * The labels of the targets merged into these artifacts, whose `metadata.target` is then the
   * selection's label; empty for a single target.
   */
  val memberTargets: List<String> = emptyList(),
)

/**
 * The optional `runtimeJarOwners` of an artifacts document (compose-preview-contracts #189), read
 * from the raw JSON alongside the typed parse so a contracts release without the field, or an
 * aspect that does not emit it, degrades to no owners.
 */
internal fun bazelRuntimeJarOwners(artifactsJson: String): Map<String, String> =
  ((bazelJson.parseToJsonElement(artifactsJson) as? JsonObject)?.get("runtimeJarOwners")
      as? JsonObject)
    .orEmpty()
    .mapNotNull { (path, label) ->
      (label as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull?.let { path to it }
    }
    .toMap()

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
        if (process.exitValue() != 0) {
          throw BazelCommandFailed(
            process.exitValue(),
            "Build command exited ${process.exitValue()}: ${output.readText().takeLast(8000)}",
          )
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

/** A build-tool command that ran and exited [exitCode], not zero. */
internal class BazelCommandFailed(val exitCode: Int, message: String) :
  IllegalStateException(message)

/** Bazel's exit code for a command line it rejects: an unknown option, for one. */
internal const val BAZEL_COMMAND_LINE_ERROR: Int = 2

/**
 * The flags in [buildArgs] that set Bazel's `BuildLanguageOptions` — Starlark semantics, which
 * `query` and `build` must agree on or Bazel discards its analysis cache between them (#6175):
 * `--[no]check_bzl_visibility`, `--[no]incompatible_*`, `--[no]experimental_*` and
 * `--[no]enable_bzlmod` / `--[no]enable_workspace`, in the `=value` form or followed by a value
 * (which may start with `-`; the next token starting `--` is another option). Some `incompatible_`
 * and `experimental_` flags are build-only; [BazelBuild] runs the query again without these when
 * `query` rejects its command line.
 */
internal fun bazelQueryLanguageArgs(buildArgs: List<String>): List<String> {
  fun languageFlag(arg: String): Boolean {
    if (!arg.startsWith("--")) return false
    val name = arg.removePrefix("--").substringBefore('=').removePrefix("no")
    return name == "check_bzl_visibility" ||
      name == "enable_bzlmod" ||
      name == "enable_workspace" ||
      name.startsWith("incompatible_") ||
      name.startsWith("experimental_")
  }
  val out = mutableListOf<String>()
  var i = 0
  while (i < buildArgs.size) {
    val arg = buildArgs[i]
    if (languageFlag(arg)) {
      out += arg
      val value = buildArgs.getOrNull(i + 1)
      // A value may itself start with `-` (`--experimental_builtins_injection_override -foo`);
      // the next option starts with `--`, as every flag in buildArgs does.
      if ('=' !in arg && value != null && !value.startsWith("--")) {
        out += value
        i++
      }
    }
    i++
  }
  return out
}

/**
 * The execution root a build's event stream reports (`workspaceInfo.localExecRoot`, Bazel's
 * `WorkspaceConfig` event), or null when it reports none or names a missing directory: an older or
 * custom Bazel distribution, for which the caller asks `info execution_root` instead.
 */
internal fun bazelEventExecutionRoot(events: Sequence<String>): String? =
  events
    .filter { "\"workspaceInfo\"" in it }
    .firstNotNullOfOrNull { line ->
      runCatching {
        ((bazelJson.parseToJsonElement(line) as JsonObject)["workspaceInfo"] as? JsonObject)
          ?.get("localExecRoot")
          ?.jsonPrimitive
          ?.contentOrNull
      }
        .getOrNull()
    }
    ?.takeIf { it.isNotBlank() && File(it).isDirectory }

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
   * `bazel query [expression] --output=label`, with [BazelBuildConfig.queryArgs], or else the
   * language flags of [BazelBuildConfig.buildArgs] ([bazelQueryLanguageArgs]). Forwarded flags that
   * `query` rejects as a command line are dropped and the query run once more without them.
   */
  private fun query(
    root: File,
    expression: String,
    config: BazelBuildConfig,
    timeoutSeconds: Long,
  ): String {
    val invocation = config.buildCommand + config.startupArgs + listOf("query")
    val explicit = config.queryArgs
    val args = explicit ?: bazelQueryLanguageArgs(config.buildArgs)
    fun run(args: List<String>) =
      execute(invocation + args + listOf(expression, "--output=label"), root, timeoutSeconds)
    return try {
      run(args)
    } catch (e: BazelCommandFailed) {
      if (explicit != null || args.isEmpty() || e.exitCode != BAZEL_COMMAND_LINE_ERROR) throw e
      System.err.println(
        "[bazel] query rejected the language flags from buildArgs (${args.joinToString(" ")}); " +
          "querying without them, which costs Bazel its analysis cache. Set queryArgs in " +
          ".compose-preview/bazel.json to the ones query accepts."
      )
      run(emptyList())
    }
  }

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
          query(root, "kind('$kinds', //$path:*)", config, timeoutSeconds)
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
      query(root, expression, config, timeoutSeconds)
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
    // A pattern's matches whose package sources never mention Preview are not built (#6162);
    // labels named explicitly are kept whatever they hold.
    return BazelPreviewSources(root.toOkioPath()).filter(targets, labels.toSet()) {
      System.err.println(it)
    }
  }

  /**
   * [targets] as (JVM, Android) for an unconfigured aspect, so each kind is built by the aspect
   * that reads it. A target an Android library `exports` is part of that library and left out:
   * rules_kotlin's `kt_android_library` is an `android_library` exporting the `_kt` JVM library and
   * `_base` resources it compiles to. An `alias` is classified by its `actual` target.
   */
  fun splitAndroid(
    root: File,
    targets: List<String>,
    config: BazelBuildConfig,
    timeoutSeconds: Long,
  ): Pair<List<String>, List<String>> {
    fun query(expression: String): Set<String> =
      query(root, expression, config, timeoutSeconds)
        .lineSequence()
        .map { it.trim() }
        .filter { it.startsWith("//") || it.startsWith("@") }
        .map(::normalizeBazelLabel)
        .toSet()
    fun set(labels: Collection<String>) =
      labels.joinToString(" ", prefix = "set(", postfix = ")") { "\"$it\"" }
    val all = set(targets)
    // Only a label named explicitly can be an alias: a pattern's matches are kind-filtered.
    val aliasedAndroid =
      query("kind('^alias rule$', $all)").filter {
        query("kind('$ANDROID_TARGET_KINDS', labels(actual, \"$it\"))").isNotEmpty()
      }
    val android = query("kind('$ANDROID_TARGET_KINDS', $all)") + aliasedAndroid
    if (android.isEmpty()) return targets to emptyList()
    val exported = query("$all intersect labels(exports, ${set(android)})")
    val kept = targets.filterNot { normalizeBazelLabel(it) in exported }
    return kept.partition { normalizeBazelLabel(it) !in android }
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
    val candidates =
      config.projectOutputGroup
        ?.takeIf { it.isNotBlank() }
        ?.let { group -> previewCandidates(root, selectedTargets, config, group, timeoutSeconds) }
        ?: selectedTargets
    if (candidates.isEmpty()) return emptyList()
    val bep = Files.createTempFile("compose-preview-bep-", ".json").toFile()
    try {
      val executionRoot =
        build(
          root,
          config,
          listOf(config.metadataOutputGroup, config.runtimeOutputGroup),
          candidates.map { it.second },
          bep,
          timeoutSeconds,
        )
      return candidates.mapNotNull { (target, selectedTarget) ->
        val metadata =
          if (skipWithoutMetadata) {
            val files =
              bep.bufferedReader().use {
                bazelMetadataFiles(
                  it.lineSequence(),
                  selectedTarget,
                  config.metadataOutputGroup,
                  executionRoot,
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
                executionRoot,
              )
            }
        val artifactsText = metadata.readText()
        val artifacts = bazelJson.decodeFromString<BazelArtifacts>(artifactsText)
        // Main-repository labels may be emitted with @@ canonical spelling by newer Bazel.
        require(normalizeBazelLabel(artifacts.target) == normalizeBazelLabel(selectedTarget)) {
          "Metadata target ${artifacts.target} does not match $target"
        }
        artifacts.resolve(
          executionRoot,
          root,
          config.androidCompileSdk,
          bazelRuntimeJarOwners(artifactsText),
        )
      }
    } finally {
      bep.delete()
    }
  }

  /**
   * Builds [groups] of the configured aspect over [labels], writing the event stream to [bep], and
   * returns the execution root.
   */
  private fun build(
    root: File,
    config: BazelBuildConfig,
    groups: List<String>,
    labels: List<String>,
    bep: File,
    timeoutSeconds: Long,
  ): File {
    val invocation = config.buildCommand + config.startupArgs
    execute(
      invocation +
        listOf("build") +
        config.buildArgs +
        listOf(
          "--aspects=${config.aspect}",
          "--output_groups=${groups.joinToString(",")}",
          "--build_event_json_file=${bep.absolutePath}",
        ) +
        labels,
      root,
      timeoutSeconds,
    )
    // The build's own event stream names its execution root; only a tool that does not report
    // it pays for a second client call (#6104).
    val executionRoot =
      bep.bufferedReader().use { bazelEventExecutionRoot(it.lineSequence()) }
        ?: execute(invocation + listOf("info", "execution_root"), root, timeoutSeconds).trim()
    check(executionRoot.isNotBlank() && File(executionRoot).isDirectory) {
      "Build tool returned an invalid execution_root: $executionRoot"
    }
    return File(executionRoot)
  }

  /**
   * [selected] less the targets whose own jars reference no Preview annotation, read from a build
   * of [projectGroup] alone (#6162): such a target costs its own compilation, not a resource link
   * and its whole runtime closure. A target is kept on any doubt: the aspect reported no jars for
   * it (rules that predate the group, or a target the aspect does not read), they are not on disk
   * (a remote build that did not download them), or one cannot be read.
   */
  private fun previewCandidates(
    root: File,
    selected: List<Pair<String, String>>,
    config: BazelBuildConfig,
    projectGroup: String,
    timeoutSeconds: Long,
  ): List<Pair<String, String>> {
    val bep = Files.createTempFile("compose-preview-bep-", ".json").toFile()
    try {
      val executionRoot =
        build(root, config, listOf(projectGroup), selected.map { it.second }, bep, timeoutSeconds)
      val skipped = mutableListOf<String>()
      val kept = selected.filter { (_, label) ->
        val jars = runCatching {
          bep.bufferedReader().use {
            bazelMetadataFiles(it.lineSequence(), label, projectGroup, executionRoot)
          }
        }
          .getOrNull()
          .orEmpty()
        val keep = jars.isEmpty() || BazelPreviewJars.mayHoldPreviews(jars)
        if (!keep) skipped += label
        keep
      }
      if (skipped.isNotEmpty()) {
        System.err.println(
          "[bazel] no Preview annotation in the jars of ${skipped.joinToString(" ")}; " +
            "skipped before linking resources or building the runtime"
        )
      }
      return kept
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
 * Android targets merge only when [mergeAndroid] asks for it. Null when there is one target, or
 * [bazelMergeBlocker] gives a reason they cannot share a daemon.
 */
internal fun mergeBazelArtifacts(
  label: String,
  artifacts: List<ResolvedBazelArtifacts>,
  mergeAndroid: Boolean = false,
  linkAndroid: ((AndroidMergePlan) -> ResolvedAndroidArtifacts)? = null,
): ResolvedBazelArtifacts? {
  if (artifacts.size < 2 || bazelMergeBlocker(artifacts, mergeAndroid) != null) return null
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
    runtimeJarOwners = artifacts.fold(emptyMap()) { owners, it -> it.runtimeJarOwners + owners },
    memberTargets =
      artifacts.flatMap { it.memberTargets.ifEmpty { listOf(it.metadata.target) } }.distinct(),
  )
}

/**
 * Why [artifacts] cannot be one module: Android and JVM targets render on different runtimes; an
 * Android target whose aspect predates `android.link` reports only its own linked APK, and
 * Robolectric loads one; targets from different configurations were compiled against different
 * dependency sets.
 */
internal fun bazelMergeBlocker(
  artifacts: List<ResolvedBazelArtifacts>,
  mergeAndroid: Boolean = false,
): String? {
  val android = artifacts.count { it.android != null }
  return when {
    artifacts.size < 2 -> null
    android in 1 until artifacts.size -> "they mix Android and JVM targets"
    android > 0 && !mergeAndroid ->
      "Android targets each render on their own resources (set \"mergeAndroid\": true to link one APK)"
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

/**
 * Both: what an unconfigured selection reads, before [BazelBuild.splitAndroid] gives each kind its
 * aspect.
 */
internal const val AUTO_TARGET_KINDS =
  "(kt_jvm_library|kt_jvm_binary|java_library|java_binary|kt_android_library|android_library)"

/** JVM library and binary rules, or Android library rules when [aspect] is an Android one. */
internal fun defaultTargetKinds(aspect: String): String =
  if ("android" in aspect.substringAfterLast('%') || aspect.contains("android.bzl"))
    ANDROID_TARGET_KINDS
  else JVM_TARGET_KINDS

/** rules_compose_preview's own Android aspect, for a module that already depends on it. */
internal const val RULES_ANDROID_ASPECT =
  "@rules_compose_preview//:android.bzl%compose_preview_android_aspect"

/**
 * The Android aspect for an unconfigured workspace, or null when it has none it can load, so an
 * unconfigured selection stays JVM-only as before (a `WORKSPACE`-only project without
 * `MODULE.bazel`):
 *
 * - [RULES_ANDROID_ASPECT] when `MODULE.bazel` declares `rules_compose_preview` (under its
 *   `repo_name`, when it sets one);
 * - else the copy the CLI installs ([DEFAULT_BAZEL_ANDROID_ASPECT]) when configured [providers]
 *   point its shims somewhere, or when the `rules_android` and `rules_java` its shims load by
 *   default are visible under their own names: declared in `MODULE.bazel`, or defined in a
 *   `WORKSPACE` (`WORKSPACE.bazel`).
 */
internal fun detectAndroidAspect(
  root: File,
  providers: Map<String, String> = emptyMap(),
): String? {
  if (providers.isNotEmpty()) return DEFAULT_BAZEL_ANDROID_ASPECT
  val module = root.resolve("MODULE.bazel").takeIf { it.isFile }?.readText().orEmpty()
  fun attribute(call: String, name: String) =
    Regex("""\b$name\s*=\s*"([^"]+)"""").find(call)?.groupValues?.get(1)
  // The repository a root-module bazel_dep is visible as, or null when it is not declared.
  fun declared(name: String): String? =
    Regex("""bazel_dep\(([^)]*)\)""")
      .findAll(module)
      .map { it.groupValues[1] }
      .firstOrNull { attribute(it, "name") == name }
      ?.let { attribute(it, "repo_name") ?: name }
  declared("rules_compose_preview")?.let { repository ->
    return RULES_ANDROID_ASPECT.replaceFirst("@rules_compose_preview//", "@$repository//")
  }
  // A WORKSPACE-based project defines repositories by name (http_archive, local_repository, ...).
  val workspace =
    listOf("WORKSPACE.bazel", "WORKSPACE")
      .map(root::resolve)
      .filter { it.isFile }
      .joinToString("\n") { it.readText() }
  fun visible(name: String) =
    declared(name) == name || Regex("""\bname\s*=\s*"$name"""").containsMatchIn(workspace)
  return if (visible("rules_android") && visible("rules_java")) DEFAULT_BAZEL_ANDROID_ASPECT
  else null
}

/** Expand package shorthand and normalize the equivalent main/canonical repository spellings. */
internal fun normalizeBazelLabel(label: String): String {
  val repository = label.substringBefore("//", "").trimStart('@')
  val body = label.substringAfter("//", label)
  val expanded = if (':' in body) body else "$body:${body.substringAfterLast('/')}"
  return (if (repository.isBlank()) "" else "@@$repository") + "//" + expanded
}
