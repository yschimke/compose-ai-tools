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

import ee.schimke.composeai.bundle.bundleSidecarSearchDescription
import ee.schimke.composeai.bundle.locateBundleSidecarJars
import ee.schimke.composeai.daemon.protocol.RemoteNamedValue
import ee.schimke.composeai.io.composeAiCacheDir
import java.io.File
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * Renders a captured Remote Compose document to PNG or layered SVG for the serve viewer's
 * **cmp-jvm** chip, by spawning the embedded desktop player ([ee.schimke.composeai.rcembedded.jvm]
 * `RcJvmRenderMain`) as a one-shot subprocess off an isolated classpath — the same subprocess
 * isolation `BundleRenderer` uses for the desktop `@Preview` renderer, and for the same reason:
 * Compose Desktop + Skiko's per-OS natives are kept off the CLI's own classpath so a cross-platform
 * release doesn't bake in one host's natives.
 *
 * The classpath joins the CLI install's `lib-rcjvm/` (the embedded jvm player + its Compose API
 * deps, staged by the CLI build) with `lib-daemon-desktop/` (the Compose Desktop runtime + Skiko
 * natives the desktop daemon already carries), so the natives are shared rather than bundled twice.
 * When either sidecar is absent (a build that didn't stage them, or a headless host that dropped
 * the desktop lane) [isAvailable] is false and the viewer never lights the chip.
 */
internal object RcJvmServerRenderer {

  private const val MAIN_CLASS = "ee.schimke.composeai.rcembedded.jvm.RcJvmRenderMainKt"
  private const val RENDER_TIMEOUT_SECONDS = 120L

  /**
   * Least budget worth starting a cold one-shot render with. Below this the retry cannot finish (a
   * fresh JVM needs ~2.3s just to boot Compose + Skiko before it draws anything), so spending the
   * remainder on a render that is going to be killed anyway only delays the failure.
   */
  private const val MIN_FALLBACK_SECONDS = 10L
  private const val DRAIN_FLUSH_MILLIS = 1000L

  /**
   * The subprocess classpath: the embedded jvm player (`lib-rcjvm`) plus the desktop Compose +
   * Skiko runtime (`lib-daemon-desktop`). Empty when either sidecar dir is missing.
   */
  private fun classpath(): List<File> {
    val rcjvm = locateBundleSidecarJars("lib-rcjvm")
    val desktop = locateBundleSidecarJars("lib-daemon-desktop")
    if (rcjvm.isEmpty() || desktop.isEmpty()) return emptyList()
    return rcjvm + desktop
  }

  /** True when both sidecar classpaths are present, so a cmp-jvm render can actually be spawned. */
  fun isAvailable(): Boolean = classpath().isNotEmpty()

  /** Human-readable description of where the sidecars were looked for, for error messages. */
  fun unavailableReason(): String =
    "cmp-jvm render needs lib-rcjvm and lib-daemon-desktop on the CLI install " +
      "(${bundleSidecarSearchDescription("lib-rcjvm")}; " +
      "${bundleSidecarSearchDescription("lib-daemon-desktop")})"

