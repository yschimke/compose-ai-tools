@file:OptIn(androidx.tracing.DelicateTracingApi::class)

package ee.schimke.composeai.rcplayer.profile

import androidx.tracing.Tracer
import androidx.tracing.wire.TraceDriver
import androidx.tracing.wire.TraceSink
import ee.schimke.composeai.io.SystemFileSystem
import java.io.File
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath

/**
 * Profile the CMP Remote Compose player over the four reference documents.
 *
 * Two things come out of a run, into the directory named by the single argument:
 * - `profile.md` and one `<scenario>.json` per scenario — the summary tables and the Chrome Trace
 *   Event timelines, both from `RcTrace.recorder`, which works identically on every target the
 *   player builds for.
 * - a `perfetto/` directory of `.perfetto-trace` files — the same spans as Perfetto `TracePacket`s,
 *   written by `androidx.tracing:tracing-wire`'s `TraceDriver`. Open at
 *   [ui.perfetto.dev](https://ui.perfetto.dev/).
 *
 * This process is the one place in the repository that calls `Tracer.setGlobalTracer`. The player
 * modules only ever read `Tracer.global`, which androidx documents as the rule for libraries; a
 * library that installed a tracer would be overriding a decision that belongs to the application.
 */
public fun main(args: Array<String>) {
  val outputDirectory = (args.firstOrNull() ?: "build/profile").toPath()
  val fileSystem: FileSystem = SystemFileSystem
  fileSystem.createDirectories(outputDirectory)
  val perfettoDirectory = outputDirectory / "perfetto"
  fileSystem.createDirectories(perfettoDirectory)

  // The capture phase renders every document a second time, to PNG. Those renders must not land in
  // the trace — they would double every span count — but they must also not run against a *closed*
  // driver, whose sink is finalized. `isCategoryEnabled` is the driver's own switch for exactly
  // this: the flag turns spans off for the capture phase while the driver stays open, and the
  // driver is closed only once every render is finished.
  //
  // `Tracer.setGlobalTracer` throws if called twice, and its counterpart `resetGlobalTracer` is
  // `@VisibleForTesting`, so there is no supported way to swap the tracer back mid-process — one
  // more reason the switch lives in the predicate rather than in the global.
  var measuring = true
  // `TraceSink(directory)` mints a `.perfetto-trace` file inside the directory it is handed.
  val driver =
    TraceDriver(
      sink = TraceSink(directory = File(perfettoDirectory.toString())),
      isCategoryEnabled = { measuring },
    )
  Tracer.setGlobalTracer(driver.tracer)

  val runner = RcProfileRunner()
  val scenarios = rcProfileScenarios()
  val results: List<RcProfileResult>
  val captures: Map<String, ByteArray>
  try {
    results = runner.run(scenarios)
    measuring = false
    captures = results.associate { it.scenario.id to runner.capture(it.scenario) }
  } finally {
    driver.close()
  }

  results.forEach { result ->
    fileSystem.write(outputDirectory / "${result.scenario.id}.json") {
      writeUtf8(result.chromeTraceJson)
    }
    fileSystem.write(outputDirectory / "${result.scenario.id}.png") {
      write(captures.getValue(result.scenario.id))
    }
  }
  val report = RcProfileReport.render(results, environment())
  fileSystem.write(outputDirectory / "profile.md") { writeUtf8(report) }

  println(report)
  println("Wrote ${outputDirectory / "profile.md"}")
  println("Perfetto traces in $perfettoDirectory")
}

/**
 * The run's context. A timing table without it is unfalsifiable — the same profile on a different
 * JVM or a busier machine is a different profile, and the reader has to be able to tell.
 */
private fun environment(): List<Pair<String, String>> =
  listOf(
    "JVM" to "${System.getProperty("java.vm.name")} ${System.getProperty("java.version")}",
    "OS" to "${System.getProperty("os.name")} ${System.getProperty("os.arch")}",
    "CPUs" to Runtime.getRuntime().availableProcessors().toString(),
    "Renderer" to "Compose Desktop ImageComposeScene (skiko software raster), density 1",
  )

private operator fun Path.div(segment: String): Path = resolve(segment)
