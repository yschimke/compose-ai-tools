package ee.schimke.composeai.renderer

import ee.schimke.composeai.io.SystemFileSystem
import java.io.File
import okio.FileSystem
import okio.Path.Companion.toPath

/**
 * Per-preview render-*warning* sidecar for the Android (Robolectric) renderer path. The sibling of
 * [RenderErrorSidecar]: where `.error.json` means "the preview failed and there is no valid PNG",
 * `<png>.warnings.json` means "the preview rendered, but something about it is off". Three kinds
 * today:
 * - one or more downloadable fonts fell back to the platform default (see
 *   [FontResolutionDiagnostics]) while `composeai.fonts.failOnFallback` was turned off;
 * - one or more coil image requests didn't resolve before the capture (see [CoilLoadDiagnostics]),
 *   so the PNG has a blank, possibly layout-collapsing hole where artwork should be;
 * - a still capture's quiescence probe ran out its sample budget (see [VisualSettleDiagnostics]),
 *   so the PNG is a half-drawn frame or the frame before a reveal that hadn't started.
 *
 * Either way the PNG is kept and the warning rides alongside it.
 *
 * Hand-rolled JSON for the same reason as [RenderErrorSidecar]: the renderer-android runtime
 * classpath deliberately doesn't pull `kotlinx-serialization` (renderer-vs-consumer alignment, see
 * `docs/RENDERER_COMPATIBILITY.md`), so we encode a shallow, stable object directly. The gradle
 * plugin reads this back (`ComposePreviewTasks.WarningSidecar`) and the bundle packs it next to the
 * PNG.
 */
// Public (not `internal`) so the CLI `bundle pack` / serve daemon's `:daemon:android`
// `RenderEngine`
// can write the warnings sidecar on the daemon render path, the same as the gradle-plugin's
// `RobolectricRenderTest` does on its path.
object RenderWarningsSidecar {

  const val SCHEMA: String = "compose-preview-warnings/v1"

  /** The sidecar that pairs with [pngFile] — same path with `.warnings.json` appended. */
  fun pathFor(pngFile: File): File = File(pngFile.parentFile, pngFile.name + ".warnings.json")

  /**
   * Write [fallbacks], [imageLoads] and [unsettled] as the warnings sidecar for [pngFile], or
   * delete any stale sidecar when all three are empty (a now-clean render must not keep yesterday's
   * warning). Best-effort — a write failure prints to stderr but never derails the render,
   * mirroring [RenderErrorSidecar].
   */
  @JvmOverloads
  fun writeOrDelete(
    pngFile: File,
    fallbacks: List<FontResolutionDiagnostics.FontFallback>,
    imageLoads: List<CoilLoadDiagnostics.UnresolvedLoad> = emptyList(),
    unsettled: List<VisualSettleDiagnostics.UnsettledCapture> = emptyList(),
    fileSystem: FileSystem = SystemFileSystem,
  ) {
    if (fallbacks.isEmpty() && imageLoads.isEmpty() && unsettled.isEmpty()) {
      deleteStale(pngFile)
      return
    }
    try {
      val sidecar = pathFor(pngFile)
      sidecar.parentFile?.mkdirs()
      fileSystem.write(sidecar.path.toPath()) {
        writeUtf8(encode(fallbacks, imageLoads, unsettled))
      }
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

  /**
   * The JSON body. Pure + internal so a unit test can assert the shape without touching disk.
   *
   * `unresolvedImages` and `unsettledCaptures` are additive: a reader that only knows about
   * `fontFallbacks` (every reader that predates issue #2952) keeps working unchanged, and an empty
   * array is still written when there are no warnings of that kind so the shape is stable.
   */
  internal fun encode(
    fallbacks: List<FontResolutionDiagnostics.FontFallback>,
    imageLoads: List<CoilLoadDiagnostics.UnresolvedLoad> = emptyList(),
    unsettled: List<VisualSettleDiagnostics.UnsettledCapture> = emptyList(),
  ): String {
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
    sb.append("],\"unresolvedImages\":[")
    imageLoads.forEachIndexed { i, load ->
      if (i > 0) sb.append(',')
      sb.append('{')
      sb.append("\"model\":").append(jsonString(load.model)).append(',')
      sb.append("\"outcome\":").append(jsonString(load.outcome.name.lowercase())).append(',')
      sb.append("\"detail\":")
      if (load.detail == null) sb.append("null") else sb.append(jsonString(load.detail))
      sb.append(',')
      sb.append("\"message\":").append(jsonString(CoilLoadDiagnostics.describe(load)))
      sb.append('}')
    }
    sb.append("],\"unsettledCaptures\":[")
    unsettled.forEachIndexed { i, capture ->
      if (i > 0) sb.append(',')
      sb.append('{')
      sb.append("\"role\":").append(jsonString(capture.role)).append(',')
      sb.append("\"outcome\":").append(jsonString(capture.outcome.name.lowercase())).append(',')
      sb.append("\"samples\":").append(capture.samples).append(',')
      sb.append("\"message\":").append(jsonString(VisualSettleDiagnostics.describe(capture)))
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