  /**
   * Render [docBytes] to [format] at [spec]'s pixel size and density, applying any [seeds] (the
   * serve `rc.<name>=…` knob edits) on top of the document's authored defaults. Reports whether the
   * subprocess is unavailable, timed out, or could not draw the document.
   */
  fun render(
    docBytes: ByteArray,
    spec: RcJvmRenderSpec,
    seeds: Map<String, RemoteNamedValue> = emptyMap(),
    format: Format = Format.PNG,
    /**
     * Which branch a `ColorTheme` operation selects. Defaults to light rather than to the machine's
     * desktop setting: this renderer is headless, so "the host's theme" is not a real question, and
     * a render that changed colour with the build machine's OS would not be reproducible. Documents
     * with no `ColorTheme` are unaffected either way.
     */
    theme: RenderTheme = RenderTheme.LIGHT,
  ): RenderResult {
    val cp = classpath()
    if (cp.isEmpty()) return RenderResult.Unavailable(unavailableReason())

    // One budget for the whole request, spent across both lanes. Without this a pooled worker could
    // burn its full watchdog and *then* hand a fresh one-shot render another full timeout, so a
    // request that used to fail at 120s would hold its caller's render-semaphore slot for ~240s and
    // starve unrelated renders.
    val startNanos = System.nanoTime()
    fun secondsLeft(): Long =
      RENDER_TIMEOUT_SECONDS - (System.nanoTime() - startNanos) / 1_000_000_000L

    // Warm path: a pooled worker draws this on an already-booted JVM (~85 ms) instead of paying
    // Compose Desktop + Skiko startup again (~2.3 s). Only `Unusable` falls through to the one-shot
    // path below — a `Failed` is the player's real answer about this document, and re-rendering it
    // cold would double the cost of every document that cannot be drawn.
    pool(cp)?.let { pool ->
      val pooled =
        pool.render(
          docBytes,
          spec,
          seedLines(seeds).joinToString("\n"),
          format,
          theme,
          secondsLeft(),
        )
      when (pooled) {
        is RcJvmWorkerPool.PoolResult.Ok -> return RenderResult.Ok(pooled.bytes)
        is RcJvmWorkerPool.PoolResult.Failed -> return RenderResult.Failed(pooled.reason)
        is RcJvmWorkerPool.PoolResult.Unusable -> {
          // A pool that declined instantly (disabled, stale sidecar, spawn refused) leaves the
          // budget intact and the cold retry is free to use it. A pool that declined by *timing
          // out* has already spent it — retrying cold would only blow through the deadline the
          // caller is holding a semaphore permit against, so report the failure instead.
          if (secondsLeft() < MIN_FALLBACK_SECONDS) {
            return RenderResult.Failed(
              "${pooled.reason}; no time left in the ${RENDER_TIMEOUT_SECONDS}s render budget " +
                "for a one-shot retry"
            )
          }
        }
      }
    }

    return renderOneShot(cp, docBytes, spec, seeds, format, theme, secondsLeft())
  }

  /**
   * The original process-per-document path, kept as the fallback for every way the pool can decline
   * to serve: pooling switched off, a `lib-rcjvm/` too old to speak the worker protocol, a worker
   * that could not be spawned, or one that broke mid-request. Behaviour here is unchanged, so the
   * worst case of the pool existing is the cost that was already being paid.
   */
  private fun renderOneShot(
    cp: List<File>,
    docBytes: ByteArray,
    spec: RcJvmRenderSpec,
    seeds: Map<String, RemoteNamedValue>,
    format: Format,
    theme: RenderTheme,
    timeoutSeconds: Long = RENDER_TIMEOUT_SECONDS,
  ): RenderResult {
    val input = File.createTempFile("rcjvm-in-", ".rc")
    val output = File.createTempFile("rcjvm-out-", ".${format.wire}")
    val seedsFile = writeSeedsFile(seeds)
    try {
      input.writeBytes(docBytes)
      output.delete() // the subprocess creates it; absence after the run signals failure

      val command = buildList {
        add(javaBin())
        addAll(renderJvmArgs())
        add("-cp")
        add(cp.joinToString(File.pathSeparator) { it.absolutePath })
        add(MAIN_CLASS)
        add("--input")
        add(input.absolutePath)
        add("--output")
        add(output.absolutePath)
        add("--width")
        add(spec.widthPx.toString())
        add("--height")
        add(spec.heightPx.toString())
        add("--density")
        add(spec.density.toString())
        add("--format")
        add(format.wire)
        add("--theme")
        add(theme.wire)
        if (seedsFile != null) {
          add("--seeds")
          add(seedsFile.absolutePath)
        }
      }

      val process =
        ProcessBuilder(command).redirectErrorStream(true).start().also { it.outputStream.close() }
      // Drain the merged stdout/stderr on a daemon thread *concurrently* with the timed wait — a
      // blocking readText() here would wait for EOF, which a hung Skiko/native render never
      // reaches,
      // so the timeout below (and the render-semaphore permit the caller holds) would never
      // release.
      // Mirrors BundleRenderer.runRenderProcess.
      val log = StringBuilder()
      val drain = Thread {
        process.inputStream.bufferedReader().forEachLine { log.appendLine(it) }
      }
        .apply {
          isDaemon = true
          start()
        }
      val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
      if (!finished) {
        process.destroyForcibly()
        drain.join(DRAIN_FLUSH_MILLIS)
        return RenderResult.Failed("cmp-jvm render timed out after ${timeoutSeconds}s")
      }
      // The process exited, so the reader has hit EOF; this join just flushes the last lines.
      drain.join(DRAIN_FLUSH_MILLIS)
      if (process.exitValue() != 0 || !output.isFile || output.length() == 0L) {
        return RenderResult.Failed(
          "cmp-jvm render failed (exit ${process.exitValue()})" +
            log
              .toString()
              .trim()
              .takeIf { it.isNotEmpty() }
              ?.let { ": ${it.lines().last().take(300)}" }
              .orEmpty()
        )
      }
      return RenderResult.Ok(output.readBytes())
    } finally {
      input.delete()
      output.delete()
      seedsFile?.delete()
    }
  }

