package ee.schimke.composeai.plugin

import kotlinx.serialization.Serializable

/**
 * Per-preview render-error sidecar. Written by the renderer (desktop today; Android Robolectric
 * path is a planned follow-up) when a preview function throws at render time so the VS Code
 * extension can surface a structured message on the failing card instead of the generic "Render
 * failed — see Output ▸ Compose Preview" message that lacked the actual exception text.
 *
 * The file lives next to where the PNG would have gone — same path with `.error.json` appended,
 * e.g. `renders/HomeScreen.png.error.json`. Sibling placement keeps the renderer's filesystem
 * layout self-contained: no separate aggregation step in the gradle plugin, and the extension can
 * find the sidecar by trivial string concatenation on the manifest's existing `renderOutput` path.
 *
 * Schema is versioned via [schema]; bumps are mechanical (extension reads the prefix and ignores
 * files whose schema version it doesn't recognise).
 */
@Serializable
data class PreviewRenderError(
  /** Stable version tag — `compose-preview-error/v1`. */
  val schema: String = SCHEMA_V1,
  /** FQN of the thrown exception, e.g. `java.lang.NullPointerException`. */
  val exception: String,
  /**
   * The exception's message, or empty string when the throwable carried no message. Empty rather
   * than null so the JSON shape is uniform — extension code can string-concatenate without null
   * checks.
   */
  val message: String,
  /**
   * The first stack frame the renderer attributes to user code (i.e. not `androidx.compose.*`,
   * `kotlinx.coroutines.*`, `java.*`, or the renderer scaffold itself). Surfaced on the card as `at
   * <file>:<line>` plus the function name when available — same heuristic LeakCanary uses to point
   * past framework frames to the offending call site. `null` when the heuristic finds no match
   * (very deep framework throw, native crash).
   */
  val topAppFrame: TopFrame? = null,
  /** Full stack trace as it would appear in `Throwable.printStackTrace()`. */
  val stackTrace: String,
) {
  companion object {
    const val SCHEMA_V1: String = "compose-preview-error/v1"
  }
}

@Serializable
data class TopFrame(
  /** Source-file basename, e.g. `Previews.kt`. Empty when the frame doesn't carry a file name. */
  val file: String,
  /** 1-based line number, or 0 when the frame doesn't carry one. */
  val line: Int,
  /** Function / method name from the stack frame, e.g. `HomeScreen`. */
  val function: String,
)
