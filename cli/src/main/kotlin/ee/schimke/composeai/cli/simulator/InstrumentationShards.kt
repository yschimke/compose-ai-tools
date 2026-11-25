package ee.schimke.composeai.cli.simulator

import ee.schimke.composeai.cli.destroyTree
import java.io.File
import java.util.Collections
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * `--instrument --shards N`: one test APK's tests split across N simulator JVMs run side by side,
 * then their results merged into the one report `--test-json` and `--test-report` name.
 *
 * Each simulator is told its shard (`composeai.simulator.instrument.shard=i/N`) and keeps only the
 * tests whose display name hashes to it, as AndroidJUnitRunner's `shardIndex`/`numShards` do, so
 * the shards together run every selected test once. Every simulator boots the app on its own; a
 * suite pays that once per shard in CPU, but in parallel, so wall time falls towards the boot plus
 * the slowest shard's tests.
 */
internal object InstrumentationShards {

  const val SHARD_PROPERTY = "composeai.simulator.instrument.shard"
  const val JSON_PROPERTY = "composeai.simulator.instrument.json"
  const val REPORT_PROPERTY = "composeai.simulator.instrument.report"

  /** Shard [index] of [count]'s properties: its shard, and a JSON report of its own in [dir]. */
  fun properties(
    base: Map<String, String>,
    index: Int,
    count: Int,
    dir: File,
  ): Map<String, String> =
    base - REPORT_PROPERTY +
      mapOf(SHARD_PROPERTY to "$index/$count", JSON_PROPERTY to shardJson(dir, index).absolutePath)

  fun shardJson(dir: File, index: Int): File = File(dir, "shard-$index.json")

  /**
   * Runs [commands] (one per shard) at once, each line of their output prefixed with its shard, and
   * returns their exit codes in order. Every simulator is taken down if this JVM is.
   */
  fun run(
    commands: List<List<String>>,
    out: (String) -> Unit = ::println,
    pool: Boolean = false,
  ): List<Int> {
    val prefixed = commands.size > 1
    fun sink(index: Int): (String) -> Unit {
      val prefix = if (prefixed) "[${index + 1}/${commands.size}] " else ""
      return { line -> synchronized(out) { out(prefix + line) } }
    }
    if (pool) {
      val exits = IntArray(commands.size)
      commands
        .mapIndexed { index, command ->
          thread(name = "shard-$index") {
            exits[index] =
              runCatching { SimulatorPool.run(command, keep = commands.size, out = sink(index)) }
                .getOrElse {
                  sink(index)("compose-preview: $it")
                  1
                }
          }
        }
        .forEach { it.join() }
      return exits.toList()
    }
    val processes = Collections.synchronizedList(mutableListOf<Process>())
    val hook = Thread { processes.forEach { destroyTree(it) } }
    Runtime.getRuntime().addShutdownHook(hook)
    try {
      val pumps = commands.mapIndexed { index, command ->
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        processes += process
        process.outputStream.close()
        val sink = sink(index)
        thread(name = "shard-$index", isDaemon = true) {
          process.inputStream.bufferedReader().useLines { lines -> lines.forEach(sink) }
        }
      }
      val exits = processes.toList().map { it.waitFor() }
      pumps.forEach { it.join(TimeUnit.SECONDS.toMillis(5)) }
      return exits
    } finally {
      runCatching { Runtime.getRuntime().removeShutdownHook(hook) }
    }
  }

