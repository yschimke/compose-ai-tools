package ee.schimke.composeai.cli

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
 */
@Serializable
internal data class BazelArtifacts(
  val schemaVersion: Int,
  val target: String,
  val configuration: String,
  val projectJars: List<String>,
  val runtimeJars: List<String>,
  val sourceFiles: List<String> = emptyList(),
  val dataFiles: List<String> = emptyList(),
) {
  fun resolve(executionRoot: File, workspaceRoot: File? = null): ResolvedBazelArtifacts {
    require(schemaVersion == 1) { "Unsupported Bazel artifacts schemaVersion $schemaVersion" }
    require(target.isNotBlank() && configuration.isNotBlank()) {
      "Missing target/configuration identity"
    }
    require(projectJars.isNotEmpty()) { "No project runtime jars for $target" }
    fun resolvePaths(paths: List<String>): List<File> =
      paths.distinct().map { path ->
        require(path.isNotBlank()) { "Empty artifact path" }
        val file =
          File(path).let { if (it.isAbsolute) it else File(executionRoot, path) }.absoluteFile
        require(file.exists()) {
          "Build artifact is not materialized: $file. Request runtime outputs/downloads from the build system."
        }
        file
      }
    val project = resolvePaths(projectJars)
    val runtime = resolvePaths(runtimeJars)
    require(project.all { it.isFile && it.extension == "jar" }) {
      "projectJars must contain compiled application jars"
    }
    require(runtime.all { it.isFile && it.extension == "jar" }) {
      "runtimeJars must contain JVM runtime jars"
    }
    require(project.all { it in runtime }) { "runtimeJars must include every project jar" }
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
    )
  }
}

internal data class ResolvedBazelArtifacts(
  val metadata: BazelArtifacts,
  val projectJars: List<File>,
  val runtimeJars: List<File>,
  val sourceFiles: List<File>,
  val dataFiles: List<File>,
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
): File {
  return bazelMetadataFile(events.lineSequence(), target, outputGroup, executionRoot)
}

private fun bazelMetadataFile(
  events: Sequence<String>,
  target: String,
  outputGroup: String,
  executionRoot: File?,
): File {
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
  check(files.size == 1) {
    "Expected one '$outputGroup' metadata file for $target, found ${files.size}. Select one configured JVM target or use --artifacts."
  }
  return files.single().also { check(it.isFile) { "Metadata output missing: $it" } }
}

internal class BazelBuild(
  private val execute: (List<String>, File, Long) -> String = BazelProcess::run
) {
  fun selectTarget(
    root: File,
    directory: File,
    config: BazelBuildConfig,
    timeoutSeconds: Long,
  ): String {
    config.target?.let {
      return it
    }
    var packageDirectory: File? = directory.canonicalFile
    while (packageDirectory != null && packageDirectory.toPath().startsWith(root.toPath())) {
      val pkg = packageDirectory
      if (listOf("BUILD.bazel", "BUILD").any { pkg.resolve(it).isFile }) {
        val path = pkg.relativeTo(root).invariantSeparatorsPath
        val expression =
          "kind('(kt_jvm_library|kt_jvm_binary|java_library|java_binary)', //$path:*)"
        val targets =
          execute(
              config.buildCommand +
                config.startupArgs +
                listOf("query", expression, "--output=label"),
              root,
              timeoutSeconds,
            )
            .lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("//") || it.startsWith("@") }
            .distinct()
            .sorted()
            .toList()
        check(targets.size == 1) {
          "${if (targets.isEmpty()) "No JVM targets" else "Multiple JVM targets: ${targets.joinToString()}"} in //$path. Select --target <label>, or commit target in .compose-preview/bazel.json."
        }
        return targets.single()
      }
      if (pkg == root) break
      packageDirectory = pkg.parentFile
    }
    error(
      "No BUILD file found below $root; select --target or configure target in .compose-preview/bazel.json"
    )
  }

  fun collect(
    root: File,
    target: String,
    config: BazelBuildConfig,
    timeoutSeconds: Long,
  ): ResolvedBazelArtifacts {
    require(config.schemaVersion == 1) {
      "Unsupported build config schemaVersion ${config.schemaVersion}"
    }
    require(config.buildCommand.isNotEmpty() && config.buildCommand.first().isNotBlank()) {
      "buildCommand must be a nonempty argv array"
    }
    require(target.startsWith("//") || target.startsWith("@")) {
      "--target must be an absolute Bazel label"
    }
    require(!target.contains("...") && !target.endsWith(":all") && !target.endsWith(":*")) {
      "Select one JVM target, not a target pattern"
    }
    require(
      config.aspect.isNotBlank() &&
        config.metadataOutputGroup.isNotBlank() &&
        config.runtimeOutputGroup.isNotBlank()
    ) {
      "Aspect and output groups must be configured"
    }
    val invocation = config.buildCommand + config.startupArgs
    val selectedTarget =
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
        canonical.single()
      } else normalizeBazelLabel(target)
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
            selectedTarget,
          ),
        root,
        timeoutSeconds,
      )
      val executionRoot =
        execute(invocation + listOf("info", "execution_root"), root, timeoutSeconds).trim()
      check(executionRoot.isNotBlank() && File(executionRoot).isDirectory) {
        "Build tool returned an invalid execution_root: $executionRoot"
      }
      val metadata =
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
      return artifacts.resolve(File(executionRoot), root)
    } finally {
      bep.delete()
    }
  }
}

/** Expand package shorthand and normalize the equivalent main/canonical repository spellings. */
internal fun normalizeBazelLabel(label: String): String {
  val repository = label.substringBefore("//", "").trimStart('@')
  val body = label.substringAfter("//", label)
  val expanded = if (':' in body) body else "$body:${body.substringAfterLast('/')}"
  return (if (repository.isBlank()) "" else "@@$repository") + "//" + expanded
}
