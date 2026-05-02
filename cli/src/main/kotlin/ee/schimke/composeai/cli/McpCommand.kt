package ee.schimke.composeai.cli

import ee.schimke.composeai.mcp.DaemonMcpMain
import java.io.File
import kotlin.system.exitProcess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * `compose-preview mcp <subcommand>`
 *
 * Three subcommands cover the full agent-attach lifecycle:
 *
 * - `serve` — start the MCP server on stdio. Writes nothing to stdout itself; the only stdout
 *   traffic is the JSON-RPC frames the [DaemonMcpMain] reader/writer pair owns. Status / errors go
 *   to stderr.
 * - `install` — bootstrap descriptors for every module that applies the plugin, flip each
 *   descriptor's `enabled` flag to `true` (`composePreview.experimental.daemon { enabled = true }`
 *   isn't required up-front this way), run `discoverPreviews`, and print the `claude mcp add`
 *   invocation an agent host needs.
 * - `doctor` — report per-module descriptor state (present / missing / disabled / stale) without
 *   making any changes.
 *
 * The CLI bundles `:mcp` so all three run in-process. No second tarball, no manual classpath.
 */
internal class McpCommand(args: List<String>) {

  private val parsed = parseSubcommand(args)
  private val sub: String = parsed.first
  private val rest: List<String> = parsed.second

  fun run() {
    when (sub) {
      "serve" -> serve(rest)
      "install" -> install(rest)
      "doctor" -> doctor(rest)
      "help",
      "--help",
      "-h" -> {
        printUsage()
      }
      else -> {
        System.err.println("Unknown mcp subcommand: $sub")
        printUsage()
        exitProcess(1)
      }
    }
  }

  private fun printUsage() {
    println(
      """
      compose-preview mcp <subcommand>

      Subcommands:
        serve    Start the MCP server on stdio. Forwards remaining flags to DaemonMcpMain
                 (--project <path>[:<rootName>], --replicas-per-daemon N).
        install  Bootstrap daemon descriptors for every module with the plugin applied,
                 flip each descriptor's enabled flag, and print a `claude mcp add` line.
        doctor   Report per-module descriptor state (no mutations).

      Common options (install/doctor):
        --project <path>     Project root containing settings.gradle[.kts] (default: cwd)
        --module <gradlePath> Limit to one or more module paths (repeatable)
        --json               Emit a structured envelope on stdout (install, doctor)

      See skills/compose-preview/design/MCP.md for the full agent flow.
      """
        .trimIndent()
    )
  }

  // -- serve -------------------------------------------------------------------------------------

  private fun serve(args: List<String>) {
    // Pure delegation — the MCP server owns System.in / System.out for JSON-RPC framing. Anything
    // we print to stdout would corrupt the wire protocol; status goes to stderr.
    DaemonMcpMain.main(args.toTypedArray())
  }

  // -- install -----------------------------------------------------------------------------------

