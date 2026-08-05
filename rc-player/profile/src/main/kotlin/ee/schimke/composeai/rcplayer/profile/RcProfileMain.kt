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

  // `TraceSink(directory)` mints a `.perfetto-trace` file inside the directory it is handed. Every
  // category is enabled: the point of this process is to capture all of them at once.
  val driver = TraceDriver(sink = TraceSink(directory = File(perfettoDirectory.toString())))
  Tracer.setGlobalTracer(driver.tracer)

  val runner = RcProfileRunner()
  val scenarios = rcProfileScenarios()
  val results =
    try {
      runner.run(scenarios)
    } finally {
      driver.close()
    }

  results.forEach { result ->
    fileSystem.write(outputDirectory / "${result.scenario.id}.json") {
      writeUtf8(result.chromeTraceJson)
    }
    fileSystem.write(outputDirectory / "${result.scenario.id}.png") {
      write(runner.capture(result.scenario))
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
