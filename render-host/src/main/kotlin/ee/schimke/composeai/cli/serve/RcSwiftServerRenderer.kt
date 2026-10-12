/*
 * Copyright 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.daemon.protocol.RemoteNamedValue
import java.io.File
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Duration
import java.util.Properties
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/**
 * Renders a captured Remote Compose document to PNG with the native Swift player's AppKit renderer
 * — yschimke/rc-players' `rc-swift-render` — for the serve viewer's swift-appkit chip. It is the
 * one lane whose pixels come from Apple's text shaping and antialiasing, which is where the other
 * players disagree with it.
 *
 * `rc-swift-render` speaks `rc-render-jvm`'s contract in both modes, so this reuses
 * [RcJvmWorkerPool] (pointed at `rc-swift-render --worker`) and the same one-shot flags. PNG only:
 * AppKit has no vector export.
 *
 * The binary is found, in order:
 * 1. [SYS_PROP_BINARY] / [ENV_BINARY] — an explicit path, e.g. a local `swift build`;
 * 2. `rc-swift-render` on `PATH`;
 * 3. the cached release asset, `<cache>/composeai/rc-swift-render/<version>/…`;
 * 4. otherwise downloaded on first render from the rc-players GitHub Release for [version] — the
 *    `rcplayers` version this module was built against — and verified against its `.sha256`.
 *    [SYS_PROP_DOWNLOAD]`=off` disables this.
 *
 * Only macOS on Apple silicon can run it (the asset is arm64, the renderer AppKit); everywhere else
 * [isAvailable] is false and the chip never lights. A failed download is remembered for the life of
 * the process, so a server offline from GitHub reports the lane unavailable rather than retrying it
 * on every request.
 */
// Public because `:server` call sites live in another module; not a widened API by intent.
public object RcSwiftServerRenderer {

  public const val LANE: String = "swift-appkit"
  public const val SYS_PROP_BINARY: String = "composeai.rcswift.binary"
  public const val ENV_BINARY: String = "COMPOSEAI_RC_SWIFT_RENDER"
  public const val SYS_PROP_DOWNLOAD: String = "composeai.rcswift.download"
  public const val SYS_PROP_VERSION: String = "composeai.rcswift.version"
  public const val SYS_PROP_RELEASE_BASE_URL: String = "composeai.rcswift.releaseBaseUrl"
  /**
   * Where downloaded assets are kept; defaults to `$XDG_CACHE_HOME` or `~/.cache`, + `composeai`.
   */
  public const val SYS_PROP_CACHE_DIR: String = "composeai.rcswift.cacheDir"

  /** The rc-players release asset's name; its layout is `<name>/bin/rc-swift-render`. */
  public const val ASSET_NAME: String = "rc-swift-render-macos-arm64"
  private const val EXECUTABLE = "rc-swift-render"
  private const val DEFAULT_RELEASE_BASE_URL =
    "https://github.com/yschimke/rc-players/releases/download"

  private const val RENDER_TIMEOUT_SECONDS = 60L
  private const val DOWNLOAD_TIMEOUT_SECONDS = 120L
  private const val DRAIN_FLUSH_MILLIS = 1000L

  @Volatile private var downloadFailure: String? = null
  @Volatile private var poolInstance: RcJvmWorkerPool? = null
  @Volatile private var poolBinary: File? = null

  /** The rc-players version whose asset this downloads: [SYS_PROP_VERSION], else the build's. */
  public fun version(): String =
    System.getProperty(SYS_PROP_VERSION)?.takeIf { it.isNotBlank() } ?: BUILT_AGAINST_VERSION

  private val BUILT_AGAINST_VERSION: String by lazy {
    Properties()
      .apply {
        RcSwiftServerRenderer::class.java.getResourceAsStream("rc-swift-render.properties")?.use {
          load(it)
        }
      }
      .getProperty("version")
      .orEmpty()
  }

  /** True on the one host the asset runs on: macOS, Apple silicon. */
  public fun isSupportedHost(): Boolean {
    val os = System.getProperty("os.name").orEmpty().lowercase()
    val arch = System.getProperty("os.arch").orEmpty().lowercase()
    return os.contains("mac") && (arch == "aarch64" || arch == "arm64")
  }

