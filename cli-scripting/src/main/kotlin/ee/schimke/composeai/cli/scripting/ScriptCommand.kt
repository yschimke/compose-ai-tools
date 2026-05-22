package ee.schimke.composeai.cli.scripting

import ee.schimke.composeai.cli.Command
import java.io.File
import kotlin.system.exitProcess

/**
 * `compose-preview script <path.composepreview.kts>` — Path A of issue #1084.
 *
 * Loads the script via [ScriptRunner], runs the same `:composePreviewRenderAll` drive as
 * `compose-preview a11y` / `profile`, then walks every result through the script-declared filter
 * predicates and `onResult { … }` handlers. Any `fail("…")` call accumulated during the run drives
 * the process to exit code 2 with the messages on stderr — matching the canned-report commands'
 * threshold-tripped semantics.
 *
 * Extension wiring: the script's `extensions("a11y", "theme")` declarations are folded into
 * [implicitExtensions], so they merge with the user's `--with-extension` flags via the same
 * `composePreview.activeExtensions=<comma-list>` Gradle property the rest of the CLI emits. A
 * script that omits `extensions(...)` runs against the default extension set, same as a bare
 * `compose-preview render`.
 *
 * Filter ordering: the CLI's own `--id` / `--filter` / `--changed-only` flags narrow the result set
 * first; the script's `filter { … }` predicates AND-compose on top of that. A script can't widen
 * past the user's flags — by design, so wrapper scripts in CI pipelines can pass `--changed-only`
 * to short-circuit.
 *
 * **MVP slice.** No jar cache (every run pays the ~1–3 s compile cost), no classloader split (the
 * `kotlin-scripting-jvm-host` + `kotlin-compiler-embeddable` closure rides on the default CLI
 * runtime classpath, adding ~50 MB to the tarball). Those are tracked on issue #1084 alongside
 * `scripts/install.sh --with-scripting`.
 */
class ScriptCommand(args: List<String>) : Command(args) {

  // Populated by `run()` before any [Command] method that reads it; the `::state.isInitialized`
  // guard on [implicitExtensions] only matters if a subclass or test calls it pre-run.
  private lateinit var state: ScriptState

  override fun implicitExtensions(): List<String> =
    if (::state.isInitialized) state.extensions.toList() else emptyList()

  override fun run() {
    val scriptPath = pickScriptPath(args)
    if (scriptPath == null) {
      System.err.println(
        "Usage: compose-preview script <path.composepreview.kts> [flags…]\n" +
          "  See issue #1084 for the DSL surface (extensions/filter/onResult/fail)."
      )
      exitProcess(1)
    }

    val scriptFile = File(scriptPath)
    if (!scriptFile.exists()) {
      System.err.println("compose-preview script: not found: $scriptPath")
      exitProcess(1)
    }

    when (val outcome = ScriptRunner.load(scriptFile)) {
      is ScriptRunner.Outcome.Failed -> {
        System.err.println("compose-preview script: failed to evaluate $scriptPath")
        System.err.println(outcome.message)
        exitProcess(1)
      }
      is ScriptRunner.Outcome.Ok -> state = outcome.state
    }

    val raw = renderModules(silenceStdout = false, gradleArguments = gradleArgsWithForce())
    if (!raw.buildOk) {
      System.err.println("compose-preview script: render failed")
      exitProcess(2)
    }

    val manifests = readAllManifests(raw.modules)
    val results = if (manifests.isEmpty()) emptyList() else buildResults(manifests)

    val cliFiltered = applyFilters(results)
    val scriptFiltered =
      if (state.filters.isEmpty()) {
        cliFiltered
      } else {
        cliFiltered.filter { result -> state.filters.all { predicate -> predicate(result) } }
      }

    if (state.handlers.isEmpty() && scriptFiltered.isNotEmpty()) {
      System.err.println(
        "compose-preview script: script declared no `onResult { … }` handlers; " +
          "${scriptFiltered.size} preview result(s) had nowhere to go."
      )
    }

    for (result in scriptFiltered) {
      for (handler in state.handlers) handler(result)
    }

    if (state.failures.isNotEmpty()) {
      for (message in state.failures) {
        System.err.println("compose-preview script: $message")
      }
      exitProcess(2)
    }
  }

  internal companion object {
    /**
     * Pick the script path from [args]. Prefers an explicit `*.composepreview.kts` / `*.kts` ending
     * so an interleaved `--module :app` doesn't pollute the picker — mirrors what `ProfileCommand`
     * does, but with the suffix hint so order-of-flags-vs-path is robust. Falls back to "first
     * non-flag arg" when the user passed a script under an unusual name.
     */
    internal fun pickScriptPath(args: List<String>): String? {
      val byExtension = args.firstOrNull {
        !it.startsWith("-") && (it.endsWith(".kts") || it.endsWith(".kt"))
      }
      if (byExtension != null) return byExtension
      // Fall back to first non-flag — same as `ProfileCommand`'s picker so users get consistent
      // behaviour. Note this can pick up a previous flag's value when the user writes
      // `--module :app foo.kts`; encourage `compose-preview script foo.kts --module :app` order
      // in docs and don't try to be clever here.
      return args.firstOrNull { !it.startsWith("-") }
    }
  }
}
