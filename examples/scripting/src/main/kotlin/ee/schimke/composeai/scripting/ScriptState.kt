package ee.schimke.composeai.scripting

/**
 * Shared host ↔ script state. The host (`Main.kt`) pre-populates [results] with the rendered
 * preview set before evaluation; the script body reaches them through `previews()` / `show(id)` on
 * [ComposePreviewScript], and accumulates [failures] through `fail("…")`.
 *
 * Pure data carrier — the DSL methods live on the script base class so a script body can write
 * `fail("…")` instead of `state.failures += "…"`.
 */
class ScriptState {
  /**
   * Every preview the host rendered, keyed by [RenderedPreview.id]. Populated by the host before
   * the script is evaluated, then read-only as far as the script body is concerned.
   */
  val results: MutableMap<String, RenderedPreview> = mutableMapOf()

  /**
   * Error messages collected via `fail("…")` during script evaluation. Any non-empty list at the
   * end of the run drives a non-zero CLI exit (`2`, mirroring the canned-report commands'
   * threshold-tripped code) and a per-message stderr line.
   */
  val failures: MutableList<String> = mutableListOf()
}
