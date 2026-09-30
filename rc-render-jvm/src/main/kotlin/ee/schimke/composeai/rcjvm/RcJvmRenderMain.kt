package ee.schimke.composeai.rcjvm

import java.io.File
import kotlin.system.exitProcess

/**
 * A one-shot command-line entry point that renders a captured Remote Compose document to a PNG or
 * layered SVG file.
 *
 * This is what the `compose-preview serve` cmp-jvm lane spawns as an isolated subprocess: the
 * desktop player needs Compose Desktop + Skiko's per-OS natives on its classpath, which the CLI
 * keeps out of its own runtime so a cross-platform release does not bake in one host's natives.
 *
 * Contract (kept dead simple — a file in, a file out, an exit code): the caller writes the document
 * to `--input`, names the pixel size, density and format, and reads the artifact from `--output` on
 * exit 0. Any failure prints one line to stderr and exits non-zero, so the caller distinguishes
 * "rendered" from "the player could not draw this document" without parsing stdout.
 *
 * ```
 * java -cp <lib-rcjvm jars + lib-daemon-desktop jars> ee.schimke.composeai.rcjvm.RcJvmRenderMainKt \
 *   --input <doc.rc> --output <out.png> --width 640 --height 480 [--density 2.0]
 *   [--fontScale 1.0] [--format png|svg] [--theme light|dark] [--seeds <file>]
 * ```
 */
public fun main(args: Array<String>) {
  val opts = parseArgs(args)
  val input = opts[ARG_INPUT]
  val output = opts[ARG_OUTPUT]
  val width = opts[ARG_WIDTH]?.toIntOrNull()
  val height = opts[ARG_HEIGHT]?.toIntOrNull()
  val density = opts[ARG_DENSITY]?.toFloatOrNull() ?: DEFAULT_DENSITY
  // A non-positive or unparseable value is the unscaled default rather than an error: this flag
  // reaches us from a `?fontScale=` query string the serve layer has already validated, and a
  // render should not die over a display axis.
  val fontScale = opts[ARG_FONT_SCALE]?.toFloatOrNull()?.takeIf { it > 0f } ?: DEFAULT_FONT_SCALE
  val format = opts[ARG_FORMAT]?.lowercase() ?: FORMAT_PNG
  val dark = opts[ARG_THEME]?.lowercase() == THEME_DARK

  if (input == null || output == null || width == null || height == null) {
    System.err.println(
      "usage: RcJvmRenderMain --input <doc.rc> --output <out.png> --width <px> --height <px> " +
        "[--density <f>] [--fontScale <f>] [--seeds <file>] [--format png|svg] [--theme light|dark]"
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
      opts[ARG_SEEDS]?.let { parseSeedText(File(it).readText()) } ?: emptyMap()
    } catch (e: Exception) {
      // A malformed seed file must not fail the whole render — fall back to the base document, the
      // same posture as an unsupported op. The reason is logged for the caller's failure tail.
      System.err.println("ignoring unreadable seed file ${opts[ARG_SEEDS]}: ${e.message}")
      emptyMap()
    }

  val artifact =
    try {
      when (format) {
        FORMAT_SVG ->
          renderRemoteDocumentToSvg(bytes, width, height, density, seeds, dark, fontScale)
        else -> renderRemoteDocumentToPng(bytes, width, height, density, seeds, dark, fontScale)
      }
    } catch (t: Throwable) {
      // Any render failure — a malformed document, a missing native, an unsupported op — is one
      // stderr line and a non-zero exit, never a stack trace on stdout the caller would mistake
      // for artifact bytes.
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
private const val ARG_FONT_SCALE = "--fontScale"
private const val ARG_SEEDS = "--seeds"
private const val ARG_FORMAT = "--format"
private const val ARG_THEME = "--theme"
private const val THEME_DARK = "dark"
private const val FORMAT_PNG = "png"
private const val FORMAT_SVG = "svg"
private const val DEFAULT_DENSITY = 2f
private const val DEFAULT_FONT_SCALE = 1f

/** Parse `--flag value` pairs; unknown or dangling flags are ignored (the caller owns the argv). */
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
