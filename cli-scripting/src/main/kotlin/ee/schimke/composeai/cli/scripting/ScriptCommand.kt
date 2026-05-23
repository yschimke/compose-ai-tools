package ee.schimke.composeai.cli.scripting

import ee.schimke.composeai.cli.Command
import java.io.File
import kotlin.system.exitProcess

/**
 * `compose-preview script <path.composepreview.kts>` — Path A of issue #1084.
 *
 * Flow:
 * 1. Run `:composePreviewRenderAll` against every preview module (honouring CLI `--module` /
 *    `--filter` / `--id` / `--with-extension` / `--changed-only` narrowing).
 * 2. Wrap each surviving [ee.schimke.composeai.cli.PreviewResult] into a [RenderedPreview], keyed
 *    by id in [ScriptState.results].
 * 3. Compile + evaluate the script. The script body reads results via `previews()` / `show(id)` and
 *    accumulates failures via `fail(...)`.
 * 4. Exit 2 if `fail(...)` was called at least once (matching the canned-report commands'
 *    threshold-tripped code) — otherwise mirror the underlying render exit code.
 *
 * The render-then-evaluate order is the shape-C contract: the script sees a populated preview set
 * up-front rather than registering callbacks the host drives. This is the right shape for "give me
 * the rendered result and let me write arbitrary Kotlin against it" (matches the way agents and
 * teams want to interact with the data) and the wrong shape for "open a live session and drive
 * keyboard / UI events" (which needs daemon JSON-RPC plumbing — tracked on the same issue).
 *
 * **MVP slice.** No jar cache (every run pays the ~1–3 s compile cost), no classloader split (the
 * `kotlin-scripting-jvm-host` + `kotlin-compiler-embeddable` closure rides on the default CLI
 * runtime classpath, adding ~50 MB to the tarball), no interactive sub-handles on
 * [RenderedPreview]. Those are tracked on issue #1084.
 */
class ScriptCommand(args: List<String>) : Command(args) {

  override fun run() {
    val scriptPath = pickScriptPath(args)
    if (scriptPath == null) {
      System.err.println(
        "Usage: compose-preview script <path.composepreview.kts> [flags…]\n" +
          "  Script DSL: previews(), show(id), fail(msg). See issue #1084."
      )
      exitProcess(1)
    }

    val scriptFile = File(scriptPath)
    if (!scriptFile.exists()) {
      System.err.println("compose-preview script: not found: $scriptPath")
      exitProcess(1)
    }

    // Render first — the script sees a populated preview set when it evaluates. Honours the
    // user's `--with-extension`, `--module`, `--filter`, `--id`, `--changed-only` flags via the
    // base `Command` plumbing.
    val raw = renderModules(silenceStdout = false, gradleArguments = gradleArgsWithForce())
    if (!raw.buildOk) {
      System.err.println("compose-preview script: render failed")
      exitProcess(2)
    }

    val manifests = readAllManifests(raw.modules)
    val results = if (manifests.isEmpty()) emptyList() else buildResults(manifests)
    val filtered = applyFilters(results)

    val state = ScriptState()
    for (result in filtered) {
      state.results[result.id] = RenderedPreview(result)
    }

    when (val outcome = ScriptRunner.evaluate(scriptFile, state)) {
      is ScriptRunner.Outcome.Failed -> {
        System.err.println("compose-preview script: failed to evaluate $scriptPath")
        System.err.println(outcome.message)
        exitProcess(1)
      }
      is ScriptRunner.Outcome.Ok -> {} // fall through to the fail-accumulation check
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
     * so an interleaved `--module :app` doesn't pollute the picker. Falls back to "first non-flag
     * arg" so a script under an unusual name still works when no other flags compete.
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
