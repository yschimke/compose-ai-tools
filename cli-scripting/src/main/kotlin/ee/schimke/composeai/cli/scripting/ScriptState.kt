package ee.schimke.composeai.cli.scripting

import ee.schimke.composeai.cli.PreviewResult

/**
 * Mutable state a single `*.composepreview.kts` evaluation builds up via the DSL on
 * [ComposePreviewScript]. Owned by the host ([ScriptRunner]) and passed into the script as a
 * constructor arg, so the script's top-level statements (`extensions(...)`, `filter { … }`,
 * `onResult { … }`) and any lambda body invoked later by [ScriptCommand] mutate the same
 * collections.
 *
 * Pure data carrier — no behaviour beyond holding lists. The DSL methods (and `fail()`) live on
 * [ComposePreviewScript] so a script body can write `fail("…")` instead of `state.failures += "…"`.
 */
class ScriptState {
  /**
   * Extension ids the script asked to be enabled (`extensions("a11y", "theme")`). Merged with the
   * caller's `--with-extension` flags by [ScriptCommand.implicitExtensions] so a one-line script
   * gets the same `composePreview.activeExtensions` wiring as `compose-preview a11y`.
   */
  val extensions: MutableList<String> = mutableListOf()

  /**
   * Per-result predicates the script registered via `filter { … }`. AND-composed in
   * [ScriptCommand], i.e. a result must satisfy every registered predicate (and the CLI's own
   * `--id` / `--filter` flags) to reach the [handlers] step.
   */
  val filters: MutableList<(PreviewResult) -> Boolean> = mutableListOf()

  /**
   * Handlers the script registered via `onResult { … }`. [ScriptCommand] invokes each one, in
   * registration order, against every result that survives [filters]. Handlers can call `fail(…)`
   * to mark the whole run failed and `println(…)` for human output.
   */
  val handlers: MutableList<(PreviewResult) -> Unit> = mutableListOf()

  /**
   * Error messages collected via `fail("…")`. Any non-empty list at the end of the run drives a
   * non-zero CLI exit code (`2`, matching the canned-report commands' "threshold tripped" code) and
   * a per-message stderr line. The script can call `fail()` zero, one, or many times — accumulated
   * rather than fail-fast so an `onResult` loop reports every failing preview in a single run.
   */
  val failures: MutableList<String> = mutableListOf()
}
