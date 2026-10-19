package ee.schimke.composeai.cli

import ee.schimke.composeai.daemon.client.SocketDaemonClientFactory
import ee.schimke.composeai.daemonlaunch.DaemonLaunchBuilder
import ee.schimke.composeai.io.composeAiCacheDir
import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * The opt-in reusable daemon for `compose-preview bazel render` / `show` (#6104).
 *
 * A one-shot render boots a fresh daemon JVM, about 11 s for Robolectric, and stops it on exit. With
 * [requested], the render instead connects to a daemon serving clients over a Unix socket
 * (compose-preview-daemon's `DaemonSocketFrontEnd`), starting one in the background when none is
 * running. It stays warm for [BazelBuildConfig.daemonIdleTimeoutSeconds] after the last render, so
 * an edit, `bazel build` and render cycle pays for the render alone.
 *
 * One daemon serves one [key]: everything it reads only at launch — the descriptor, the resource
 * APK, manifest and R jar, and the preview set — so a change to any of them starts a fresh one.
 * Rebuilt user jars at the same paths do not: the daemon is told the classpath changed and swaps
 * its user class loader, keeping the warm sandbox.
 */
internal object BazelKeepDaemon {
  const val FLAG = "--keep-daemon"
  const val ENV = "COMPOSE_PREVIEW_KEEP_DAEMON"

  private const val SOCKET_PATH = "composeai.daemon.socketPath"
  private const val KEEP_ALIVE_IDLE_MS = "composeai.daemon.keepAliveIdleMs"
  private const val USER_CLASS_DIRS = "composeai.daemon.userClassDirs"
  private const val JARS_FILE = "jars.json"

  private val json = Json { prettyPrint = false }

  /** Whether this run keeps its daemon: [FLAG], [ENV] or `keepDaemon` in bazel.json. */
  fun requested(
    args: List<String>,
    config: BazelBuildConfig,
    env: (String) -> String? = System::getenv,
  ): Boolean =
    FLAG in args ||
      env(ENV)?.trim()?.lowercase() in setOf("1", "true", "yes") ||
      config.keepDaemon

  /**
   * [descriptor] rewritten to share a daemon, with [changedJar] naming a user jar rebuilt since
   * that daemon last rendered (null when none was, or there was no earlier render).
   */
  class Prepared(val descriptor: File, val dir: File, val changedJar: String?)

  /**
   * Rewrites [descriptor] for a shared daemon keyed on it, [launchInputs] (files the daemon reads
   * only at launch, such as linked resources) and [previewsManifest].
   */
  fun prepare(
    descriptor: File,
    launchInputs: List<File>,
    previewsManifest: File,
    idleTimeoutSeconds: Int,
    cacheRoot: File = composeAiCacheDir("daemons"),
  ): Prepared {
    val launch = DaemonLaunchBuilder.decode(descriptor.readText())
    val dir = cacheRoot.resolve(key(descriptor.readText(), launchInputs, previewsManifest))
    dir.mkdirs()
    val shared =
      launch.copy(
        systemProperties =
          launch.systemProperties +
            mapOf(
              SOCKET_PATH to dir.resolve("d.sock").absolutePath,
              KEEP_ALIVE_IDLE_MS to (idleTimeoutSeconds.coerceAtLeast(1) * 1000L).toString(),
            )
      )
    val sharedDescriptor =
      descriptor.resolveSibling("daemon-launch-shared.json").apply {
        writeText(DaemonLaunchBuilder.encode(shared))
      }
    val jars =
      launch.systemProperties[USER_CLASS_DIRS]
        ?.split(File.pathSeparator)
        ?.filter { it.isNotBlank() }
        .orEmpty()
    return Prepared(sharedDescriptor, dir, recordJars(dir, jars))
  }

  /**
   * The daemon directory name: a hash of what the daemon reads only at launch. The preview set is
   * hashed without source line numbers, so editing a preview's body above another keeps the key.
   */
  internal fun key(descriptorText: String, launchInputs: List<File>, previewsManifest: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    digest.update(descriptorText.toByteArray())
    launchInputs.forEach { file ->
      digest.update(file.absolutePath.toByteArray())
      if (file.isFile) file.inputStream().use { input -> input.copyTo(DigestSink(digest)) }
    }
    if (previewsManifest.isFile) {
      val previews = runCatching { Json.parseToJsonElement(previewsManifest.readText()) }
      digest.update(
        (previews.getOrNull()?.let(::withoutLines)?.toString() ?: previewsManifest.readText())
          .toByteArray()
      )
    }
    return digest.digest().joinToString("") { "%02x".format(it) }.take(16)
  }

  private fun withoutLines(element: JsonElement): JsonElement =
    when (element) {
      is JsonObject ->
        JsonObject(element.filterKeys { it !in LINE_KEYS }.mapValues { withoutLines(it.value) })
      is JsonArray -> JsonArray(element.map(::withoutLines))
      else -> element
    }

  private val LINE_KEYS = setOf("bodyLine", "line", "sourceLine")

  /**
   * Records [jars]' size and mtime in [dir] and returns one that changed since the last record, or
   * null on the first run or when none did. Size and mtime, not content: Bazel rewrites an output
   * it rebuilt and leaves the rest alone, and hashing ~300 jars per render would cost more than the
   * reload it decides.
   */
  internal fun recordJars(dir: File, jars: List<String>): String? {
    val file = dir.resolve(JARS_FILE)
    val previous =
      runCatching { json.decodeFromString<Map<String, String>>(file.readText()) }.getOrNull()
    val current = jars.associateWith { File(it).let { jar -> "${jar.length()}:${jar.lastModified()}" } }
    file.writeText(json.encodeToString(current))
    if (previous == null) return null
    return current.entries.firstOrNull { (path, stamp) -> previous[path] != stamp }?.key
  }

  /** `bazel daemon status|stop`: the shared daemons under [cacheRoot]. */
  fun manage(action: String?, cacheRoot: File = composeAiCacheDir("daemons")) {
    val sockets =
      cacheRoot.listFiles().orEmpty().map { it.resolve("d.sock") }.filter { it.exists() }
    when (action) {
      "status",
      null -> {
        val live = sockets.filter { SocketDaemonClientFactory.connect(it.toPath())?.also { c -> c.close() } != null }
        if (live.isEmpty()) println("No shared daemons running")
        live.forEach { println("running  ${it.parentFile.name}  ${it.parentFile.resolve("daemon.log")}") }
      }
      "stop" -> {
        val stopped = sockets.count { SocketDaemonClientFactory.stop(it.toPath()) }
        println("Stopped $stopped shared daemon${if (stopped == 1) "" else "s"}")
      }
      else -> error("Expected bazel daemon status or bazel daemon stop")
    }
  }

  private class DigestSink(private val digest: MessageDigest) : java.io.OutputStream() {
    override fun write(b: Int) = digest.update(b.toByte())

    override fun write(b: ByteArray, off: Int, len: Int) = digest.update(b, off, len)
  }
}
