package ee.schimke.composeai.scripting

import kotlin.script.experimental.annotations.KotlinScript
import kotlin.script.experimental.api.ScriptCompilationConfiguration
import kotlin.script.experimental.api.defaultImports
import kotlin.script.experimental.jvm.dependenciesFromCurrentContext
import kotlin.script.experimental.jvm.jvm

/**
 * Base class every `*.composepreview.kts` file inherits — the DSL surface the script body sees.
 *
 * Shape-C semantics: the host renders the preview set up-front (using `:gradle-preview-driver`'s
 * `GradlePreviewDriver.render(...)`) and then evaluates the script body against the populated
 * [state]. The script reaches every preview via [previews] (full list) or [show] (lookup by id),
 * reads typed extension data off the handle ([RenderedPreview.a11y] etc.), and accumulates failures
 * via [fail].
 *
 * Example — the a11y-audit shape from issue #1084:
 * ```kotlin
 * for (ui in previews().filter { it.module == ":app" && it.id.startsWith("Home") }) {
 *   if (ui.a11y.hasErrors) fail("a11y errors on ${ui.id}: ${ui.a11y.errors.size}")
 *   println("${ui.id}: ${ui.captures.size} captures")
 * }
 * ```
 *
 * This expresses the same intent as `compose-preview a11y --filter Home --module :app --fail-on
 * errors` from the CLI, proving the scripting surface is expressive enough to displace per-command
 * code — the "Built-in scripts" note in `docs/AGENTS.md`.
 *
 * State is passed via constructor injection ([kotlin.script.experimental.api.constructorArgs]), so
 * the host's caller keeps a live reference to the same [ScriptState] the script body mutates
 * through [fail].
 */
@KotlinScript(
  displayName = "Compose Preview script",
  fileExtension = "composepreview.kts",
  compilationConfiguration = ComposePreviewScriptCompilationConfig::class,
)
abstract class ComposePreviewScript(@Suppress("unused") val state: ScriptState) {

  /**
   * Every rendered preview, in stable id-sorted order. Returns the snapshot the host pre-populated
   * — calling it twice yields the same list. Filter with stock Kotlin: `previews().filter { … }`.
   */
  fun previews(): List<RenderedPreview> = state.results.values.sortedBy { it.id }

  /**
   * Look up one rendered preview by exact id. Throws [IllegalArgumentException] when the id isn't
   * in the rendered set — that's almost always a typo in the script and the script author wants a
   * loud failure, not a silent null.
   */
  fun show(id: String): RenderedPreview =
    state.results[id]
      ?: throw IllegalArgumentException(
        "preview not found: '$id'. " +
          "Available (${state.results.size}): ${state.results.keys.sorted().joinToString(", ")}"
      )

  /**
   * Mark the run failed with [message]. Accumulating — multiple `fail(…)` calls all surface as
   * separate stderr lines at the end of the run, and the process exits with code 2. The script body
   * keeps executing after a `fail()`, so an a11y-style audit can report every failing preview in
   * one go rather than aborting on the first.
   */
  fun fail(message: String) {
    state.failures += message
  }
}

/**
 * Compilation config for [ComposePreviewScript].
 *
 * `dependenciesFromCurrentContext(wholeClasspath = true)` lets the script `import` anything on the
 * host JVM's classpath, so users don't have to learn `@file:DependsOn(...)` just to reference the
 * `AccessibilityFinding` DTO.
 */
object ComposePreviewScriptCompilationConfig :
  ScriptCompilationConfiguration({
    defaultImports(
      "ee.schimke.composeai.scripting.RenderedPreview",
      "ee.schimke.composeai.scripting.A11yHandle",
      "ee.schimke.composeai.cli.AccessibilityFinding",
      "ee.schimke.composeai.cli.CaptureResult",
      "ee.schimke.composeai.cli.ScrollCapture",
    )
    jvm { dependenciesFromCurrentContext(wholeClasspath = true) }
  })
