@file:OptIn(org.jetbrains.kotlin.buildtools.api.ExperimentalBuildToolsApi::class)
@file:JvmName("BtaBenchMain")

package ee.schimke.composeai.daemon.bta

import ee.schimke.composeai.io.SystemFileSystem
import java.io.File
import java.net.URLClassLoader
import java.nio.file.Path
import okio.Path.Companion.toPath
import org.jetbrains.kotlin.buildtools.api.ExperimentalBuildToolsApi
import org.jetbrains.kotlin.buildtools.api.SourcesChanges

/**
 * Stage-2 measurement driver for `:samples:*-daemon-bench:benchCompileStages` — tracked in
 * [#1586](https://github.com/yschimke/compose-ai-tools/issues/1586). See
 * [docs/daemon/COMPILE-IN-PROCESS.md](../../../../../../docs/daemon/COMPILE-IN-PROCESS.md) § "What
 * we expect to measure".
 *
 * This is a thin, standalone `main` so the bench Gradle task can `javaexec` it on `:daemon:core`'s
 * runtime classpath, feeding it the exact `btaCompile` block the daemon would consume at startup
 * (via the same `composeai.daemon.bta.*` system properties [DefaultBtaCompileService.fromSysprops]
 * reads). It drives the *real* production [BtaCompileSession.compileIncremental] — the same code
 * path the daemon's `compileSources` handler runs — so the numbers it emits are the stage-2 compile
 * leg, not an approximation.
 *
 * It deliberately does NOT spawn a daemon or speak JSON-RPC: the only stage-2 surface the bench
 * needs to time is `compileIncremental` (compile leg) and the child-classloader rotation that
 * follows a successful compile (`classloader-swap`). The render leg is unchanged from stage 0 (the
 * daemon's hot-swap reuses the same renderer), so the bench reuses its stage-0 render number rather
 * than re-measuring it here.
 *
 * Output protocol — one tab-separated record per line on stdout, parsed by the bench task:
 * - `BENCHROW\t<phase>\t<scenario>\t<run>\t<milliseconds>\t<notes>` — a measured CSV row.
 * - `BENCHMEM\t<usedHeapMb>` — resident used-heap after warm-up, for the memory-delta criterion.
 * - `BENCHNOTE\t<message>` — stage 2 unavailable / ineligible / failed; bench records it verbatim
 *   and keeps going (a missing stage-2 number is informational, not a hard failure).
 *
 * The driver mutates the edit file in place (the `warm-after-1-line-edit` scenario) and always
 * restores it in a `finally`, so a crash mid-run can't leave the working tree dirty.
 */
fun main() {
  val sysprops: (String) -> String? = System::getProperty
  val config = BenchConfig.fromSysprops(sysprops)
  if (config == null) {
    emit(note("stage-2 unavailable: launch descriptor carried no btaCompile block"))
    return
  }
  val ineligibility =
    sysprops(DefaultBtaCompileService.SYSPROP_INELIGIBILITY_REASON)?.ifEmpty { null }
  if (ineligibility != null) {
    emit(note("stage-2 ineligible for this module: $ineligibility"))
    return
  }

  val editFile = config.editFile.toFile()
  val original =
    if (editFile.exists()) SystemFileSystem.read(editFile.path.toPath()) { readUtf8() } else null
  try {
    runBench(config)
  } catch (t: Throwable) {
    // A BTA bootstrap / compile fault is informational for the bench — surface it and exit 0 so a
    // scheduled job stays green and a human reads the note. (The cheap composePreviewRender smoke
    // is the real green-gate; this task only produces measurement rows.)
    emit(note("stage-2 driver failed: ${t.javaClass.simpleName}: ${t.message}"))
  } finally {
    if (original != null) SystemFileSystem.write(editFile.path.toPath()) { writeUtf8(original) }
  }
}

private const val COLD_NOTE = "BTA impl bootstrap + first compileIncremental()"
private const val WARM_NOTE = "BtaCompileSession.compileIncremental()"
private const val SWAP_NOTE =
  "URLClassLoader rotation over output classes dir (same shape as UserClassLoaderHolder)"

private fun runBench(config: BenchConfig) {
  val session =
    BtaCompileSession(
      implClasspath = config.implClasspath,
      icWorkingDir = config.icWorkingDir,
      moduleName = config.moduleName,
    )
  val plugins = DefaultBtaCompileService.composeCompilerPlugins(config.compilerPlugins)

  fun compileFull(changes: SourcesChanges) =
    session.compileIncremental(
      sources = config.sources,
      compileClasspath = config.compileClasspath,
      outputDir = config.outputDir,
      compilerPlugins = plugins,
      sourcesChanges = changes,
    )

  // Cold first save: pays the BTA impl-classloader bootstrap (~5 s in the spike). One tax per
  // daemon JVM; recorded so the README's stage table stays honest about it.
  val coldMs = timeMillis { compileFull(SourcesChanges.ToBeCalculated) }
  emit(row("compile", "stage-2-cold-first-save", 1, coldMs, COLD_NOTE))

  // Warm the frontend + IC cache so the recorded warm runs don't absorb first-touch cost.
  repeat(config.warmups) { compileFull(SourcesChanges.ToBeCalculated) }

  val editFile = config.editFile.toFile()
  val pristine = SystemFileSystem.read(editFile.path.toPath()) { readUtf8() }
  check(config.editMarker in pristine) {
    "${config.editFile} no longer contains ${config.editMarker} — update the bench marker"
  }

  // Rotate a child classloader the same way UserClassLoaderHolder does after a swap: a parent over
  // the compile classpath, a short-lived child over the freshly-written output classes dir.
  val parentUrls = config.compileClasspath.map { it.toUri().toURL() }.toTypedArray()
  val parent = URLClassLoader(parentUrls, BtaBenchClassLoaderMarker::class.java.classLoader)
  val outputUrl = config.outputDir.toUri().toURL()
  var child: URLClassLoader? = null

  try {
    for (run in 1..config.runs) {
      SystemFileSystem.write(editFile.path.toPath()) {
        writeUtf8(mutateMarker(pristine, config.editMarker))
      }
      try {
        // Editor knows the dirty file → SourcesChanges.Known, exactly what the daemon forwards.
        val changes = SourcesChanges.Known(listOf(editFile), emptyList())
        val compileMs = timeMillis { compileFull(changes) }
        emit(row("compile", "stage-2-warm-after-1-line-edit", run, compileMs, WARM_NOTE))

        val swapMs = timeMillis {
          val next = URLClassLoader(arrayOf(outputUrl), parent)
          child?.close()
          child = next
        }
        emit(row("classloader-swap", "stage-2-warm", run, swapMs, SWAP_NOTE))
      } finally {
        SystemFileSystem.write(editFile.path.toPath()) { writeUtf8(pristine) }
      }
    }
  } finally {
    child?.close()
    parent.close()
  }

  System.gc()
  val runtime = Runtime.getRuntime()
  val usedMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
  emit("BENCHMEM\t$usedMb")
  emit(note("stage-2 resident used-heap after warm-up: $usedMb MB (BTA frontend loaded)"))
}