  private fun install(args: List<String>) {
    val emitJson = "--json" in args
    val projectDir = resolveProjectDir(args)
    val moduleFilter = args.flagValuesAll("--module").map { it.removePrefix(":") }.toSet()

    val connection = GradleConnection(projectDir, verbose = "--verbose" in args || "-v" in args)
    connection.use { gc ->
      val allModules = gc.findPreviewModules()
      val modules =
        if (moduleFilter.isEmpty()) allModules
        else allModules.filter { it.gradlePath in moduleFilter }

      if (modules.isEmpty()) {
        val msg =
          if (moduleFilter.isEmpty()) "no modules apply the compose-preview plugin"
          else "none of --module ${moduleFilter.joinToString(",")} apply the plugin"
        System.err.println("compose-preview mcp install: $msg")
        exitProcess(2)
      }

      // Two batched runs: bootstrap every descriptor, then re-run discovery so previews.json sits
      // alongside. Single Gradle invocation per phase keeps configuration cache hits warm.
      val daemonTasks = modules.map { ":${it.gradlePath}:composePreviewDaemonStart" }
      val discoverTasks = modules.map { ":${it.gradlePath}:discoverPreviews" }

      System.err.println(
        "==> bootstrapping daemon descriptors for ${modules.size} module(s): " +
          modules.joinToString(", ") { ":${it.gradlePath}" }
      )
      // No `-P` propagation here: DaemonExtension's `enabled` property is intentionally not wired
      // to a Gradle property (see DaemonExtension.kt KDoc). We populate the descriptor by running
      // the task and then patch the JSON ourselves below — same as the smoke script does.
      val daemonOk = gc.runTasks(*daemonTasks.toTypedArray())
      if (!daemonOk) {
        System.err.println("composePreviewDaemonStart failed; aborting.")
        exitProcess(1)
      }

      // Flip the on-disk `enabled` flag — DaemonExtension's gradle-property override is
      // intentionally
      // not propagated into the JSON (see DaemonExtension.kt KDoc), so we patch it here so the
      // agent
      // doesn't have to remember the build-script edit.
      val descriptors = modules.mapNotNull { module ->
        val descriptor = File(module.projectDir, "build/compose-previews/daemon-launch.json")
        if (!descriptor.isFile) {
          System.err.println(
            "warning: descriptor missing for :${module.gradlePath} (${descriptor})"
          )
          null
        } else {
          enableDescriptor(descriptor)
          DescriptorState(module.gradlePath, descriptor, enabled = true)
        }
      }

      System.err.println("==> running discoverPreviews so previews.json is up to date")
      val discoverOk = gc.runTasks(*discoverTasks.toTypedArray())
      if (!discoverOk) {
        System.err.println("discoverPreviews failed; descriptors are still in place.")
        exitProcess(1)
      }

      // CLAUDE_CLOUD users need an absolute path because `~/.claude/skills/.../bin/compose-preview`
      // is the canonical launcher; we don't know how `compose-preview` is on the consumer's PATH.
      val launcher = locateOwnLauncher() ?: "compose-preview"
      val claudeMcpAdd =
        "claude mcp add compose-preview-mcp -- $launcher mcp serve --project=${projectDir.absolutePath}"

      if (emitJson) {
        val payload = buildJsonObject {
          put("schema", JsonPrimitive("compose-preview-mcp-install/v1"))
          put("projectRoot", JsonPrimitive(projectDir.absolutePath))
          put("launcher", JsonPrimitive(launcher))
          put("claudeMcpAdd", JsonPrimitive(claudeMcpAdd))
          put(
            "modules",
            kotlinx.serialization.json.JsonArray(
              descriptors.map {
                buildJsonObject {
                  put("gradlePath", JsonPrimitive(":${it.gradlePath}"))
                  put("descriptor", JsonPrimitive(it.descriptor.absolutePath))
                  put("enabled", JsonPrimitive(it.enabled))
                }
              }
            ),
          )
        }
        println(JSON.encodeToString(JsonObject.serializer(), payload))
      } else {
        System.err.println()
        System.err.println("==> ready. Bootstrapped descriptors:")
        descriptors.forEach { d ->
          System.err.println("    :${d.gradlePath}  ${d.descriptor}  (enabled=true)")
        }
        System.err.println()
        System.err.println("Attach an MCP-aware agent host with:")
        println(claudeMcpAdd)
      }
    }
  }

  // -- doctor ------------------------------------------------------------------------------------

