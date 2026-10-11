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
import java.io.File
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * Renders a captured Remote Compose document to PNG or layered SVG for the serve viewer's cmp-jvm
 * chip, via the CMP render worker (`:rc-render-jvm`, drawing through `rc-player-compose`) as a
 * pooled or one-shot subprocess. Isolated like `BundleRenderer`, so Compose Desktop + Skiko natives
 * stay off the CLI's classpath.
 *
 * The classpath joins `lib-rcjvm/` with `lib-daemon-desktop/` (sharing its Compose runtime and
 * natives). Without either, [isAvailable] is false and the chip never lights.
 */
// Public because `:server` call sites live in another module; not a widened API by intent.
public object RcJvmServerRenderer {

  private const val MAIN_CLASS = "ee.schimke.composeai.rcjvm.RcJvmRenderMainKt"
  private const val RENDER_TIMEOUT_SECONDS = 120L

  /** Least budget worth starting a cold one-shot render with (a fresh JVM needs ~2.3s to boot). */
  private const val MIN_FALLBACK_SECONDS = 10L
  private const val DRAIN_FLUSH_MILLIS = 1000L

  /** `lib-rcjvm` plus `lib-daemon-desktop`; empty when either is missing. */
  private fun classpath(): List<File> {
    val rcjvm = locateBundleSidecarJars("lib-rcjvm")
    val desktop = locateBundleSidecarJars("lib-daemon-desktop")
    if (rcjvm.isEmpty() || desktop.isEmpty()) return emptyList()
    return rcjvm + desktop
  }

  /** True when both sidecar classpaths are present, so a cmp-jvm render can actually be spawned. */
  public fun isAvailable(): Boolean = classpath().isNotEmpty()

  /** Human-readable description of where the sidecars were looked for, for error messages. */
  public fun unavailableReason(): String =
    "cmp-jvm render needs lib-rcjvm and lib-daemon-desktop on the CLI install " +
      "(${bundleSidecarSearchDescription("lib-rcjvm")}; " +
      "${bundleSidecarSearchDescription("lib-daemon-desktop")})"

