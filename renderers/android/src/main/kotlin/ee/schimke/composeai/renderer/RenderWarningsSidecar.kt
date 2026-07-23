package ee.schimke.composeai.renderer

import ee.schimke.composeai.io.SystemFileSystem
import java.io.File
import okio.FileSystem
import okio.Path.Companion.toPath

/**
 * Per-preview render-*warning* sidecar for the Android (Robolectric) renderer path. The sibling of
 * [RenderErrorSidecar]: where `.error.json` means "the preview failed and there is no valid PNG",
 * `<png>.warnings.json` means "the preview rendered, but something about it is off" — today, one or
 * more downloadable fonts fell back to the platform default (see [FontResolutionDiagnostics]) while
 * `composeai.fonts.failOnFallback` was turned off. The PNG is kept; the warning rides alongside it.
 *
 * Hand-rolled JSON for the same reason as [RenderErrorSidecar]: the renderer-android runtime
 * classpath deliberately doesn't pull `kotlinx-serialization` (renderer-vs-consumer alignment, see
 * `docs/RENDERER_COMPATIBILITY.md`), so we encode a shallow, stable object directly. The gradle
 * plugin reads this back (`ComposePreviewTasks.WarningSidecar`) and the bundle packs it next to the
 * PNG.
 */
internal object RenderWarningsSidecar {

  const val SCHEMA: String = "compose-preview-warnings/v1"

  /** The sidecar that pairs with [pngFile] — same path with `.warnings.json` appended. */
  fun pathFor(pngFile: File): File = File(pngFile.parentFile, pngFile.name + ".warnings.json")

  /**
   * Write [fallbacks] as the warnings sidecar for [pngFile], or delete any stale sidecar when the
   * list is empty (a now-clean render must not keep yesterday's warning). Best-effort — a write
   * failure prints to stderr but never derails the render, mirroring [RenderErrorSidecar].
   */
  fun writeOrDelete(
    pngFile: File,
    fallbacks: List<FontResolutionDiagnostics.FontFallback>,
    fileSystem: FileSystem = SystemFileSystem,
  ) {
    if (fallbacks.isEmpty()) {
      deleteStale(pngFile)
      return
    }
    try {
      val sidecar = pathFor(pngFile)
      sidecar.parentFile?.mkdirs()
      fileSystem.write(sidecar.path.toPath()) { writeUtf8(encode(fallbacks)) }
    } catch (writeFailure: Throwable) {
      System.err.println(
        "Failed to write render-warnings sidecar for ${pngFile.name}: ${writeFailure.message}"
      )
    }
  }

  /** Drop the sidecar for [pngFile] if one exists. Called before each render attempt. */
  fun deleteStale(pngFile: File) {
    val sidecar = pathFor(pngFile)
    if (sidecar.exists()) sidecar.delete()
  }

  /** The JSON body. Pure + internal so a unit test can assert the shape without touching disk. */
  internal fun encode(fallbacks: List<FontResolutionDiagnostics.FontFallback>): String {
    val sb = StringBuilder()
    sb.append('{')
    sb.append("\"schema\":").append(jsonString(SCHEMA)).append(',')
    sb.append("\"fontFallbacks\":[")
    fallbacks.forEachIndexed { i, f ->
      if (i > 0) sb.append(',')
      sb.append('{')
      sb.append("\"family\":").append(jsonString(f.family)).append(',')
      sb.append("\"weight\":").append(f.weight).append(',')
      sb.append("\"italic\":").append(f.italic).append(',')
      sb.append("\"reason\":").append(jsonString(f.reason)).append(',')
      sb.append("\"message\":").append(jsonString(FontResolutionDiagnostics.describe(f)))
      sb.append('}')
    }
    sb.append("]}")
    return sb.toString()
  }

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
