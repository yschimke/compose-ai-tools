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
 * It also carries one thing that is **not** a warning: `phasePinnedCaptures`, a still taken at an
 * author-declared `@SettledPreview(afterMs = …)` coordinate. It rides here because it is the answer
 * to the same question a consumer asks of `unsettledCaptures` — "is this still trustworthy?" — and
 * the two are only useful together: without it, a spinner pinned to a deliberate phase and an
 * ordinary static preview are indistinguishable, so a catalog that wants to assert its renders are
 * settled has nothing to assert against (issue #4829).
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
   * Write [fallbacks], [imageLoads], [unsettled] and [pinned] as the sidecar for [pngFile], or
   * delete any stale sidecar when all four are empty (a now-clean render must not keep yesterday's
   * warning). Best-effort — a write failure prints to stderr but never derails the render,
   * mirroring [RenderErrorSidecar].
   *
   * [pinned] counts towards writing the file even though it is not a warning: it is a positive
   * claim a consumer can assert on, and withholding it whenever the render was otherwise clean
   * would make it available only on previews that also had something wrong with them.
   */
  @JvmOverloads
  fun writeOrDelete(
    pngFile: File,
    fallbacks: List<FontResolutionDiagnostics.FontFallback>,
    imageLoads: List<CoilLoadDiagnostics.UnresolvedLoad> = emptyList(),
    unsettled: List<VisualSettleDiagnostics.UnsettledCapture> = emptyList(),
    pinned: List<VisualSettleDiagnostics.PinnedCapture> = emptyList(),
    unlandedScrollSteps: List<ScrollDriveDiagnostics.UnlandedStep> = emptyList(),
    unverifiedScrollSeams: List<ScrollDriveDiagnostics.UnverifiedSeam> = emptyList(),
    fileSystem: FileSystem = SystemFileSystem,
  ) {
    if (
      fallbacks.isEmpty() &&
        imageLoads.isEmpty() &&
        unsettled.isEmpty() &&
        pinned.isEmpty() &&
        unlandedScrollSteps.isEmpty() &&
        unverifiedScrollSeams.isEmpty()
    ) {
      deleteStale(pngFile)
      return
    }
    try {
      val sidecar = pathFor(pngFile)
      sidecar.parentFile?.mkdirs()
      fileSystem.write(sidecar.path.toPath()) {
        writeUtf8(
          encode(
            fallbacks,
            imageLoads,
            unsettled,
            pinned,
            unlandedScrollSteps,
            unverifiedScrollSeams,
          )
        )
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
   * `unresolvedImages`, `unsettledCaptures`, `phasePinnedCaptures`, `unlandedScrollSteps` and
   * `unverifiedScrollSeams` are additive: a reader that only knows about `fontFallbacks` (every
   * reader that predates issue #2952) keeps working unchanged, and an empty array is still written
   * when there are none of that kind so the shape is stable.
   */
  internal fun encode(
    fallbacks: List<FontResolutionDiagnostics.FontFallback>,
    imageLoads: List<CoilLoadDiagnostics.UnresolvedLoad> = emptyList(),
    unsettled: List<VisualSettleDiagnostics.UnsettledCapture> = emptyList(),
    pinned: List<VisualSettleDiagnostics.PinnedCapture> = emptyList(),
    unlandedScrollSteps: List<ScrollDriveDiagnostics.UnlandedStep> = emptyList(),
    unverifiedScrollSeams: List<ScrollDriveDiagnostics.UnverifiedSeam> = emptyList(),
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
    sb.append("],\"phasePinnedCaptures\":[")
    pinned.forEachIndexed { i, capture ->
      if (i > 0) sb.append(',')
      sb.append('{')
      sb.append("\"role\":").append(jsonString(capture.role)).append(',')
      sb.append("\"outcome\":").append(jsonString("phase_pinned")).append(',')
      sb.append("\"atMs\":").append(capture.atMs).append(',')
      sb.append("\"message\":").append(jsonString(VisualSettleDiagnostics.describe(capture)))
      sb.append('}')
    }
    sb.append("],\"unlandedScrollSteps\":[")
    unlandedScrollSteps.forEachIndexed { i, entry ->
      if (i > 0) sb.append(',')
      val step = entry.step
      sb.append('{')
      sb.append("\"role\":").append(jsonString(entry.role)).append(',')
      sb.append("\"step\":").append(step.index).append(',')
      sb.append("\"requestedPx\":").append(step.requestedPx).append(',')
      sb.append("\"measuredPx\":")
      if (step.measuredPx == null) sb.append("null") else sb.append(step.measuredPx)
      sb.append(',')
      sb.append("\"corrections\":").append(step.corrections).append(',')
      sb.append("\"settled\":").append(step.settled).append(',')
      sb.append("\"message\":").append(jsonString(ScrollDriveDiagnostics.describe(entry)))
      sb.append('}')
    }
    sb.append("],\"unverifiedScrollSeams\":[")
    unverifiedScrollSeams.forEachIndexed { i, entry ->
      if (i > 0) sb.append(',')
      val seam = entry.seam
      sb.append('{')
      sb.append("\"role\":").append(jsonString(entry.role)).append(',')
      sb.append("\"seam\":").append(seam.index).append(',')
      sb.append("\"verdict\":").append(jsonString(seam.verdict.name.lowercase())).append(',')
      sb.append("\"hintPx\":").append(seam.hintPx).append(',')
      sb.append("\"shiftPx\":").append(seam.shiftPx).append(',')
      sb.append("\"overlapRows\":").append(seam.overlapRows).append(',')
      sb.append("\"informativeRows\":").append(seam.informativeRows).append(',')
      sb.append("\"residualPerPixel\":").append(seam.weightedSadPerPixel).append(',')
      sb.append("\"message\":").append(jsonString(ScrollDriveDiagnostics.describe(entry)))
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
