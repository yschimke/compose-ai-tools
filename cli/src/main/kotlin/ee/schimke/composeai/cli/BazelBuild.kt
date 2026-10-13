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
  val buildCommand: List<String> = listOf("bazel"),
  val startupArgs: List<String> = emptyList(),
  val buildArgs: List<String> = listOf("--remote_download_outputs=all"),
  val aspect: String = "//.compose-preview/bazel:runtime.bzl%compose_preview_aspect",
  val metadataOutputGroup: String = "compose_preview_metadata",
  val runtimeOutputGroup: String = "compose_preview_runtime",
)

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
  fun resolve(executionRoot: File): ResolvedBazelArtifacts {
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
      resolvePaths(sourceFiles),
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

/** Resolve only the selected target's metadata output group, including forward-referenced sets. */
internal fun bazelMetadataFile(events: String, target: String, outputGroup: String): File {
  return bazelMetadataFile(events.lineSequence(), target, outputGroup)
}

private fun bazelMetadataFile(events: Sequence<String>, target: String, outputGroup: String): File {
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
      if (targetCompleted?.get("label")?.jsonPrimitive?.contentOrNull == target) {
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
      val uri =
        (value as JsonObject)["uri"]?.jsonPrimitive?.contentOrNull
          ?: error("Metadata output has no local URI; materialize it first")
      val parsedUri = URI(uri)
      check(parsedUri.scheme == "file") {
        "Metadata output is remote ($uri); materialize it through your build system first"
      }
      files += File(parsedUri)
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
            target,
          ),
        root,
        timeoutSeconds,
      )
      val metadata =
        bep.bufferedReader().use {
          bazelMetadataFile(it.lineSequence(), target, config.metadataOutputGroup)
        }
      val artifacts = bazelJson.decodeFromString<BazelArtifacts>(metadata.readText())
      // Main-repository labels may be emitted with @@ canonical spelling by newer Bazel.
      require(artifacts.target.removePrefix("@@") == target.removePrefix("@@")) {
        "Metadata target ${artifacts.target} does not match $target"
      }
      val executionRoot =
        execute(invocation + listOf("info", "execution_root"), root, timeoutSeconds).trim()
      check(executionRoot.isNotBlank() && File(executionRoot).isDirectory) {
        "Build tool returned an invalid execution_root: $executionRoot"
      }
      return artifacts.resolve(File(executionRoot))
    } finally {
      bep.delete()
    }
  }
}