  /**
   * The shards' JSON reports as one: totals summed, cases in shard order, and an `error` naming
   * each shard that failed to report or to start its tests. [exits] are the shards' exit codes.
   */
  fun merge(reports: List<File>, exits: List<Int>, wallMillis: Long): JsonObject {
    var tests = 0
    var passed = 0
    var failed = 0
    var skipped = 0
    val cases = mutableListOf<JsonElement>()
    val errors = mutableListOf<String>()
    reports.forEachIndexed { index, file ->
      val label = "shard ${index + 1}/${reports.size}"
      val report =
        runCatching { Json.parseToJsonElement(file.readText()).jsonObject }.getOrNull()
          ?: run {
            errors += "$label exited with ${exits.getOrNull(index)} and wrote no results"
            return@forEachIndexed
          }
      fun count(name: String) = report[name]?.jsonPrimitive?.intOrNull ?: 0
      tests += count("tests")
      passed += count("passed")
      failed += count("failed")
      skipped += count("skipped")
      report["error"]?.jsonPrimitive?.contentOrNull?.let { errors += "$label: $it" }
      report["cases"]?.jsonArray?.let(cases::addAll)
    }
    return buildJsonObject {
      put("tests", tests)
      put("passed", passed)
      put("failed", failed)
      put("skipped", skipped)
      put("durationMs", wallMillis)
      put("shards", reports.size)
      if (errors.isNotEmpty()) put("error", errors.joinToString("; "))
      put("cases", JsonArray(cases))
    }
  }

  /** Whether the merged run passed: every shard reported, none errored and no test failed. */
  fun passed(merged: JsonObject, exits: List<Int>): Boolean =
    merged["error"] == null &&
      (merged["failed"]?.jsonPrimitive?.intOrNull ?: 1) == 0 &&
      exits.all { it == 0 }

  /** [merged] as the JUnit XML report a single simulator writes. */
  fun xml(merged: JsonObject): String = buildString {
    val cases = merged["cases"]?.jsonArray.orEmpty().map { it.jsonObject }
    fun int(name: String) = merged[name]?.jsonPrimitive?.intOrNull ?: 0
    append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
    append("<testsuite name=\"simulator-instrumentation\" tests=\"${int("tests")}\"")
    append(" failures=\"${int("failed")}\" skipped=\"${int("skipped")}\"")
    append(" time=\"${(merged["durationMs"]?.jsonPrimitive?.longOrNull ?: 0) / 1000.0}\">\n")
    for (case in cases) {
      fun text(name: String) = case[name]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content
      val seconds = (case["durationMs"]?.jsonPrimitive?.longOrNull ?: 0) / 1000.0
      append("  <testcase classname=\"${escape(text("class") ?: "")}\"")
      append(" name=\"${escape(text("method") ?: "null")}\" time=\"$seconds\"")
      when (text("status")) {
        "failed" -> {
          append(">\n    <failure message=\"${escape(text("message") ?: "null")}\">")
          append(escape(text("trace") ?: ""))
          append("</failure>\n  </testcase>\n")
        }
        "skipped" -> append(">\n    <skipped/>\n  </testcase>\n")
        else -> append("/>\n")
      }
    }
    append("</testsuite>\n")
  }

  /** The `am instrument`-shaped summary line a single simulator prints. */
  fun summary(merged: JsonObject, exits: List<Int>): String {
    fun int(name: String) = merged[name]?.jsonPrimitive?.intOrNull ?: 0
    val skipped = int("skipped").takeIf { it > 0 }?.let { ", $it skipped" } ?: ""
    val shards = "${exits.size} shards"
    merged["error"]?.jsonPrimitive?.contentOrNull?.let {
      return "INSTRUMENTATION_FAILED: $it\nTests run: ${int("tests")},  Failures: ${int("failed")} ($shards)"
    }
    return if (passed(merged, exits)) "OK (${int("tests")} tests$skipped, $shards)"
    else "FAILURES!!!\nTests run: ${int("tests")},  Failures: ${int("failed")} ($shards)"
  }

  private fun escape(text: String): String = buildString {
    for (ch in text) {
      when (ch) {
        '&' -> append("&amp;")
        '<' -> append("&lt;")
        '>' -> append("&gt;")
        '"' -> append("&quot;")
        else -> append(if (ch < ' ' && ch != '\n' && ch != '\t' && ch != '\r') ' ' else ch)
      }
    }
  }
}