  /**
   * The JVM flags every cmp-jvm render runs under, shared by the pooled worker and the one-shot
   * subprocess.
   *
   * Shared deliberately, not by coincidence: the font cache directory below decides which typeface
   * a `google:`-named family resolves to, so a pooled worker started without it would draw text
   * differently from the one-shot fallback. Two lanes that are supposed to be interchangeable must
   * boot identically, or "did the pool serve this?" becomes visible in the pixels — exactly the
   * property `RcJvmHotWorkerDeterminismTest` exists to protect.
   */
  private fun renderJvmArgs(): List<String> = buildList {
    add("--enable-native-access=ALL-UNNAMED")
    // Skiko draws offscreen; keep the JVM out of the macOS Dock / app-switcher when spawned
    // on a developer's Mac, matching BundleRenderer's desktop renderer launch.
    add("-Dapple.awt.UIElement=true")
    // The player's `GoogleFontTypefaceResolver` downloads a `google:`-named family into the
    // shared font cache — the same directory the Android and desktop daemons are pointed at,
    // so a family already fetched for another lane is reused rather than re-downloaded. With
    // no cache directory the resolver stays off and the lane substitutes a local face, so this
    // is what makes the cmp-jvm chip show a branded typeface at all. The offline switch is
    // forwarded when this process carries one.
    add("-Dcomposeai.fonts.cacheDir=${composeAiCacheDir("fonts").absolutePath}")
    System.getProperty("composeai.fonts.offline")?.let { add("-Dcomposeai.fonts.offline=$it") }
  }

  /**
   * The process-wide worker pool, created on first use so a cli invocation that never renders a
   * cmp-jvm document never spawns a JVM. Null when pooling is switched off
   * ([RcJvmWorkerPool.SYS_PROP_ENABLED]`=off`), which forces every render down the one-shot path.
   */
  @Volatile private var poolInstance: RcJvmWorkerPool? = null

  private fun pool(cp: List<File>): RcJvmWorkerPool? {
    if (!RcJvmWorkerPool.isEnabled()) return null
    poolInstance?.let {
      return it
    }
    return synchronized(this) {
      poolInstance
        ?: RcJvmWorkerPool(
            classpath = cp,
            javaBin = javaBin(),
            extraJvmArgs = renderJvmArgs(),
            maxWorkers = RcJvmWorkerPool.configuredWorkers(),
            maxRendersPerWorker = RcJvmWorkerPool.configuredMaxRenders(),
            maxWorkerAgeMillis = RcJvmWorkerPool.configuredMaxAgeMillis(),
            renderTimeoutSeconds = RENDER_TIMEOUT_SECONDS,
          )
          .also { created ->
            poolInstance = created
            // Workers outlive any single render, so nothing else would reap them if the cli exits
            // while some are parked.
            Runtime.getRuntime().addShutdownHook(Thread({ created.close() }, "rcjvm-pool-shutdown"))
          }
    }
  }

