package ee.schimke.composeai.editloop

import ee.schimke.composeai.daemon.client.SubprocessDaemonClientFactory
import ee.schimke.composeai.daemon.client.WorkspaceId
import ee.schimke.composeai.daemon.protocol.CompileResultKind
import ee.schimke.composeai.daemon.protocol.CompileSourcesParams
import ee.schimke.composeai.daemon.protocol.DaemonLaunchDescriptor
import ee.schimke.composeai.daemon.protocol.SourceChangeSet
import java.io.File
import kotlin.system.exitProcess
import kotlin.time.Duration.Companion.minutes

/**
 * The warm edit loop, measured against the daemon this repository pins: launch the module's daemon
 * from its `daemon-launch.json`, warm its in-process compiler, then edit one file and call
 * `compileSources` [cycles] times, the way compose-preview-mcp does on every save.
 *
 * It fails (exit 1) when any warm cycle is not `ok`, when the warm-up reports nothing ready, or
 * when the median warm compile exceeds [budgetMs]. Two regressions it exists for, both shipped in
 * compose-preview-daemon 3.27.5 and invisible to every other check:
 * - the warm compiler could not see `internal` declarations of the module's other files, so every
 *   edit answered `compileError` and fell back to Gradle (compose-preview-daemon#465); and
 * - the release that fixed it published no `daemon-core`, so the fix reached no consumer
 *   (compose-preview-daemon#469). Running against the published pin, not a source build, is what
 *   catches the second.
 *
 * Arguments: `--descriptor <daemon-launch.json> --workspace <dir> --edit <file> --marker <text>
 * --cycles <n> --budget-ms <n> --report <file>`.
 */
fun main(args: Array<String>) {
  val opts = args.toList().chunked(2).associate { (k, v) -> k.removePrefix("--") to v }
  val descriptorFile = File(opts.getValue("descriptor"))
  val workspace = File(opts.getValue("workspace")).canonicalFile
  val edit = File(opts.getValue("edit")).canonicalFile
  val marker = opts.getValue("marker")
  val cycles = opts["cycles"]?.toInt() ?: 5
  val budgetMs = opts["budget-ms"]?.toLong() ?: 2_500L
  val report = File(opts.getValue("report"))

  val descriptor = DaemonLaunchDescriptor.parse(descriptorFile.readText())
  val sourceDirs = descriptor.sourceWatch?.sourceDirs.orEmpty().map(::File)
  val sources =
    sourceDirs
      .flatMap { dir -> dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }
      .map { it.canonicalPath }
      .distinct()
      .sorted()
  check(edit.path in sources) { "$edit is not among the module's sources: $sourceDirs" }

  val original = edit.readText()
  check(original.contains("\"$marker\"")) { "$edit does not contain \"$marker\"" }
  val nonce = System.currentTimeMillis().toString(36)

  val spawn =
    SubprocessDaemonClientFactory().spawn(WorkspaceId.derive(workspace.name, workspace), descriptor)
  val client = spawn.client(onNotification = { _, _ -> }, onClose = {})
  val results = mutableListOf<Cycle>()
  var warmUp = "not run"
  var warmUpReady = false
  try {
    client.initialize(
      workspaceRoot = workspace.path,
      moduleId = descriptor.modulePath,
      moduleProjectDir = descriptor.workingDirectory,
    )
    val warm = client.compileWarmUp(listOf(edit.path), timeout = 5.minutes)
    warmUpReady = warm?.ready == true
    warmUp = "ready=$warmUpReady ${warm?.durationMs} ms ${warm?.message.orEmpty()}".trim()

    for (n in 1..cycles) {
      // A fresh string each cycle and each run, so no cache can stand in for a compile.
      edit.writeText(original.replace("\"$marker\"", "\"$marker $nonce-$n\""))
      val started = System.nanoTime()
      val result =
        client.compileSources(
          CompileSourcesParams(
            sources = sources,
            changes = SourceChangeSet(modified = listOf(edit.path), removed = emptyList()),
          ),
          timeout = 5.minutes,
        )
      val wallMs = (System.nanoTime() - started) / 1_000_000
      results +=
        Cycle(
          n,
          result.result,
          wallMs,
          result.durationMs,
          result.errors.take(3).joinToString("; ") {
            "${File(it.file).name}:${it.line} ${it.message}"
          },
        )
    }
  } finally {
    edit.writeText(original)
    runCatching { spawn.shutdown() }
  }

  val warmCycles = results.drop(1).ifEmpty { results }
  val median = warmCycles.map { it.wallMs }.sorted().let { it[it.size / 2] }
  val failures = buildList {
    if (!warmUpReady) add("compileWarmUp found nothing to warm: $warmUp")
    results
      .filter { it.kind != CompileResultKind.OK }
      .forEach { add("cycle ${it.n}: ${it.kind} ${it.errors}".trim()) }
    if (median > budgetMs) add("median warm compile $median ms > budget $budgetMs ms")
  }

  val markdown = buildString {
    appendLine("## Warm edit loop (${descriptor.modulePath})")
    appendLine()
    appendLine("Warm-up: $warmUp")
    appendLine()
    appendLine("| Cycle | Result | Wall ms | Daemon ms |")
    appendLine("|---|---|---|---|")
    results.forEach { appendLine("| ${it.n} | ${it.kind} | ${it.wallMs} | ${it.daemonMs} |") }
    appendLine()
    appendLine("Median warm compile (cycles 2+): **$median ms** (budget $budgetMs ms)")
    if (failures.isNotEmpty()) {
      appendLine()
      appendLine("**Failures**")
      failures.forEach { appendLine("- $it") }
    }
  }
  report.parentFile?.mkdirs()
  report.writeText(markdown)
  System.getenv("GITHUB_STEP_SUMMARY")?.let { File(it).appendText(markdown) }
  println(markdown)
  if (failures.isNotEmpty()) exitProcess(1)
}

private data class Cycle(
  val n: Int,
  val kind: CompileResultKind,
  val wallMs: Long,
  val daemonMs: Long,
  val errors: String,
)