  /**
   * Render [docBytes] to [format] at [spec]'s size and density, applying [seeds] (serve
   * `rc.<name>=…` edits) over the authored defaults.
   *
   * [RcJvmRenderSpec.fontScale] reaches the player as `Density(density, fontScale)`. The pooled
   * frame has no font-scale field, so scaled requests take the one-shot path (`--fontScale`).
   */
  public fun render(
    docBytes: ByteArray,
    spec: RcJvmRenderSpec,
    seeds: Map<String, RemoteNamedValue> = emptyMap(),
    format: Format = Format.PNG,
    /** Defaults to light rather than the host's theme, so headless renders are reproducible. */
    theme: RenderTheme = RenderTheme.LIGHT,
  ): RenderResult {
    val cp = classpath()
    if (cp.isEmpty()) return RenderResult.Unavailable(unavailableReason())

    // One budget across both lanes, so a pool timeout plus a cold retry can't hold the caller's
    // render slot for twice the timeout.
    val startNanos = System.nanoTime()
    fun secondsLeft(): Long =
      RENDER_TIMEOUT_SECONDS - (System.nanoTime() - startNanos) / 1_000_000_000L

    // Warm path (~85 ms vs ~2.3 s cold). Only `Unusable` falls through to the one-shot path;
    // `Failed` is the player's real answer. Scaled-text requests skip the pool, which can't express
    // font scale.
    if (!spec.scalesText) {
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
            // A pool that declined by timing out has spent the budget; report instead of retrying
            // cold.
            if (secondsLeft() < MIN_FALLBACK_SECONDS) {
              return RenderResult.Failed(
                "${pooled.reason}; no time left in the ${RENDER_TIMEOUT_SECONDS}s render budget " +
                  "for a one-shot retry"
              )
            }
          }
        }
      }
    }

    return renderOneShot(cp, docBytes, spec, seeds, format, theme, secondsLeft())
  }

  /**
   * The process-per-document path, the fallback whenever the pool declines (disabled, old
   * `lib-rcjvm/`, spawn failure, worker broke).
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
        add("--fontScale")
        add(spec.fontScale.toString())
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
      // Drain output concurrently with the timed wait; a hung native render never reaches EOF, so a
      // blocking read would never time out. Mirrors BundleRenderer.runRenderProcess.
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
   * JVM flags shared by the pooled worker and the one-shot subprocess. They must boot identically
   * (e.g. the font cache dir decides typefaces), or the pixels would reveal which lane served them
   * (`RcJvmHotWorkerDeterminismTest`).
   */
  private fun renderJvmArgs(): List<String> = buildList {
    add("--enable-native-access=ALL-UNNAMED")
    // Keep the offscreen JVM out of the macOS Dock.
    add("-Dapple.awt.UIElement=true")
    // Use the same vendored faces as the Wasm player and parity run; without them text shapes at a
    // different width.
    rcFontsDir()?.let { add("-Dcomposeai.rcjvm.fontsDir=${it.absolutePath}") }
  }

  /**
   * The player's fonts dir: `-Dcomposeai.rcjvm.fontsDir`, else `rc-player-wasm/fonts/` in the
   * install. Null falls back to the player's default face.
   */
  private fun rcFontsDir(): File? {
    System.getProperty("composeai.rcjvm.fontsDir")
      ?.let(::File)
      ?.takeIf { it.isDirectory }
      ?.let {
        return it
      }
    val appHome = System.getProperty("composeai.cli.appHome") ?: System.getenv("APP_HOME")
    val fromHome = appHome?.let { File(it, "rc-player-wasm/fonts") }
    if (fromHome?.isDirectory == true) return fromHome
    val firstJar =
      System.getProperty("java.class.path")?.split(File.pathSeparator)?.firstOrNull {
        it.endsWith(".jar")
      }
    return firstJar
      ?.let { File(it).parentFile?.parentFile }
      ?.let { File(it, "rc-player-wasm/fonts") }
      ?.takeIf { it.isDirectory }
  }

  /**
   * The process-wide worker pool, created on first use. Null when disabled
   * ([RcJvmWorkerPool.SYS_PROP_ENABLED]`=off`), forcing the one-shot path.
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
            // Parked workers outlive renders, so reap them on exit.
            Runtime.getRuntime().addShutdownHook(Thread({ created.close() }, "rcjvm-pool-shutdown"))
          }
    }
  }

  /** Releases every pooled worker. Exposed for tests and for an explicit serve shutdown. */
  public fun shutdownPool() {
    synchronized(this) {
      poolInstance?.close()
      poolInstance = null
    }
  }

  /**
   * Serialize [seeds] as `<kind> <base64Name> <value>` lines (kind ∈ str/float/int/color),
   * collapsing `dp` to float and `bool` to int like the daemon, and dropping unparseable colours.
   * Shared by both lanes (inline frame or [writeSeedsFile]); parsed by `parseSeedText` in the
   * player.
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
   * Parse an rc colour to ARGB like the JS lane's `parseRcColor`: strip `#` or `%23`, treat 6
   * digits as opaque (prepend `FF`), require 8 hex digits. Null when unparseable.
   */
  // Public because `:server` call sites live in another module; not a widened API by intent.
  public fun rcColorToArgb(raw: String): Int? {
    val hex = raw.removePrefix("%23").removePrefix("#")
    val opaque = if (hex.length == 6) "FF$hex" else hex
    return opaque.takeIf { it.length == 8 }?.toLongOrNull(16)?.toInt()
  }

  private fun javaBin(): String {
    val home = System.getProperty("java.home")
    val candidate = File(home, "bin/java")
    return if (candidate.canExecute()) candidate.absolutePath else "java"
  }

  public sealed interface RenderResult {
    public data class Ok(val bytes: ByteArray) : RenderResult

    public data class Failed(val reason: String) : RenderResult

    public data class Unavailable(val reason: String) : RenderResult
  }

  public enum class Format(public val wire: String) {
    PNG("png"),
    SVG("svg"),
  }

  /**
   * The `ColorTheme` branch a cmp-jvm render selects. Our own type rather than `remote-core`'s,
   * since the player is a subprocess with no compile dependency. [wire] is the one-shot `--theme`
   * value, [frame] the pooled request int (mirrored by `RcJvmRenderWorkerMain`).
   */
  public enum class RenderTheme(public val wire: String, public val frame: Int) {
    LIGHT("light", 0),
    DARK("dark", 1),
  }
}

/**
 * Pixel size, density and font scale for a cmp-jvm render, matched to the baked lane. See
 * [RcJvmServerRenderer.render] for how [fontScale] reaches the player.
 */
public data class RcJvmRenderSpec(
  val widthPx: Int,
  val heightPx: Int,
  val density: Float,
  /**
   * Text multiplier (`Density.fontScale`); the player uses `ID_FONT_SIZE = 14 × density ×
   * fontScale`.
   */
  val fontScale: Float = 1f,
) {
  /** True when [fontScale] asks for something the un-scaled default does not already give. */
  internal val scalesText: Boolean
    get() = fontScale != 1f
}
