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

import ee.schimke.composeai.cli.bundleSidecarSearchDescription
import ee.schimke.composeai.cli.locateBundleSidecarJars
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
  ): RenderResult {
    val cp = classpath()
    if (cp.isEmpty()) return RenderResult.Unavailable(unavailableReason())

    val input = File.createTempFile("rcjvm-in-", ".rc")
    val output = File.createTempFile("rcjvm-out-", ".${format.wire}")
    val seedsFile = writeSeedsFile(seeds)
    try {
      input.writeBytes(docBytes)
      output.delete() // the subprocess creates it; absence after the run signals failure

      val command = buildList {
        add(javaBin())
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
      val drain =
        Thread { process.inputStream.bufferedReader().forEachLine { log.appendLine(it) } }
          .apply {
            isDaemon = true
            start()
          }
      val finished = process.waitFor(RENDER_TIMEOUT_SECONDS, TimeUnit.SECONDS)
      if (!finished) {
        process.destroyForcibly()
        drain.join(DRAIN_FLUSH_MILLIS)
        return RenderResult.Failed("cmp-jvm render timed out after ${RENDER_TIMEOUT_SECONDS}s")
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
   * Serialize [seeds] to the line-based file `RcJvmRenderMain` reads (`<kind> <base64Name>
   * <value>`, kind ∈ str/float/int/color), or null when there is nothing to seed. Normalizes the
   * wire types the jvm player does not need to distinguish — `dp` collapses to float and `bool` to
   * int, matching the daemon's `applyConnectorOverrides` — and drops a colour whose `#AARRGGBB`
   * string won't parse.
   */
  private fun writeSeedsFile(seeds: Map<String, RemoteNamedValue>): File? {
    if (seeds.isEmpty()) return null
    val b64 = Base64.getEncoder()
    fun enc(s: String) = b64.encodeToString(s.toByteArray(Charsets.UTF_8))
    val lines = seeds.mapNotNull { (name, value) ->
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
}

/**
 * The pixel size and density a cmp-jvm render should use — matched to the baked/View-player lane.
 */
data class RcJvmRenderSpec(val widthPx: Int, val heightPx: Int, val density: Float)