  /**
   * True when a render can be attempted: a binary is already here, or this host can fetch one.
   * Cheap — it never downloads.
   */
  public fun isAvailable(): Boolean = isSupportedHost() && (locateLocal() != null || canDownload())

  /** Why [isAvailable] is false, for a 503 body. */
  public fun unavailableReason(): String =
    when {
      !isSupportedHost() ->
        "the swift-appkit lane needs macOS on Apple silicon (this host is " +
          "${System.getProperty("os.name")} ${System.getProperty("os.arch")})"
      downloadFailure != null -> "rc-swift-render is unavailable: $downloadFailure"
      else ->
        "rc-swift-render not found: set -D$SYS_PROP_BINARY or $ENV_BINARY, put it on PATH, " +
          "or allow the download (-D$SYS_PROP_DOWNLOAD)"
    }

  /**
   * Render [docBytes] at [spec]'s size and density with [seeds] applied, light or dark per [theme].
   * Results reuse [RcJvmServerRenderer.RenderResult] so `:server` handles both lanes with one
   * `when`.
   */
  public fun render(
    docBytes: ByteArray,
    spec: RcJvmRenderSpec,
    seeds: Map<String, RemoteNamedValue> = emptyMap(),
    theme: RcJvmServerRenderer.RenderTheme = RcJvmServerRenderer.RenderTheme.LIGHT,
  ): RcJvmServerRenderer.RenderResult {
    if (!isSupportedHost()) return RcJvmServerRenderer.RenderResult.Unavailable(unavailableReason())
    val binary =
      try {
        resolveBinary()
      } catch (e: IOException) {
        return RcJvmServerRenderer.RenderResult.Unavailable(
          "rc-swift-render is unavailable: ${e.message}"
        )
      } ?: return RcJvmServerRenderer.RenderResult.Unavailable(unavailableReason())

    val seedLines = RcJvmServerRenderer.seedLines(seeds)
    val startNanos = System.nanoTime()
    fun secondsLeft(): Long =
      RENDER_TIMEOUT_SECONDS - (System.nanoTime() - startNanos) / 1_000_000_000L

    // The frame has no font-scale field, so scaled text takes the one-shot path, as for cmp-jvm.
    if (!spec.scalesText) {
      pool(binary)?.let { pool ->
        when (
          val pooled =
            pool.render(
              docBytes,
              spec,
              seedLines.joinToString("\n"),
              RcJvmServerRenderer.Format.PNG,
              theme,
              secondsLeft(),
            )
        ) {
          is RcJvmWorkerPool.PoolResult.Ok ->
            return RcJvmServerRenderer.RenderResult.Ok(pooled.bytes)
          is RcJvmWorkerPool.PoolResult.Failed ->
            return RcJvmServerRenderer.RenderResult.Failed(pooled.reason)
          is RcJvmWorkerPool.PoolResult.Unusable ->
            if (secondsLeft() <= 1) {
              return RcJvmServerRenderer.RenderResult.Failed(pooled.reason)
            }
        }
      }
    }
    return renderOneShot(binary, docBytes, spec, seedLines, theme, secondsLeft())
  }

