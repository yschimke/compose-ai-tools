package ee.schimke.composeai.cli.scripting

import java.io.File
import kotlin.script.experimental.api.ResultValue
import kotlin.script.experimental.api.ResultWithDiagnostics
import kotlin.script.experimental.api.ScriptDiagnostic
import kotlin.script.experimental.api.ScriptEvaluationConfiguration
import kotlin.script.experimental.api.constructorArgs
import kotlin.script.experimental.host.toScriptSource
import kotlin.script.experimental.jvmhost.BasicJvmScriptingHost
import kotlin.script.experimental.jvmhost.createJvmCompilationConfigurationFromTemplate

/**
 * Compiles and evaluates a single `*.composepreview.kts` against [ComposePreviewScript].
 *
 * The host populates the [ScriptState] (rendered previews keyed by id) before calling [evaluate];
 * the script body then reads them via `previews()` / `show(id)` and writes failures back through
 * `fail(...)`. Returning [Outcome] rather than throwing so the caller can print the structured
 * error and pick its own exit code.
 *
 * No caching, no classloader split — the MVP slice of issue #1084. Every invocation pays the
 * cold-compile cost (~1–3 s); the `~/.compose-preview/scripts-cache/<hash>.jar` story is left for
 * the follow-up that also splits the host JARs off the default CLI classpath.
 */
object ScriptRunner {

  sealed interface Outcome {
    /** Script body ran to completion. Any `fail(...)` calls landed in `state.failures`. */
    object Ok : Outcome

    /** Compile error or evaluation-time throwable. Caller prints [message] and exits non-zero. */
    data class Failed(val message: String) : Outcome
  }

  fun evaluate(scriptFile: File, state: ScriptState): Outcome {
    val compilationConfig = createJvmCompilationConfigurationFromTemplate<ComposePreviewScript>()
    val evaluationConfig = ScriptEvaluationConfiguration { constructorArgs(state) }

    val result =
      BasicJvmScriptingHost().eval(scriptFile.toScriptSource(), compilationConfig, evaluationConfig)

    return when (result) {
      is ResultWithDiagnostics.Failure -> Outcome.Failed(formatDiagnostics(result.reports))
      is ResultWithDiagnostics.Success -> {
        // A script that throws at *runtime* still completes compilation, so the host wraps it in
        // `Success` with `returnValue = ResultValue.Error`. Surface those as `Failed` so
        // `show("X")`
        // missing the id, a stock `require(…) { "…" }` tripping, or any other in-script throwable
        // turns into a clean error message + non-zero exit instead of looking like a clean run.
        val returnValue = result.value.returnValue
        if (returnValue is ResultValue.Error) {
          Outcome.Failed(formatRuntimeError(scriptFile, returnValue))
        } else {
          Outcome.Ok
        }
      }
    }
  }

  private fun formatRuntimeError(scriptFile: File, error: ResultValue.Error): String {
    val thrown = error.error
    val cause = thrown.cause
    // The host wraps the actual user-thrown exception in an `InvocationTargetException` from the
    // reflective constructor / `<clinit>` call — unwrap one level when present so error messages
    // read like "preview not found: 'Nope'" rather than mentioning reflection internals.
    val effective = cause ?: thrown
    val message = effective.message ?: effective::class.simpleName ?: "script threw"
    return "${scriptFile.name}: ${effective::class.simpleName}: $message"
  }

  private fun formatDiagnostics(reports: List<ScriptDiagnostic>): String {
    val errors = reports.filter { it.severity >= ScriptDiagnostic.Severity.ERROR }
    val pool = if (errors.isNotEmpty()) errors else reports
    return pool.joinToString(separator = "\n") { diag ->
      val location = diag.location?.let { loc -> ":${loc.start.line}:${loc.start.col}" } ?: ""
      val path = diag.sourcePath ?: ""
      val prefix = if (path.isNotEmpty()) "$path$location: " else ""
      "$prefix${diag.severity.name.lowercase()}: ${diag.message}"
    }
  }
}
