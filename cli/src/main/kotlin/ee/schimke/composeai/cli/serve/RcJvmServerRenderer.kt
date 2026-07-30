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
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Renders a captured Remote Compose document to PNG for the serve viewer's **cmp-jvm** chip, by
 * spawning the embedded desktop player ([ee.schimke.composeai.rcembedded.jvm] `RcJvmRenderMain`) as
 * a one-shot subprocess off an isolated classpath — the same subprocess isolation `BundleRenderer`
 * uses for the desktop `@Preview` renderer, and for the same reason: Compose Desktop + Skiko's
 * per-OS natives are kept off the CLI's own classpath so a cross-platform release doesn't bake in
 * one host's natives.
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
   * Render [docBytes] to a PNG at [spec]'s pixel size and density, or null when the subprocess is
   * unavailable, times out, or the player could not draw the document (it prints the reason to
   * stderr, surfaced in [RenderResult.Failed]).
   */
  fun render(docBytes: ByteArray, spec: RcJvmRenderSpec): RenderResult {
    val cp = classpath()
    if (cp.isEmpty()) return RenderResult.Unavailable(unavailableReason())

    val input = File.createTempFile("rcjvm-in-", ".rc")
    val output = File.createTempFile("rcjvm-out-", ".png")
    try {
      input.writeBytes(docBytes)
      output.delete() // the subprocess creates it; absence after the run signals failure

      val command = buildList {
        add(javaBin())
        add("--enable-native-access=ALL-UNNAMED")
        // Skiko draws offscreen; keep the JVM out of the macOS Dock / app-switcher when spawned
        // on a developer's Mac, matching BundleRenderer's desktop renderer launch.
        add("-Dapple.awt.UIElement=true")
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
      }

      val process =
        ProcessBuilder(command).redirectErrorStream(true).start().also { it.outputStream.close() }
      val log = process.inputStream.bufferedReader().use { it.readText() }
      val finished = process.waitFor(RENDER_TIMEOUT_SECONDS, TimeUnit.SECONDS)
      if (!finished) {
        process.destroyForcibly()
        return RenderResult.Failed("cmp-jvm render timed out after ${RENDER_TIMEOUT_SECONDS}s")
      }
      if (process.exitValue() != 0 || !output.isFile || output.length() == 0L) {
        return RenderResult.Failed(
          "cmp-jvm render failed (exit ${process.exitValue()})" +
            log
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
    }
  }

  private fun javaBin(): String {
    val home = System.getProperty("java.home")
    val candidate = File(home, "bin/java")
    return if (candidate.canExecute()) candidate.absolutePath else "java"
  }

  sealed interface RenderResult {
    data class Ok(val png: ByteArray) : RenderResult

    data class Failed(val reason: String) : RenderResult

    data class Unavailable(val reason: String) : RenderResult
  }
}

/**
 * The pixel size and density a cmp-jvm render should use — matched to the baked/View-player lane.
 */
data class RcJvmRenderSpec(val widthPx: Int, val heightPx: Int, val density: Float)