  private fun renderOneShot(
    binary: File,
    docBytes: ByteArray,
    spec: RcJvmRenderSpec,
    seedLines: List<String>,
    theme: RcJvmServerRenderer.RenderTheme,
    timeoutSeconds: Long,
  ): RcJvmServerRenderer.RenderResult {
    val input = File.createTempFile("rcswift-in-", ".rc")
    val output = File.createTempFile("rcswift-out-", ".png")
    val seedsFile =
      seedLines
        .takeIf { it.isNotEmpty() }
        ?.let { lines ->
          File.createTempFile("rcswift-seeds-", ".txt").also {
            it.writeText(lines.joinToString("\n"))
          }
        }
    try {
      input.writeBytes(docBytes)
      output.delete()
      val command = buildList {
        add(binary.absolutePath)
        addAll(listOf("--input", input.absolutePath, "--output", output.absolutePath))
        addAll(listOf("--width", spec.widthPx.toString(), "--height", spec.heightPx.toString()))
        addAll(listOf("--density", spec.density.toString()))
        addAll(listOf("--fontScale", spec.fontScale.toString()))
        addAll(listOf("--theme", theme.wire))
        if (seedsFile != null) addAll(listOf("--seeds", seedsFile.absolutePath))
      }
      val process =
        ProcessBuilder(command).redirectErrorStream(true).start().also { it.outputStream.close() }
      val log = StringBuilder()
      val drain = Thread {
        process.inputStream.bufferedReader().forEachLine { log.appendLine(it) }
      }
        .apply {
          isDaemon = true
          start()
        }
      if (!process.waitFor(timeoutSeconds.coerceAtLeast(1), TimeUnit.SECONDS)) {
        process.destroyForcibly()
        drain.join(DRAIN_FLUSH_MILLIS)
        return RcJvmServerRenderer.RenderResult.Failed(
          "$LANE render timed out after ${timeoutSeconds}s"
        )
      }
      drain.join(DRAIN_FLUSH_MILLIS)
      if (process.exitValue() != 0 || !output.isFile || output.length() == 0L) {
        val tail = log.toString().trim().lines().lastOrNull()?.take(300).orEmpty()
        return RcJvmServerRenderer.RenderResult.Failed(
          "$LANE render failed (exit ${process.exitValue()})" +
            tail.takeIf { it.isNotEmpty() }?.let { ": $it" }.orEmpty()
        )
      }
      return RcJvmServerRenderer.RenderResult.Ok(output.readBytes())
    } finally {
      input.delete()
      output.delete()
      seedsFile?.delete()
    }
  }

  private fun pool(binary: File): RcJvmWorkerPool? {
    if (!RcJvmWorkerPool.isEnabled()) return null
    poolInstance
      ?.takeIf { poolBinary == binary }
      ?.let {
        return it
      }
    return synchronized(this) {
      poolInstance?.takeIf { poolBinary == binary }
        ?: RcJvmWorkerPool(
            classpath = emptyList(),
            javaBin = "",
            extraJvmArgs = emptyList(),
            maxWorkers = RcJvmWorkerPool.configuredWorkers(),
            maxRendersPerWorker = RcJvmWorkerPool.configuredMaxRenders(),
            maxWorkerAgeMillis = RcJvmWorkerPool.configuredMaxAgeMillis(),
            renderTimeoutSeconds = RENDER_TIMEOUT_SECONDS,
            workerCommand = listOf(binary.absolutePath, "--worker"),
            lane = LANE,
          )
          .also { created ->
            poolInstance?.close()
            poolInstance = created
            poolBinary = binary
            Runtime.getRuntime()
              .addShutdownHook(Thread({ created.close() }, "rcswift-pool-shutdown"))
          }
    }
  }

  /** Releases every pooled worker. Exposed for tests and for an explicit serve shutdown. */
  public fun shutdownPool() {
    synchronized(this) {
      poolInstance?.close()
      poolInstance = null
      poolBinary = null
    }
  }

  /** [shutdownPool], and forget a remembered download failure. */
  internal fun resetForTests() {
    shutdownPool()
    downloadFailure = null
  }

  /** An explicit binary, one on `PATH`, or a cached asset; never downloads. */
  internal fun locateLocal(): File? {
    val explicit =
      System.getProperty(SYS_PROP_BINARY)?.takeIf { it.isNotBlank() }
        ?: System.getenv(ENV_BINARY)?.takeIf { it.isNotBlank() }
    // An explicit path is a decision: honour it or report it missing, never fall through to
    // something else that would draw with a different build.
    if (explicit != null) return File(explicit).takeIf { it.canExecute() }
    System.getenv("PATH")
      ?.split(File.pathSeparator)
      ?.map { File(it, EXECUTABLE) }
      ?.firstOrNull { it.isFile && it.canExecute() }
      ?.let {
        return it
      }
    return cachedBinary()?.takeIf { it.canExecute() }
  }

  private fun canDownload(): Boolean =
    !System.getProperty(SYS_PROP_DOWNLOAD).equals("off", ignoreCase = true) &&
      version().isNotEmpty() &&
      downloadFailure == null &&
      (System.getProperty(SYS_PROP_BINARY) ?: System.getenv(ENV_BINARY)).isNullOrBlank()

  private fun cacheRoot(): File? {
    System.getProperty(SYS_PROP_CACHE_DIR)
      ?.takeIf { it.isNotBlank() }
      ?.let {
        return File(it)
      }
    val xdg = System.getenv("XDG_CACHE_HOME")?.takeIf { it.isNotBlank() }
    val base = xdg?.let(::File) ?: System.getProperty("user.home")?.let { File(it, ".cache") }
    return base?.let { File(it, "composeai/rc-swift-render") }
  }

