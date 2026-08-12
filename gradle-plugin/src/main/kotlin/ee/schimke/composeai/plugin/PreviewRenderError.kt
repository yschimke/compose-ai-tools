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
  /**
   * One actionable sentence when the render died loading a *native* library rather than running the
   * preview — a missing `libGL.so.1`, or a package-store library dragged into a system-glibc JVM
   * (issue #3690). Null for an ordinary preview throw, which is the overwhelming majority.
   *
   * Worth its own field because this failure class is not per-preview: it takes out every preview
   * in the module with the same cause, and the exception on all but the first says only `Could not
   * initialize class org.jetbrains.skia.Surface`. See `renderer-desktop/.../NativeLoadDiagnosis.kt`
   * for the classifier.
   */
  val diagnosis: String? = null,
  /**
   * Which JVM drew this preview and what it would have searched for native libraries. Recorded on
   * every sidecar: with several JDKs on a box, "which one did Gradle's toolchain resolution
   * actually fork, and did my `LD_LIBRARY_PATH` reach it?" is otherwise unanswerable after the fact
   * — and it was the first question issue #3690 could not answer. Null on sidecars written by a
   * renderer older than this field.
   */
  val runtime: RenderRuntime? = null,
  /** Full stack trace as it would appear in `Throwable.printStackTrace()`. */
  val stackTrace: String,
) {
  companion object {
    const val SCHEMA_V1: String = "compose-preview-error/v1"
  }
}

/** The render JVM's identity and native-library search path, as the renderer saw them. */
@Serializable
data class RenderRuntime(
  /** `java.home` of the JVM that ran the render. */
  val javaHome: String = "",
  val javaVersion: String = "",
  val javaVendor: String = "",
  val osArch: String = "",
  /** `LD_LIBRARY_PATH` as *inherited by the render process*. Empty when it inherited none. */
  val ldLibraryPath: String = "",
)

@Serializable
data class TopFrame(
  /** Source-file basename, e.g. `Previews.kt`. Empty when the frame doesn't carry a file name. */
  val file: String,
  /** 1-based line number, or 0 when the frame doesn't carry one. */
  val line: Int,
  /** Function / method name from the stack frame, e.g. `HomeScreen`. */
  val function: String,
)