/** Marker type whose classloader roots the bench's parent loader. */
private class BtaBenchClassLoaderMarker

// --- Pure helpers (unit-tested in BtaBenchMainTest) -------------------------------------------

/**
 * Bench JVM-side config, assembled from the daemon `btaCompile` sysprops plus `composeai.bench.*`.
 */
internal data class BenchConfig(
  val implClasspath: List<Path>,
  val compileClasspath: List<Path>,
  val compilerPlugins: List<Path>,
  val moduleName: String,
  val outputDir: Path,
  val icWorkingDir: Path,
  val sources: List<Path>,
  val editFile: Path,
  val editMarker: String,
  val warmups: Int,
  val runs: Int,
) {
  companion object {
    const val SYSPROP_SOURCES = "composeai.bench.sources"
    const val SYSPROP_EDIT_FILE = "composeai.bench.editFile"
    const val SYSPROP_EDIT_MARKER = "composeai.bench.editMarker"
    const val SYSPROP_WARMUPS = "composeai.bench.warmups"
    const val SYSPROP_RUNS = "composeai.bench.runs"

    /** Returns null when the daemon `btaCompile` block (or the bench inputs) are absent. */
    fun fromSysprops(sysprops: (String) -> String?): BenchConfig? {
      val impl = parsePathList(sysprops(DefaultBtaCompileService.SYSPROP_IMPL_CLASSPATH))
      val compile = parsePathList(sysprops(DefaultBtaCompileService.SYSPROP_COMPILE_CLASSPATH))
      val plugins = parsePathList(sysprops(DefaultBtaCompileService.SYSPROP_COMPILER_PLUGINS))
      val moduleName = sysprops(DefaultBtaCompileService.SYSPROP_MODULE_NAME)?.ifEmpty { null }
      val outputDir = sysprops(DefaultBtaCompileService.SYSPROP_OUTPUT_DIR)?.ifEmpty { null }
      val icDir = sysprops(DefaultBtaCompileService.SYSPROP_IC_WORKING_DIR)?.ifEmpty { null }
      val sources = parsePathList(sysprops(SYSPROP_SOURCES))
      val editFile = sysprops(SYSPROP_EDIT_FILE)?.ifEmpty { null }
      if (impl.isEmpty() || moduleName == null || outputDir == null || icDir == null) return null
      if (sources.isEmpty() || editFile == null) return null
      return BenchConfig(
        implClasspath = impl,
        compileClasspath = compile,
        compilerPlugins = plugins,
        moduleName = moduleName,
        outputDir = Path.of(outputDir),
        icWorkingDir = Path.of(icDir),
        sources = sources,
        editFile = Path.of(editFile),
        editMarker = sysprops(SYSPROP_EDIT_MARKER)?.ifEmpty { null } ?: "\"three\"",
        warmups = sysprops(SYSPROP_WARMUPS)?.toIntOrNull() ?: 2,
        runs = sysprops(SYSPROP_RUNS)?.toIntOrNull() ?: 5,
      )
    }
  }
}

internal fun parsePathList(value: String?): List<Path> =
  if (value.isNullOrEmpty()) emptyList()
  else value.split(File.pathSeparator).filter { it.isNotEmpty() }.map { Path.of(it) }

/**
 * Swap the [marker] string literal for a unique one so the recompile produces genuinely different
 * bytecode (kotlinc strips comments, so a comment-only edit would leave the `.class` files — and
 * therefore the IC pass — UP-TO-DATE). Mirrors the existing `benchPreviewLatency` edit.
 */
internal fun mutateMarker(text: String, marker: String): String =
  text.replaceFirst(marker, marker.dropLast(1) + "-${System.nanoTime() % 1_000_000}\"")

internal fun row(phase: String, scenario: String, run: Int, ms: Long, notes: String): String =
  "BENCHROW\t$phase\t$scenario\t$run\t$ms\t${notes.replace("\t", " ")}"

internal fun note(message: String): String = "BENCHNOTE\t${message.replace("\t", " ")}"

private inline fun timeMillis(block: () -> Unit): Long {
  val start = System.nanoTime()
  block()
  return (System.nanoTime() - start) / 1_000_000
}

private fun emit(line: String) {
  // The daemon swaps System.out to stderr at startup; the bench main keeps stdout clean for its
  // tab-separated protocol and lets BTA's own logging go to stderr.
  println(line)
}