  private fun cachedBinary(): File? {
    val v = version().takeIf { it.isNotEmpty() } ?: return null
    return cacheRoot()?.let { File(it, "$v/$ASSET_NAME/bin/$EXECUTABLE") }
  }

  private fun resolveBinary(): File? {
    locateLocal()?.let {
      return it
    }
    if (!canDownload()) return null
    return synchronized(this) {
      locateLocal()
        ?: try {
          download()
        } catch (e: IOException) {
          downloadFailure = e.message ?: e.toString()
          throw e
        }
    }
  }

  /**
   * Fetch, verify and unpack [version]'s asset into the cache. Unpacked beside its destination and
   * renamed into place, so a crash mid-download never leaves a half-written binary that
   * [locateLocal] would then trust.
   */
  private fun download(): File {
    val v = version()
    val target = cachedBinary() ?: throw IOException("no cache directory (HOME unset)")
    val versionDir = target.parentFile.parentFile.parentFile
    val base =
      (System.getProperty(SYS_PROP_RELEASE_BASE_URL)?.takeIf { it.isNotBlank() }
          ?: DEFAULT_RELEASE_BASE_URL)
        .trimEnd('/')
    val url = "$base/v$v/$ASSET_NAME.zip"
    val client =
      HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(30))
        .build()

    fun fetch(from: String): ByteArray {
      val response =
        try {
          client.send(
            HttpRequest.newBuilder(URI.create(from))
              .timeout(Duration.ofSeconds(DOWNLOAD_TIMEOUT_SECONDS))
              .GET()
              .build(),
            HttpResponse.BodyHandlers.ofByteArray(),
          )
        } catch (e: InterruptedException) {
          Thread.currentThread().interrupt()
          throw IOException("download of $from interrupted", e)
        }
      if (response.statusCode() != 200) {
        throw IOException(
          "could not download $from (HTTP ${response.statusCode()}); rc-players $v may predate " +
            "rc-swift-render — set -D$SYS_PROP_VERSION to a release that ships $ASSET_NAME.zip"
        )
      }
      return response.body()
    }

    val zip = fetch(url)
    val expected =
      String(fetch("$url.sha256"), Charsets.UTF_8).trim().split(Regex("\\s+")).firstOrNull()
    val actual =
      MessageDigest.getInstance("SHA-256").digest(zip).joinToString("") { "%02x".format(it) }
    if (expected == null || !expected.equals(actual, ignoreCase = true)) {
      throw IOException("$ASSET_NAME.zip checksum mismatch (expected $expected, got $actual)")
    }

    versionDir.mkdirs()
    val staging = Files.createTempDirectory(versionDir.toPath(), ".download-").toFile()
    try {
      unzip(zip, staging)
      val staged = File(staging, "$ASSET_NAME/bin/$EXECUTABLE")
      if (!staged.isFile) throw IOException("$ASSET_NAME.zip has no $ASSET_NAME/bin/$EXECUTABLE")
      staged.setExecutable(true)
      val destination = File(versionDir, ASSET_NAME)
      destination.deleteRecursively()
      Files.move(
        File(staging, ASSET_NAME).toPath(),
        destination.toPath(),
        StandardCopyOption.ATOMIC_MOVE,
      )
    } finally {
      staging.deleteRecursively()
    }
    if (!target.canExecute()) throw IOException("unpacked $target is not executable")
    return target
  }

  private fun unzip(bytes: ByteArray, into: File) {
    val root = into.canonicalFile
    ZipInputStream(bytes.inputStream()).use { zip ->
      while (true) {
        val entry = zip.nextEntry ?: break
        val out = File(root, entry.name).canonicalFile
        // Refuse an entry that would land outside the staging directory.
        if (!out.toPath().startsWith(root.toPath())) {
          throw IOException("$ASSET_NAME.zip entry escapes the archive: ${entry.name}")
        }
        if (entry.isDirectory) {
          out.mkdirs()
        } else {
          out.parentFile.mkdirs()
          out.outputStream().use { zip.copyTo(it) }
        }
      }
    }
  }
}
