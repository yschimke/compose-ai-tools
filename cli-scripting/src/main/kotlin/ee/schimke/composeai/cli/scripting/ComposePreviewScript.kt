package ee.schimke.composeai.cli.scripting

import ee.schimke.composeai.cli.PreviewResult
import kotlin.script.experimental.annotations.KotlinScript
import kotlin.script.experimental.api.ScriptCompilationConfiguration
import kotlin.script.experimental.api.defaultImports
import kotlin.script.experimental.jvm.dependenciesFromCurrentContext
import kotlin.script.experimental.jvm.jvm

/**
 * Base class every `*.composepreview.kts` file inherits — the DSL surface the script body sees.
 *
 * A v1 script body looks like:
 * ```kotlin
 * extensions("a11y")
 * filter { it.module == ":app" && it.id.startsWith("Home") }
 * onResult { result ->
 *   if (result.a11yFindings?.any { it.level == "ERROR" } == true) {
 *     fail("a11y errors on ${result.id}")
 *   }
 *   println("${result.id}: ${result.captures.size} captures")
 * }
 * ```
 *
 * All four methods just push onto [state]; the host ([ScriptCommand]) drives the render pipeline,
 * AND-composes the filters, calls every `onResult` handler per surviving result, and exits non-zero
 * when [ScriptState.failures] is non-empty.
 *
 * The [state] is passed via constructor injection ([kotlin.script.experimental.api.constructorArgs]
 * on the evaluation configuration), so the host's caller keeps a live reference to the same
 * collections the script body mutates. Handlers registered via [onResult] can therefore still call
 * [fail] after the script's top-level statements have returned — the captured `this` (the script
 * instance) remains reachable as long as the host holds [state].
 */
@KotlinScript(
  displayName = "Compose Preview script",
  fileExtension = "composepreview.kts",
  compilationConfiguration = ComposePreviewScriptCompilationConfig::class,
)
abstract class ComposePreviewScript(@Suppress("unused") val state: ScriptState) {

  /**
   * Enable one or more data extensions for this run, equivalent to passing `--with-extension <id>`
   * per id on the CLI. Repeatable across multiple `extensions(...)` calls; ids dedupe at the host
   * level. Empty / blank ids are silently dropped so `extensions(*ids.toTypedArray())` against a
   * possibly-empty list is safe.
   */
  fun extensions(vararg ids: String) {
    for (id in ids) {
      val trimmed = id.trim()
      if (trimmed.isNotEmpty() && trimmed !in state.extensions) state.extensions += trimmed
    }
  }

  /**
   * Register a per-result predicate. Multiple `filter { … }` calls AND together (a result must
   * satisfy every registered predicate to survive). Predicates run after the CLI's own `--id` /
   * `--filter` matching so a script can narrow further but not widen past the user's flags.
   */
  fun filter(predicate: (PreviewResult) -> Boolean) {
    state.filters += predicate
  }

  /**
   * Register a per-result handler. Handlers run in registration order against every result that
   * passes [filter]. Each handler is free to call [fail] (accumulating a non-zero exit) and/or
   * write to stdout/stderr.
   */
  fun onResult(handler: (PreviewResult) -> Unit) {
    state.handlers += handler
  }

  /**
   * Mark the run failed with [message]. Accumulating, not fail-fast — every `fail(...)` call adds
   * one line, the CLI prints them all to stderr at the end of the run and exits with code 2 (same
   * code the canned-report commands use for a tripped threshold).
   */
  fun fail(message: String) {
    state.failures += message
  }
}

/**
 * Compilation config for [ComposePreviewScript].
 *
 * `dependenciesFromCurrentContext(wholeClasspath = true)` is the MVP shortcut: it lets the script
 * `import` anything on the host JVM's classpath (including [PreviewResult] and the `:cli` types it
 * transitively pulls in), so users don't have to learn `@file:DependsOn(...)` just to write a
 * one-line `onResult` handler. The compiler walks the entire CLI classpath, which is fine for the
 * host-side use case but is the bloat lever the lazy-fetch follow-up on issue #1084 will tighten.
 *
 * The default imports below cover the DTOs a typical `onResult` body references — adding more here
 * is preferable to making users sprinkle `import ee.schimke.composeai.cli.…` at the top of every
 * script.
 */
object ComposePreviewScriptCompilationConfig :
  ScriptCompilationConfiguration({
    defaultImports(
      "ee.schimke.composeai.cli.PreviewResult",
      "ee.schimke.composeai.cli.CaptureResult",
      "ee.schimke.composeai.cli.ScrollCapture",
      "ee.schimke.composeai.cli.AccessibilityFinding",
    )
    jvm { dependenciesFromCurrentContext(wholeClasspath = true) }
  })
