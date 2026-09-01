package ee.schimke.composeai.renderer

import ee.schimke.composeai.io.SystemFileSystem
import java.io.File
import okio.FileSystem
import okio.Path.Companion.toPath

/**
 * Desktop writer for the renderer's `<png>.warnings.json` contract.
 *
 * Desktop has no font, image-load, or visual-quiescence warning producers yet, but an exact
 * `@SettledPreview(afterMs = …)` is a positive declaration that must reach consumers on both
 * backends. Keeping the same schema and all four arrays means a catalog can inspect Android and
 * Desktop renders without a backend-specific parser (issue #4829).
 *
 * Public because `:daemon:desktop` writes the same sidecar after its live render path. The
 * standalone renderer and daemon deliberately share this encoder so their JSON cannot drift.
 */
object DesktopRenderWarningsSidecar {

  const val SCHEMA: String = "compose-preview-warnings/v1"

  /** The sidecar paired with [pngFile]. */
  fun pathFor(pngFile: File): File = File(pngFile.parentFile, pngFile.name + ".warnings.json")

  /**
   * Write one declared phase pin, or remove a stale sidecar when [atMs] is `null`.
   *
   * Best-effort, like the Android writer: diagnostics must never turn a valid PNG into a failed
   * render.
   */
  @JvmOverloads
  fun writePhasePinOrDelete(
    pngFile: File,
    role: String,
    atMs: Long?,
    fileSystem: FileSystem = SystemFileSystem,
  ) {
    if (atMs == null) {
      deleteStale(pngFile)
      return
    }
    try {
      val sidecar = pathFor(pngFile)
      sidecar.parentFile?.mkdirs()
      fileSystem.write(sidecar.path.toPath()) { writeUtf8(encodePhasePin(role, atMs)) }
    } catch (writeFailure: Throwable) {
      System.err.println(
        "Failed to write render-warnings sidecar for ${pngFile.name}: ${writeFailure.message}"
      )
    }
  }

  /** Remove a sidecar left by an earlier pinned render. */
  fun deleteStale(pngFile: File) {
    val sidecar = pathFor(pngFile)
    if (sidecar.exists()) sidecar.delete()
  }

  internal fun encodePhasePin(role: String, atMs: Long): String = buildString {
    append('{')
    append("\"schema\":").append(jsonString(SCHEMA)).append(',')
    append("\"fontFallbacks\":[],")
    append("\"unresolvedImages\":[],")
    append("\"unsettledCaptures\":[],")
    append("\"phasePinnedCaptures\":[{")
    append("\"role\":").append(jsonString(role)).append(',')
    append("\"outcome\":\"phase_pinned\",")
    append("\"atMs\":").append(atMs).append(',')
    append("\"message\":")
      .append(
        jsonString(
          "$role: captured at the declared ${atMs}ms phase; " +
            "a chosen coordinate, not a failed settle."
        )
      )
    append("}]}")
  }

  private fun jsonString(value: String): String =
    buildString(value.length + 2) {
      append('"')
      value.forEach { char ->
        when (char) {
          '"' -> append("\\\"")
          '\\' -> append("\\\\")
          '\b' -> append("\\b")
          '\n' -> append("\\n")
          '\r' -> append("\\r")
          '\t' -> append("\\t")
          else -> if (char < ' ') append("\\u%04x".format(char.code)) else append(char)
        }
      }
      append('"')
    }
}
