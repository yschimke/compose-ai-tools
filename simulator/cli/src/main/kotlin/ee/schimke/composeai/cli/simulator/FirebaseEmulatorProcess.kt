package ee.schimke.composeai.cli.simulator

import java.io.Closeable
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Resolves `--firebase-emulator <spec>` for `compose-preview android-simulator`, and optionally
 * starts and tears down `firebase emulators:start` when `<spec>` is a `firebase.json` file (or a
 * directory containing one) and no Emulator Hub is already listening on its configured port.
 */
internal class FirebaseEmulatorProcess(
  /** Serialized config string passed to `-Dcomposeai.simulator.firebase.emulators`. */
  val emulatorSpec: String,
  /** Optional Firebase project ID passed to `-Dcomposeai.simulator.firebase.project`. */
  val projectId: String?,
  private val process: Process? = null,
) : Closeable {

  private val shutdownHook: Thread? = process?.let { proc ->
    Thread(
        { stopProcessTree(proc) },
        "compose-preview-firebase-emulator-shutdown",
      )
      .also { Runtime.getRuntime().addShutdownHook(it) }
  }

  override fun close() {
    val proc = process ?: return
    shutdownHook?.let { runCatching { Runtime.getRuntime().removeShutdownHook(it) } }
    stopProcessTree(proc)
  }

  companion object {
    const val DEFAULT_PROJECT_ID = "demo-simulator"
    const val DEFAULT_HUB_HOST = "127.0.0.1"
    const val DEFAULT_HUB_PORT = 4400
    private const val STARTUP_TIMEOUT_MS = 60_000L
    private const val POLL_INTERVAL_MS = 300L

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Resolves `--firebase-emulator <spec>`:
     * - `auto`, `hub=...`, or `service=host:port,...`: connects to an already-running suite.
     * - path to `firebase.json` (or a directory containing it): reuses an already-running Hub on
     *   its configured port, or spawns `firebase emulators:start` and waits for the Hub to come up.
     */
    fun resolve(
      spec: String,
      projectId: String?,
      verbose: Boolean = false,
      firebaseBinary: String = findFirebaseBinary(),
    ): FirebaseEmulatorProcess {
      val trimmed = spec.trim()
      val configFile = resolveFirebaseJsonFile(trimmed)
      if (configFile == null) {
        // Explicit spec (`auto`, `hub=...`, `auth=9099,firestore=8080`, or inline JSON).
        return FirebaseEmulatorProcess(emulatorSpec = trimmed, projectId = projectId)
      }

      val parsed = parseFirebaseJson(configFile)
      val explicitProject =
        projectId?.takeIf { it.isNotBlank() } ?: readDefaultProjectFromFirebaserc(configFile)
      val cliProject = explicitProject ?: DEFAULT_PROJECT_ID
      val hubHost = parsed.hubHost
      val hubPort = parsed.hubPort

      // Reuse an already-running Firebase Emulator Hub if one is listening on this port.
      val existing = probeHub(hubHost, hubPort)
      if (existing != null) {
        if (verbose) {
          System.err.println(
            "compose-preview android-simulator: reusing running Firebase Emulator Hub at $hubHost:$hubPort",
          )
        }
        return FirebaseEmulatorProcess(
          emulatorSpec = hubSpecJson(explicitProject, hubHost, hubPort, existing),
          projectId = explicitProject,
        )
      }

      val command = buildStartCommand(firebaseBinary, configFile, cliProject)
      if (verbose) {
        System.err.println(
          "compose-preview android-simulator: starting Firebase Emulator Suite: ${command.joinToString(" ")}",
        )
      }
      val builder = ProcessBuilder(command).directory(configFile.parentFile)
      builder.redirectErrorStream(true)
      val proc =
        try {
          builder.start()
        } catch (e: Exception) {
          throw IllegalStateException(
            "failed to start Firebase Emulator Suite (${command.first()}): ${e.message}. " +
              "Install firebase-tools (`npm install -g firebase-tools`) or start emulators " +
              "manually and pass `--firebase-emulator auto`.",
            e,
          )
        }

      val outputTail = StringBuilder()
      val reader =
        Thread(
            {
              proc.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                  if (verbose) System.err.println("[firebase-emulator] $line")
                  synchronized(outputTail) {
                    outputTail.appendLine(line)
                    if (outputTail.length > 8_000) {
                      outputTail.delete(0, outputTail.length - 8_000)
                    }
                  }
                }
              }
            },
            "firebase-emulator-log",
          )
          .apply {
            isDaemon = true
            start()
          }

      val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(STARTUP_TIMEOUT_MS)
      while (System.nanoTime() < deadline) {
        val discovered = probeHub(hubHost, hubPort)
        if (discovered != null && hasAllConfiguredServices(discovered, parsed.configuredServices)) {
          return FirebaseEmulatorProcess(
            emulatorSpec = hubSpecJson(explicitProject, hubHost, hubPort, discovered),
            projectId = explicitProject,
            process = proc,
          )
        }
        if (!proc.isAlive) {
          reader.join(1_000)
          val log = synchronized(outputTail) { outputTail.toString().trim() }
          throw IllegalStateException(
            "Firebase Emulator Suite exited early (status ${proc.exitValue()}):\n$log",
          )
        }
        Thread.sleep(POLL_INTERVAL_MS)
      }

      stopProcessTree(proc)
      val log = synchronized(outputTail) { outputTail.toString().trim() }
      throw IllegalStateException(
        "timed out waiting for Firebase Emulator Hub at $hubHost:$hubPort after " +
          "${STARTUP_TIMEOUT_MS / 1000}s:\n$log",
      )
    }

    internal data class ParsedFirebaseJson(
      val hubHost: String,
      val hubPort: Int,
      val configuredServices: Set<String>,
    )

    internal fun resolveFirebaseJsonFile(spec: String): File? {
      if (spec.isEmpty() || spec.startsWith("{") || spec.equals("auto", ignoreCase = true)) {
        return null
      }
      if ('=' in spec && !File(spec).exists()) return null
      val candidate = File(spec).absoluteFile
      if (candidate.isFile) return candidate
      if (candidate.isDirectory) {
        val nested = File(candidate, "firebase.json")
        if (nested.isFile) return nested
      }
      if (spec.endsWith(".json", ignoreCase = true)) {
        throw IllegalArgumentException("no such Firebase config file: $candidate")
      }
      return null
    }

    internal fun parseFirebaseJson(file: File): ParsedFirebaseJson {
      val root = json.parseToJsonElement(file.readText()).jsonObject
      val emulators = root["emulators"]?.jsonObject
      val hub = emulators?.get("hub")?.jsonObject
      val hubHost = normalizeHost(hub?.get("host")?.jsonPrimitive?.content)
      val hubPort = hub?.get("port")?.jsonPrimitive?.intOrNull ?: DEFAULT_HUB_PORT
      val services =
        emulators
          ?.keys
          ?.filter {
            it in setOf("auth", "firestore", "database", "storage", "functions", "dataconnect")
          }
          ?.toSet()
          .orEmpty()
      return ParsedFirebaseJson(hubHost, hubPort, services)
    }

    internal fun readDefaultProjectFromFirebaserc(firebaseJson: File): String? {
      val rc = File(firebaseJson.parentFile, ".firebaserc")
      if (!rc.isFile) return null
      return runCatching {
        val root = json.parseToJsonElement(rc.readText()).jsonObject
        root["projects"]?.jsonObject?.get("default")?.jsonPrimitive?.content?.takeIf {
          it.isNotBlank()
        }
      }
        .getOrNull()
    }

    internal fun buildStartCommand(
      firebaseBinary: String,
      configFile: File,
      projectId: String,
    ): List<String> =
      listOf(
        firebaseBinary,
        "emulators:start",
        "--config",
        configFile.absolutePath,
        "--project",
        projectId,
      )

    internal fun probeHub(host: String, port: Int): JsonObject? = runCatching {
      val conn =
        (URL("http://$host:$port/emulators").openConnection() as HttpURLConnection).apply {
          requestMethod = "GET"
          connectTimeout = 500
          readTimeout = 1_000
        }
      try {
        if (conn.responseCode !in 200..299) return@runCatching null
        val body = conn.inputStream.bufferedReader().readText()
        json.parseToJsonElement(body).jsonObject
      } finally {
        conn.disconnect()
      }
    }
      .getOrNull()

    internal fun hasAllConfiguredServices(
      hubResponse: JsonObject,
      configuredServices: Set<String>,
    ): Boolean = configuredServices.all { service ->
      hubResponse[service]?.jsonObject?.get("port")?.jsonPrimitive?.intOrNull != null
    }

    internal fun hubSpecJson(
      projectId: String?,
      hubHost: String,
      hubPort: Int,
      hubResponse: JsonObject,
    ): String {
      val known = listOf("auth", "firestore", "database", "storage", "functions", "dataconnect")
      val obj = buildJsonObject {
        if (!projectId.isNullOrBlank()) put("projectId", projectId)
        put(
          "hub",
          buildJsonObject {
            put("host", hubHost)
            put("port", hubPort)
          },
        )
        put(
          "services",
          buildJsonObject {
            for (name in known) {
              val entry = hubResponse[name]?.jsonObject ?: continue
              val port = entry["port"]?.jsonPrimitive?.intOrNull ?: continue
              val host = normalizeHost(entry["host"]?.jsonPrimitive?.content)
              put(
                name,
                buildJsonObject {
                  put("host", host)
                  put("port", port)
                },
              )
            }
          },
        )
      }
      return obj.toString()
    }

    private fun normalizeHost(host: String?): String {
      val trimmed = host?.trim().orEmpty()
      return if (
        trimmed.isEmpty() || trimmed == "0.0.0.0" || trimmed == "::" || trimmed == "10.0.2.2"
      ) {
        DEFAULT_HUB_HOST
      } else {
        trimmed
      }
    }

    private fun findFirebaseBinary(): String =
      if (System.getProperty("os.name").orEmpty().lowercase().contains("win")) "firebase.cmd"
      else "firebase"

    private fun stopProcessTree(proc: Process) {
      runCatching {
        proc.toHandle().descendants().forEach { runCatching { it.destroy() } }
        proc.destroy()
        if (!proc.waitFor(3, TimeUnit.SECONDS)) {
          proc.toHandle().descendants().forEach { runCatching { it.destroyForcibly() } }
          proc.destroyForcibly()
        }
      }
    }
  }
}
