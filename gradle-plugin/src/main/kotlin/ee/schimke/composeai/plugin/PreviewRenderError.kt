package ee.schimke.composeai.plugin

import kotlinx.serialization.Serializable

/**
 * Per-preview render-error sidecar, written by the renderer when a preview throws so VS Code can
 * show the actual exception on the card. Lives beside where the PNG would be
 * (`renders/HomeScreen.png.error.json`), so consumers find it from `renderOutput` with no
 * aggregation step. Versioned via [schema]; readers ignore unknown versions.
 */
@Serializable
data class PreviewRenderError(
  /** Stable version tag — `compose-preview-error/v1`. */
  val schema: String = SCHEMA_V1,
  /** FQN of the thrown exception, e.g. `java.lang.NullPointerException`. */
  val exception: String,
  /** Exception message, empty (not null) when absent, for a uniform shape. */
  val message: String,
  /**
   * First frame attributed to user code (skipping Compose, coroutines, `java.*` and renderer
   * frames), shown as `at <file>:<line>`; `null` when none matches.
   */
  val topAppFrame: TopFrame? = null,
  /**
   * One actionable sentence when a *native* library failed to load (missing `libGL.so.1`, store
   * glibc mismatch, #3690); null for ordinary throws. Separate because it takes out the whole
   * module, and later previews only say `Could not initialize class …Surface`. See
   * `NativeLoadDiagnosis.kt`.
   */
  val diagnosis: String? = null,
  /**
   * Which JVM drew this and its native search path, so "which JDK forked, and did `LD_LIBRARY_PATH`
   * reach it?" is answerable (#3690). Null from older renderers.
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
