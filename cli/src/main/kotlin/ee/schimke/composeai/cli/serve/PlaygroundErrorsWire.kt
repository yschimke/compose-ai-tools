package ee.schimke.composeai.cli.serve

/**
 * Projects our internal [PlaygroundDiagnostic]s into the **stock `kotlin-compiler-server`**
 * `errors`-map wire shape ([PlaygroundStockError]) that an unmodified `kotlin-playground` frontend
 * reads. See [docs/design/PLAYGROUND.md](../../../../../../../../docs/design/PLAYGROUND.md) §4.
 *
 * Why this exists as a projection rather than a field rename: the stock frontend does **not** read
 * `errors` as a flat list — it iterates a **map keyed by file name** and reads
 * `error.interval.start.line`. So compatibility is a shape change (group by file, nest the position
 * under `interval`, uppercase the severity), not a serialised-name swap. A straight `diagnostics` →
 * `errors` rename would look compatible while still rendering nothing.
 *
 * Warnings are carried too, not just errors — upstream's `errors` map includes `WARNING`-severity
 * descriptors, and the frontend styles them differently by `className`.
 */
object PlaygroundErrorsWire {

  /** Fallback file key for a diagnostic with no file anchor (a module-level message). */
  const val DEFAULT_FILE: String = "File.kt"

  /** Group [diagnostics] by file and convert each to the stock [PlaygroundStockError] shape. */
  fun project(diagnostics: List<PlaygroundDiagnostic>): Map<String, List<PlaygroundStockError>> =
    diagnostics
      .groupBy { it.file ?: DEFAULT_FILE }
      .mapValues { (_, group) -> group.map { it.toStockError() } }

  private fun PlaygroundDiagnostic.toStockError(): PlaygroundStockError {
    // A file-level diagnostic (null position) collapses to the origin; a diagnostic with a start
    // but
    // no explicit end is a zero-width caret at the start.
    val startLine = line ?: 0
    val startCh = ch ?: 0
    val start = PlaygroundPosition(startLine, startCh)
    val end = PlaygroundPosition(endLine ?: startLine, endCh ?: startCh)
    return PlaygroundStockError(
      interval = PlaygroundInterval(start, end),
      message = message,
      severity = severity.wireName(),
      className = severity.gutterClass(),
    )
  }

  /** Upstream spells severities in uppercase (`ERROR` / `WARNING` / `INFO`). */
  private fun PlaygroundSeverity.wireName(): String =
    when (this) {
      PlaygroundSeverity.ERROR -> "ERROR"
      PlaygroundSeverity.WARNING -> "WARNING"
      PlaygroundSeverity.INFO -> "INFO"
    }

  /** The CodeMirror gutter class `kotlin-playground` applies per severity. */
  private fun PlaygroundSeverity.gutterClass(): String =
    when (this) {
      PlaygroundSeverity.ERROR -> "red_wavy_line"
      PlaygroundSeverity.WARNING -> "yellow_wavy_line"
      PlaygroundSeverity.INFO -> "info"
    }
}