  private fun doctor(args: List<String>) {
    val emitJson = "--json" in args
    val projectDir = resolveProjectDir(args)
    val moduleFilter = args.flagValuesAll("--module").map { it.removePrefix(":") }.toSet()

    val connection = GradleConnection(projectDir, verbose = false)
    connection.use { gc ->
      val allModules = gc.findPreviewModules()
      val modules =
        if (moduleFilter.isEmpty()) allModules
        else allModules.filter { it.gradlePath in moduleFilter }

      if (modules.isEmpty()) {
        System.err.println("compose-preview mcp doctor: no matching modules apply the plugin")
        exitProcess(2)
      }

      val states = modules.map { module ->
        val descriptor = File(module.projectDir, "build/compose-previews/daemon-launch.json")
        if (!descriptor.isFile) {
          DoctorState(module.gradlePath, descriptor, status = "missing", enabled = false)
        } else {
          val obj = parseDescriptor(descriptor)
          val enabled = obj?.get("enabled")?.jsonPrimitive?.boolean == true
          DoctorState(
            module.gradlePath,
            descriptor,
            status = if (enabled) "ok" else "disabled",
            enabled = enabled,
          )
        }
      }

      if (emitJson) {
        val payload = buildJsonObject {
          put("schema", JsonPrimitive("compose-preview-mcp-doctor/v1"))
          put("projectRoot", JsonPrimitive(projectDir.absolutePath))
          put(
            "modules",
            kotlinx.serialization.json.JsonArray(
              states.map {
                buildJsonObject {
                  put("gradlePath", JsonPrimitive(":${it.gradlePath}"))
                  put("descriptor", JsonPrimitive(it.descriptor.absolutePath))
                  put("status", JsonPrimitive(it.status))
                  put("enabled", JsonPrimitive(it.enabled))
                }
              }
            ),
          )
        }
        println(JSON.encodeToString(JsonObject.serializer(), payload))
      } else {
        states.forEach { s ->
          val marker =
            when (s.status) {
              "ok" -> "ok"
              "disabled" -> "disabled (run `compose-preview mcp install` to flip enabled=true)"
              "missing" -> "missing (run `compose-preview mcp install`)"
              else -> s.status
            }
          println(":${s.gradlePath}  $marker  (${s.descriptor})")
        }
      }

      val anyMissing = states.any { it.status != "ok" }
      if (anyMissing) exitProcess(1)
    }
  }

  // -- helpers -----------------------------------------------------------------------------------

  private fun resolveProjectDir(args: List<String>): File {
    val explicit = args.flagValue("--project")?.let(::File)
    val cwd = explicit ?: File(".").canonicalFile
    val resolved = findGradleRoot(cwd) ?: cwd
    return resolved
  }

  private fun findGradleRoot(from: File): File? {
    var dir: File? = from.canonicalFile
    while (dir != null) {
      if (
        File(dir, "settings.gradle.kts").isFile ||
          File(dir, "settings.gradle").isFile ||
          File(dir, "gradlew").isFile
      ) {
        return dir
      }
      dir = dir.parentFile
    }
    return null
  }

  private fun enableDescriptor(file: File) {
    val text = file.readText()
    val updated = text.replace(Regex("\"enabled\"\\s*:\\s*false"), "\"enabled\": true")
    if (updated != text) file.writeText(updated)
  }

  private fun parseDescriptor(file: File): JsonObject? =
    try {
      JSON.parseToJsonElement(file.readText()).jsonObject
    } catch (_: Exception) {
      null
    }

  /**
   * Locate the `compose-preview` launcher from `app_home` exported by the Gradle distribution
   * `application` plugin. Falls back to the `compose-preview` PATH entry when the env var isn't set
   * (e.g. running directly via `java -jar`).
   */
  private fun locateOwnLauncher(): String? {
    val appHome = System.getenv("APP_HOME") ?: return null
    val candidate = File(appHome, "bin/compose-preview")
    return if (candidate.isFile) candidate.absolutePath else null
  }

  private data class DescriptorState(
    val gradlePath: String,
    val descriptor: File,
    val enabled: Boolean,
  )

  private data class DoctorState(
    val gradlePath: String,
    val descriptor: File,
    val status: String,
    val enabled: Boolean,
  )

  private companion object {
    val JSON: Json = Json { prettyPrint = true }

    private val VALUE_FLAGS = setOf("--project", "--module", "--replicas-per-daemon")

    private fun parseSubcommand(args: List<String>): Pair<String, List<String>> {
      var i = 0
      while (i < args.size) {
        val arg = args[i]
        when {
          arg in VALUE_FLAGS -> i += 2
          VALUE_FLAGS.any { arg.startsWith("$it=") } -> i++
          arg.startsWith("--") -> i++
          arg.startsWith("-") -> i++
          else -> {
            val rest = args.toMutableList().apply { removeAt(i) }
            return arg to rest
          }
        }
      }
      return "help" to args
    }
  }
}
