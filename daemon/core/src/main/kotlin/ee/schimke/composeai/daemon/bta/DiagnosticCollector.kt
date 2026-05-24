@file:OptIn(org.jetbrains.kotlin.buildtools.api.ExperimentalBuildToolsApi::class)

package ee.schimke.composeai.daemon.bta

import ee.schimke.composeai.daemon.protocol.CompileErrorDetail
import org.jetbrains.kotlin.buildtools.api.ExperimentalBuildToolsApi
import org.jetbrains.kotlin.buildtools.api.KotlinLogger

/**
 * `KotlinLogger` that collects compile-error diagnostics from BTA's `error(...)` callbacks into a
 * structured [errors] list. Pass into [BtaCompileSession.compileIncremental] so the service can map
 * a `CompilationResult.COMPILATION_ERROR` into [BtaCompileService.Outcome.CompileError] instead of
 * falling back to stage 1.
 *
 * The diagnostic format BTA emits to `error(...)` is the same `file:///path:line:col message` shape
 * kotlinc CLI prefixes with `e:` — see the spike's
 * [BTA-SPIKE.md](../../../../../../docs/daemon/BTA-SPIKE.md) sample output. The regex below
 * tolerates:
 *
 * - `file:///abs/path/Foo.kt:42:5 message`
 * - `/abs/path/Foo.kt:42:5 message`
 * - the explicit `e:` prefix (in case future BTA versions stop stripping it)
 * - an optional `error:` segment between the col/line and the message text (Kotlin 2.0+)
 *
 * Unparseable error lines are dropped silently — diagnostics with malformed file:line:col shape are
 * typically internal compiler errors and the service maps those to
 * [BtaCompileService.Outcome.Fallback] by checking `errors.isEmpty()` after the throw.
 *
 * Non-error events (info / debug / lifecycle / warn) are forwarded to [delegate] when one is
 * provided; null delegate silently drops them. Production wiring passes the daemon's stderr logger
 * as delegate so non-error compiler output still surfaces in daemon logs.
 */
class DiagnosticCollector(private val delegate: KotlinLogger? = null) : KotlinLogger {
  private val collected = mutableListOf<CompileErrorDetail>()

  /**
   * Diagnostics accumulated across this collector's lifetime, in emission order. Safe to read
   * concurrently with `error(...)` calls — implementations of `KotlinLogger.error` are invoked
   * serially from the compiler thread.
   */
  val errors: List<CompileErrorDetail>
    get() = collected.toList()

  override val isDebugEnabled: Boolean
    get() = delegate?.isDebugEnabled ?: false

  override fun error(msg: String, throwable: Throwable?) {
    parseError(msg)?.let { collected.add(it) }
    delegate?.error(msg, throwable)
  }

  override fun warn(msg: String) {
    delegate?.warn(msg)
  }

  override fun warn(msg: String, throwable: Throwable?) {
    delegate?.warn(msg, throwable)
  }

  override fun info(msg: String) {
    delegate?.info(msg)
  }

  override fun debug(msg: String) {
    delegate?.debug(msg)
  }

  override fun lifecycle(msg: String) {
    delegate?.lifecycle(msg)
  }

  internal companion object {
    /**
     * Matches BTA's diagnostic-line shape — see class kdoc.
     *
     * Groups: 1=path, 2=line, 3=column, 4=message. The leading `e:\s*` is optional; the optional
     * `error:` between the col and the message text covers Kotlin 2.0+ output.
     */
    private val ERROR_LINE_RE =
      Regex("""^(?:e:\s*)?(?:file://)?(.+?):(\d+):(\d+)(?::?\s*(?:error:\s*)?)\s*(.+)$""")

    internal fun parseError(msg: String): CompileErrorDetail? {
      val trimmed = msg.trim()
      val match = ERROR_LINE_RE.matchEntire(trimmed) ?: return null
      val line = match.groupValues[2].toIntOrNull() ?: return null
      val column = match.groupValues[3].toIntOrNull() ?: return null
      val message = match.groupValues[4].trim()
      if (message.isEmpty()) return null
      return CompileErrorDetail(
        file = match.groupValues[1],
        line = line,
        column = column,
        message = message,
      )
    }
  }
}

/**
 * Thrown by the production `CompileBackend` lambda when a compile failed AND BTA emitted
 * diagnostics through the configured [DiagnosticCollector]. [DefaultBtaCompileService.compile]
 * catches this specifically and maps it to [BtaCompileService.Outcome.CompileError]; any other
 * throwable still maps to [BtaCompileService.Outcome.Fallback] (unrecoverable internal error — BTA
 * bootstrap fault, missing JAR, etc.).
 *
 * Internal to the BTA wiring; not part of the JSON-RPC surface or the spike module.
 */
class BtaCompileDiagnosticException(val errors: List<CompileErrorDetail>) :
  RuntimeException("BTA compile failed with ${errors.size} diagnostic(s)")
