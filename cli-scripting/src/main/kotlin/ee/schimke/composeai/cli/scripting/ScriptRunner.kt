package ee.schimke.composeai.cli.scripting

import java.io.File
import kotlin.script.experimental.api.ResultWithDiagnostics
import kotlin.script.experimental.api.ScriptDiagnostic
import kotlin.script.experimental.api.ScriptEvaluationConfiguration
import kotlin.script.experimental.api.constructorArgs
import kotlin.script.experimental.host.toScriptSource
import kotlin.script.experimental.jvmhost.BasicJvmScriptingHost
import kotlin.script.experimental.jvmhost.createJvmCompilationConfigurationFromTemplate

/**
 * Compiles and evaluates a single `*.composepreview.kts` against [ComposePreviewScript], returning
 * the [ScriptState] the script body populated via the DSL (or a non-success result wrapping the
 * compiler / evaluator diagnostics).
 *
 * No caching, no classloader split — the MVP slice of issue #1084. Every invocation pays the cold
 * compile cost (~1–3 s on a warm JVM); the `~/.compose-preview/scripts-cache/<hash>.jar` story is
 * left for the follow-up that also splits the host JARs off the default CLI classpath.
 */
object ScriptRunner {

  /**
   * Result of [load]. Either [Ok] with the populated state, or [Failed] with a single composed
   * error message ready to print to stderr. We collapse to a string rather than re-surfacing the
   * full `ScriptDiagnostic` list because the only caller ([ScriptCommand]) just needs to print +
   * exit non-zero; the structured form is private to the host.
   */
  sealed interface Outcome {
    data class Ok(val state: ScriptState) : Outcome

    data class Failed(val message: String) : Outcome
  }

  fun load(scriptFile: File): Outcome {
    val state = ScriptState()
    // [ComposePreviewScriptCompilationConfig] (set on the @KotlinScript annotation) already pins
    // `dependenciesFromCurrentContext(wholeClasspath = true)`, so the script can `import` any
    // type on the host JVM's classpath — including [ComposePreviewScript], `PreviewResult`, and
    // the kotlinx-coroutines / serialization libs `:cli` pulls in transitively. No further
    // classpath wiring needed here for the MVP.
    val compilationConfig = createJvmCompilationConfigurationFromTemplate<ComposePreviewScript>()
    val evaluationConfig = ScriptEvaluationConfiguration { constructorArgs(state) }

    val result =
      BasicJvmScriptingHost().eval(scriptFile.toScriptSource(), compilationConfig, evaluationConfig)

    return when (result) {
      is ResultWithDiagnostics.Success -> Outcome.Ok(state)
      is ResultWithDiagnostics.Failure -> Outcome.Failed(formatDiagnostics(result.reports))
    }
  }

  private fun formatDiagnostics(reports: List<ScriptDiagnostic>): String {
    val errors = reports.filter { it.severity >= ScriptDiagnostic.Severity.ERROR }
    val pool = if (errors.isNotEmpty()) errors else reports
    return pool.joinToString(separator = "\n") { diag ->
      val location =
        diag.location?.let { loc -> ":${loc.start.line}:${loc.start.col}" }
          ?: diag.sourcePath?.let { "" }
          ?: ""
      val path = diag.sourcePath ?: ""
      val prefix = if (path.isNotEmpty()) "$path$location: " else ""
      "$prefix${diag.severity.name.lowercase()}: ${diag.message}"
    }
  }
}
