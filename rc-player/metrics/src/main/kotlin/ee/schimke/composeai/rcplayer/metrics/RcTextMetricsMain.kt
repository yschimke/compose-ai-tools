package ee.schimke.composeai.rcplayer.metrics

import ee.schimke.composeai.rcplayer.protocol.RcDocumentCodec
import java.io.File

/**
 * Write every text-metric fixture into a directory, in the shape the existing render harnesses
 * already read.
 *
 * That shape is not a new convention — it is `<id>.rc` plus a `manifest.json` of `{id, width,
 * height}`, which is exactly what `rc-compare --stage-embedded` produces and what
 * `RcEmbeddedRenderHarness`, `RcViewPlayerRenderHarness` (the Java reference lane) and
 * `RcJvmRenderHarness` all consume. Emitting it means the fixtures reach three of the five lanes
 * with no harness change at all:
 * ```
 * ./gradlew :rc-player-metrics:rcTextMetricFixtures
 * ./gradlew :third-party-rc-embedded-player:testDebugUnitTest --rerun \
 *   --tests '*RcViewPlayerRenderHarness*' --tests '*RcEmbeddedRenderHarness*' \
 *   -Prc.embedded.input=rc-player/metrics/build/fixtures \
 *   -Prc.view.output=/tmp/rc-metrics/java \
 *   -Prc.embedded.output=/tmp/rc-metrics/cmp-android
 * ```
 *
 * Both flags are load-bearing, and both fail quietly without it. `--tests` because
 * `rc.embedded.input` reaches every test in that module, so an unfiltered run also hands these
 * fixtures to `RcSemanticsExtractionTest` and `RcFigmaSvgExportTest`, which fail against them
 * *after* the PNGs are written. `--rerun` because the input arrives as a system property rather
 * than a declared task input, so a second run is `UP-TO-DATE` and silently keeps the old PNGs. The
 * same commands, with the same warning, are in `renders/rc-text-metrics/README.md`.
 *
 * `fixtures.json` alongside it carries the human half — each fixture's summary and the guide
 * vocabulary — so a reader of the output directory can tell what a rule of a given colour means
 * without reading this module.
 */
public fun main(args: Array<String>) {
  val outputDirectory = File(args.firstOrNull() ?: "build/fixtures").apply { mkdirs() }
  val fixtures = RcTextMetricDocuments.all()

  fixtures.forEach { fixture ->
    File(outputDirectory, "${fixture.id}.rc").writeBytes(RcDocumentCodec.encode(fixture.document))
  }
  File(outputDirectory, "manifest.json").writeText(manifestJson(fixtures))
  File(outputDirectory, "fixtures.json").writeText(descriptionJson(fixtures))

  println("rc-text-metrics: wrote ${fixtures.size} fixtures to $outputDirectory")
}

/** The render harnesses' manifest: id and the pixel size to play the document at. */
internal fun manifestJson(fixtures: List<RcTextMetricFixture>): String =
  fixtures.joinToString(prefix = "[\n", separator = ",\n", postfix = "\n]\n") {
    """  {"id": ${quote(it.id)}, "width": ${it.width}, "height": ${it.height}}"""
  }

/** What each fixture is for, and what each guide colour means. Written next to the documents. */
internal fun descriptionJson(fixtures: List<RcTextMetricFixture>): String {
  val fixtureRows =
    fixtures.joinToString(separator = ",\n") {
      """    {"id": ${quote(it.id)}, "summary": ${quote(it.summary)}}"""
    }
  val guideRows =
    RcTextGuide.entries.joinToString(separator = ",\n") {
      """    {"key": ${quote(it.key)}, "label": ${quote(it.label)}, """ +
        """"orientation": ${quote(it.orientation.name.lowercase())}, """ +
        """"probe": ${quote(it.probe.name.lowercase())}, """ +
        """"measureType": ${it.type}, "color": ${quote(argbHex(it.colorArgb))}, """ +
        """"description": ${quote(it.description)}}"""
    }
  return "{\n  \"fixtures\": [\n$fixtureRows\n  ],\n  \"guides\": [\n$guideRows\n  ]\n}\n"
}

private fun argbHex(argb: Int): String = "#" + (argb and 0xffffff).toString(16).padStart(6, '0')

/** Minimal JSON string escaping — these are ASCII ids and prose, never arbitrary input. */
private fun quote(value: String): String {
  val escaped = value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
  return "\"$escaped\""
}