  /** Releases every pooled worker. Exposed for tests and for an explicit serve shutdown. */
  fun shutdownPool() {
    synchronized(this) {
      poolInstance?.close()
      poolInstance = null
    }
  }

  /**
   * Serialize [seeds] to the line-based format the player reads (`<kind> <base64Name> <value>`,
   * kind ∈ str/float/int/color). Normalizes the wire types the jvm player does not need to
   * distinguish — `dp` collapses to float and `bool` to int, matching the daemon's
   * `applyConnectorOverrides` — and drops a colour whose `#AARRGGBB` string won't parse.
   *
   * One producer for both lanes: the pooled worker receives these lines inline in its request
   * frame, the one-shot subprocess reads them from the file [writeSeedsFile] writes. The parser is
   * `parseSeedText` in the player module.
   */
  internal fun seedLines(seeds: Map<String, RemoteNamedValue>): List<String> {
    if (seeds.isEmpty()) return emptyList()
    val b64 = Base64.getEncoder()
    fun enc(s: String) = b64.encodeToString(s.toByteArray(Charsets.UTF_8))
    return seeds.mapNotNull { (name, value) ->
      val n = enc(name)
      when (value) {
        is RemoteNamedValue.StringValue -> "str $n ${enc(value.value)}"
        is RemoteNamedValue.FloatValue -> "float $n ${value.value}"
        is RemoteNamedValue.DpValue -> "float $n ${value.value}"
        is RemoteNamedValue.IntValue -> "int $n ${value.value}"
        is RemoteNamedValue.BooleanValue -> "int $n ${if (value.value) 1 else 0}"
        is RemoteNamedValue.ColorValue -> rcColorToArgb(value.argb)?.let { "color $n $it" }
      }
    }
  }

  /** [seedLines] as the temp file the one-shot `--seeds` flag points at, or null when empty. */
  private fun writeSeedsFile(seeds: Map<String, RemoteNamedValue>): File? {
    val lines = seedLines(seeds)
    if (lines.isEmpty()) return null
    return File.createTempFile("rcjvm-seeds-", ".txt").also {
      it.writeText(lines.joinToString("\n"))
    }
  }

  /**
   * Parse an rc colour string to an ARGB int, matching the JS lane's `parseRcColor`: strip a
   * leading `#` (or URL-encoded `%23`), treat a 6-digit `#RRGGBB` as **opaque** (prepend `FF` —
   * without it a six-digit value becomes `0x00RRGGBB`, fully transparent), and accept only a
   * resulting 8 hex digits. Null when it won't parse.
   */
  internal fun rcColorToArgb(raw: String): Int? {
    val hex = raw.removePrefix("%23").removePrefix("#")
    val opaque = if (hex.length == 6) "FF$hex" else hex
    return opaque.takeIf { it.length == 8 }?.toLongOrNull(16)?.toInt()
  }

  private fun javaBin(): String {
    val home = System.getProperty("java.home")
    val candidate = File(home, "bin/java")
    return if (candidate.canExecute()) candidate.absolutePath else "java"
  }

  sealed interface RenderResult {
    data class Ok(val bytes: ByteArray) : RenderResult

    data class Failed(val reason: String) : RenderResult

    data class Unavailable(val reason: String) : RenderResult
  }

  enum class Format(val wire: String) {
    PNG("png"),
    SVG("svg"),
  }

  /**
   * The `ColorTheme` branch a cmp-jvm render selects.
   *
   * Deliberately this module's own type rather than `remote-core`'s `Theme` int: the CLI drives the
   * player as a subprocess and carries no compile dependency on it, which is what lets the worker's
   * classpath be staged independently. [wire] and [frame] are the two forms that cross the boundary
   * — a `--theme` argument on the one-shot path, and an int in the pooled worker's request frame,
   * whose values `RcJvmRenderWorkerMain` mirrors.
   */
  enum class RenderTheme(val wire: String, val frame: Int) {
    LIGHT("light", 0),
    DARK("dark", 1),
  }
}

/**
 * The pixel size and density a cmp-jvm render should use — matched to the baked/View-player lane.
 */
data class RcJvmRenderSpec(val widthPx: Int, val heightPx: Int, val density: Float)
