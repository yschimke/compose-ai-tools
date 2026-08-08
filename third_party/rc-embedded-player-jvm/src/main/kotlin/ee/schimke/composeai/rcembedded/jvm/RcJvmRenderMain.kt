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

package ee.schimke.composeai.rcembedded.jvm

import java.io.File
import kotlin.system.exitProcess

/**
 * A one-shot command-line entry point that renders a captured Remote Compose document to a PNG or
 * layered SVG file.
 *
 * This is what the `compose-preview serve` cmp-jvm lane spawns as an isolated subprocess: the
 * embedded desktop player needs Compose Desktop + Skiko's per-OS natives on its classpath, which
 * the cli deliberately keeps out of its own runtime (so a cross-platform release does not bake in
 * one host's natives). Running it in a subprocess off a purpose-built classpath — the same
 * isolation the desktop render daemon uses — keeps that dependency out of the cli while still
 * letting serve produce a cmp-jvm PNG or structural SVG.
 *
 * Contract (kept dead simple — a file in, a file out, an exit code): the caller writes the document
 * to [ARG_INPUT], names the pixel size, density and format, and reads the artifact from
 * [ARG_OUTPUT] on exit
 * 0. Any failure prints one line to stderr and exits non-zero, so the caller distinguishes
 *    "rendered" from "the player could not draw this document" without parsing stdout.
 *
 * ```
 * java -cp <lib-rcjvm-jars> ee.schimke.composeai.rcembedded.jvm.RcJvmRenderMainKt \
 *   --input <doc.rc> --output <out.png> --width 640 --height 480 [--density 2.0] [--format png|svg]
 * ```
 */
fun main(args: Array<String>) {
  val opts = parseArgs(args)
  val input = opts[ARG_INPUT]
  val output = opts[ARG_OUTPUT]
  val width = opts[ARG_WIDTH]?.toIntOrNull()
  val height = opts[ARG_HEIGHT]?.toIntOrNull()
  val density = opts[ARG_DENSITY]?.toFloatOrNull() ?: DEFAULT_DENSITY
  val format = opts[ARG_FORMAT]?.lowercase() ?: FORMAT_PNG

  if (input == null || output == null || width == null || height == null) {
    System.err.println(
      "usage: RcJvmRenderMain --input <doc.rc> --output <out.png> --width <px> --height <px> " +
        "[--density <f>] [--seeds <file>] [--format png|svg]"
    )
    exitProcess(2)
  }
  if (width <= 0 || height <= 0) {
    System.err.println("width and height must be positive (got ${width}x${height})")
    exitProcess(2)
  }
  if (format !in setOf(FORMAT_PNG, FORMAT_SVG)) {
    System.err.println("format must be png or svg (got $format)")
    exitProcess(2)
  }

  val bytes =
    try {
      File(input).readBytes()
    } catch (e: Exception) {
      System.err.println("could not read input document $input: ${e.message}")
      exitProcess(3)
    }

  val seeds =
    try {
      opts[ARG_SEEDS]?.let { readSeeds(File(it)) } ?: emptyMap()
    } catch (e: Exception) {
      // A malformed seed file must not fail the whole render — fall back to the base document, the
      // same posture as an unsupported op. The reason is logged for the caller's failure tail.
      System.err.println("ignoring unreadable seed file ${opts[ARG_SEEDS]}: ${e.message}")
      emptyMap()
    }

  val artifact =
    try {
      when (format) {
        FORMAT_SVG -> renderRemoteDocumentToSvg(bytes, width, height, density, seeds)
        else -> renderRemoteDocumentToPng(bytes, width, height, density, seeds)
      }
    } catch (t: Throwable) {
      // Any render failure — a malformed document, a missing native, an unsupported op — is
      // reported
      // as one stderr line and a non-zero exit, never a stack trace on stdout the caller would
      // mistake for artifact bytes.
      System.err.println("${t::class.java.simpleName}: ${t.message}")
      exitProcess(1)
    }

  try {
    File(output).writeBytes(artifact)
  } catch (e: Exception) {
    System.err.println("could not write output $output: ${e.message}")
    exitProcess(3)
  }
}

private const val ARG_INPUT = "--input"
private const val ARG_OUTPUT = "--output"
private const val ARG_WIDTH = "--width"
private const val ARG_HEIGHT = "--height"
private const val ARG_DENSITY = "--density"
private const val ARG_SEEDS = "--seeds"
private const val ARG_FORMAT = "--format"
private const val FORMAT_PNG = "png"
private const val FORMAT_SVG = "svg"
private const val DEFAULT_DENSITY = 2f

/**
 * Read the knob-seed file the serve side wrote. The format — and the skip-don't-fail posture for
 * malformed lines — is documented on [parseSeedText], which the pooled worker shares.
 */
private fun readSeeds(file: File): Map<String, RcSeed> = parseSeedText(file.readText())

/**
 * Parse `--flag value` pairs; unknown or dangling flags are ignored (the caller controls the argv).
 */
private fun parseArgs(args: Array<String>): Map<String, String> {
  val map = HashMap<String, String>()
  var i = 0
  while (i < args.size) {
    val a = args[i]
    if (a.startsWith("--") && i + 1 < args.size) {
      map[a] = args[i + 1]
      i += 2
    } else {
      i += 1
    }
  }
  return map
}
