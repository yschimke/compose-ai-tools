package ee.schimke.composeai.rcplayer.profile

import ee.schimke.composeai.rcplayer.trace.RcTraceCategory
import ee.schimke.composeai.rcplayer.trace.RcTraceSectionStats

/**
 * Renders profile results as Markdown.
 *
 * Per scenario there are two tables, not one, because the two groups of span are not comparable:
 * document-lifecycle spans (`rc-player.document`) fire once per load, and frame and input spans
 * fire per frame or per gesture. Putting them in one table sorted by total time would rank a phase
 * that ran twelve times above one that ran hundreds, which says nothing about either.
 */
public object RcProfileReport {
  public fun render(
    results: List<RcProfileResult>,
    environment: List<Pair<String, String>>,
  ): String = buildString {
    appendLine("# CMP Remote Compose player profile")
    appendLine()
    appendLine(
      "Produced by `./gradlew :rc-player-profile:rcPlayerProfile`. Every row comes from an " +
        "`androidx.tracing` span opened by the player itself — the same spans a Perfetto capture " +
        "shows — collected through `RcTrace.recorder`."
    )
    appendLine()
    environment.forEach { (key, value) -> appendLine("- **$key** — $value") }
    appendLine()

    results.forEach { result ->
      appendLine("## `${result.scenario.id}`")
      appendLine()
      appendLine(result.scenario.description)
      appendLine()
      appendLine(
        "${result.operations} operations, ${result.documentBytes} B on the wire, " +
          "${result.scenario.loads} loads × ${result.scenario.framesPerLoad} frames."
      )
      appendLine()

      val perLoad = result.sections.filter { it.category == RcTraceCategory.DOCUMENT }
      val perFrame = result.sections.filter { it.category != RcTraceCategory.DOCUMENT }

      appendLine("### Load (once per document)")
      appendLine()
      appendTable(perLoad)
      appendLine()
      appendLine("### Frame and input")
      appendLine()
      appendTable(perFrame)
      appendLine()
    }
  }

  private fun StringBuilder.appendTable(sections: List<RcTraceSectionStats>) {
    if (sections.isEmpty()) {
      appendLine("_No spans in this group._")
      return
    }
    appendLine("| Section | Count | Total | Mean | p50 | p95 | Max |")
    appendLine("| --- | ---: | ---: | ---: | ---: | ---: | ---: |")
    sections.forEach { section ->
      appendLine(
        "| `${section.name}` | ${section.count} | ${micros(section.totalNanos)} | " +
          "${micros(section.meanNanos)} | ${micros(section.medianNanos)} | " +
          "${micros(section.p95Nanos)} | ${micros(section.maxNanos)} |"
      )
    }
  }

  /**
   * Microseconds with three significant-ish digits. Nanoseconds would put noise in the last two
   * columns and milliseconds would round most of this table to zero.
   */
  private fun micros(nanos: Long): String {
    val value = nanos / 1000.0
    return when {
      value >= 1000.0 -> "${(value / 1000.0).roundTo(2)} ms"
      value >= 10.0 -> "${value.roundTo(1)} µs"
      else -> "${value.roundTo(2)} µs"
    }
  }

  private fun Double.roundTo(decimals: Int): String {
    var factor = 1L
    repeat(decimals) { factor *= 10 }
    val scaled = kotlin.math.round(this * factor).toLong()
    val whole = scaled / factor
    val fraction = (scaled % factor).let { if (it < 0) -it else it }
    return if (decimals == 0) "$whole" else "$whole.${fraction.toString().padStart(decimals, '0')}"
  }
}
