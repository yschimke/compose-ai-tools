package ee.schimke.composeai.renderer

import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Per-preview render-error sidecar writer for the Android (Robolectric)
 * renderer path. Mirrors the convention established by the desktop
 * renderer (`renderer-desktop/.../DesktopRendererMain.kt#writeErrorSidecar`)
 * and the schema defined in the gradle plugin
 * (`gradle-plugin/.../PreviewRenderError.kt`).
 *
 * Sibling placement of `<png>.error.json` keeps the renderer's filesystem
 * layout self-contained — no aggregation step in the gradle plugin —
 * and the VS Code extension finds the sidecar by trivial string-concat
 * on the manifest's existing `renderOutput` path.
 *
 * Hand-rolled JSON. The renderer-android runtime classpath deliberately
 * doesn't pull `kotlinx-serialization` (renderer-vs-consumer alignment;
 * see `docs/RENDERER_COMPATIBILITY.md`), so we encode a shallow object
 * directly. The schema is small and stable.
 */
internal object RenderErrorSidecar {

  /** The sidecar that pairs with [pngFile] — same path with `.error.json` appended. */
  fun pathFor(pngFile: File): File = File(pngFile.parentFile, pngFile.name + ".error.json")

  /**
   * Write the sidecar for a preview render that threw [e]. Drops any
   * stale PNG at the same path so the panel doesn't surface yesterday's
   * image alongside today's error message. Best-effort — failures here
   * (filesystem ENOSPC, permissions) print to stderr but don't propagate,
   * since the goal is to *avoid* derailing the test on a per-preview
   * issue.
   */
  fun write(pngFile: File, e: Throwable) {
    try {
      val sidecar = pathFor(pngFile)
      sidecar.parentFile?.mkdirs()
      if (pngFile.exists()) pngFile.delete()
      val stack = StringWriter().also { e.printStackTrace(PrintWriter(it)) }.toString()
      val top = pickTopAppFrame(e)
      val sb = StringBuilder()
      sb.append('{')
      sb.append("\"schema\":\"compose-preview-error/v1\",")
      sb.append("\"exception\":").append(jsonString(e.javaClass.name)).append(',')
      sb.append("\"message\":").append(jsonString(e.message ?: "")).append(',')
      if (top != null) {
        sb.append("\"topAppFrame\":{")
        sb.append("\"file\":").append(jsonString(top.file)).append(',')
        sb.append("\"line\":").append(top.line).append(',')
        sb.append("\"function\":").append(jsonString(top.function))
        sb.append("},")
      }
      sb.append("\"stackTrace\":").append(jsonString(stack))
      sb.append('}')
      sidecar.writeText(sb.toString())
    } catch (sidecarWriteFailure: Throwable) {
      System.err.println(
        "Failed to write render-error sidecar for ${pngFile.name}: ${sidecarWriteFailure.message}"
      )
    }
  }

  /**
   * Drop the sidecar for [pngFile] if one exists, regardless of whether
   * the prior render succeeded. Called at the start of every render
   * attempt so a fresh successful render doesn't leave yesterday's
   * `.error.json` haunting the panel.
   */
  fun deleteStale(pngFile: File) {
    val sidecar = pathFor(pngFile)
    if (sidecar.exists()) sidecar.delete()
  }

  /**
   * The first stack frame attributable to user code. Skips Compose
   * scaffold, Kotlin stdlib, JDK frames, the renderer's own glue, the
   * Robolectric harness, and JUnit so the `(at File.kt:42)` annotation
   * the extension surfaces points where the bug actually is rather than
   * deep into framework internals.
   *
   * Same idea as the desktop renderer's `pickTopAppFrame` plus the
   * Android-specific framework prefixes that don't show up there
   * (Robolectric, JUnit, Roborazzi).
   */
  private fun pickTopAppFrame(e: Throwable): TopFrame? {
    val skipPrefixes =
      listOf(
        "androidx.compose.",
        "androidx.lifecycle.",
        "androidx.activity.",
        "androidx.test.",
        "kotlin.",
        "kotlinx.",
        "java.",
        "javax.",
        "jdk.",
        "sun.",
        "ee.schimke.composeai.renderer.",
        "org.robolectric.",
        "org.junit.",
        "junit.",
        "com.github.takahirom.roborazzi.",
      )
    for (frame in e.stackTrace) {
      val cls = frame.className
      if (skipPrefixes.any { cls.startsWith(it) }) continue
      return TopFrame(
        file = frame.fileName ?: "",
        line = frame.lineNumber.coerceAtLeast(0),
        function = frame.methodName ?: "",
      )
    }
    return null
  }

  private data class TopFrame(val file: String, val line: Int, val function: String)

  private fun jsonString(s: String): String {
    val sb = StringBuilder(s.length + 2)
    sb.append('"')
    for (c in s) {
      when (c) {
        '"' -> sb.append("\\\"")
        '\\' -> sb.append("\\\\")
        '\b' -> sb.append("\\b")
        '\n' -> sb.append("\\n")
        '\r' -> sb.append("\\r")
        '\t' -> sb.append("\\t")
        else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
      }
    }
    sb.append('"')
    return sb.toString()
  }
}
