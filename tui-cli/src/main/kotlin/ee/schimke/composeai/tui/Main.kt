package ee.schimke.composeai.tui

import com.jakewharton.mosaic.runMosaicMain
import ee.schimke.composeai.cli.DriverOptions
import ee.schimke.composeai.cli.GradlePreviewDriver
import ee.schimke.composeai.cli.PreviewModule
import ee.schimke.composeai.tui.ui.App
import ee.schimke.composeai.tui.ui.runBundle
import java.io.File
import java.io.FileOutputStream
import java.io.PrintStream
import java.time.Instant
import kotlin.system.exitProcess

fun main(argv: Array<String>) {
  val args = TuiArgs.parse(argv)

  // Bundle-PNG mode: skip discovery and the browser entirely and open straight into the image-only
  // live view. A bundle is self-contained, so this path assumes NO project context — it spawns the
  // bundle's own daemon from its embedded classes (see `runBundle`). The stderr log lands next to
  // the PNG (or under a project root if we happen to be in one) so daemon stderr can't corrupt the
  // live screen.
  val bundlePng = args.bundlePng
  if (bundlePng != null) {
    val logRoot =
      args.projectRoot?.absoluteFile ?: findProjectRoot() ?: bundlePng.absoluteFile.parentFile
        ?: File(".")
    redirectStderrToLogFile(logRoot)
    runBundle(bundlePng, args)
    return
  }

  val projectRoot =
    args.projectRoot?.absoluteFile
      ?: findProjectRoot()
      ?: run {
        System.err.println("error: not in a Gradle project (no gradlew found walking up from cwd)")
        exitProcess(2)
      }

  val modules: List<PreviewModule> =
    if (args.noDiscovery) {
      val gradlePath =
        args.module
          ?: run {
            System.err.println("error: --no-discovery requires --module <gradle path>")
            exitProcess(2)
          }
      listOf(syntheticModule(projectRoot, gradlePath))
    } else {
      resolveModulesViaGradle(projectRoot, args)
    }

  // Mosaic owns stdin/stdout from this point. Any `println` inside the composition will land on
  // the live screen. `runMosaicMain` is the synchronous entry — it blocks the main thread until
  // the composition ends (we exitProcess from inside the App composable's q-handler).
  //
  // The daemon subprocess we spawn for live mode forwards its child stderr through
  // `System.err.println("[daemon …] …")` (see `SubprocessDaemonClientFactory.forwardStderr`),
  // and a handful of other paths in the shared `:mcp` module fall back to `System.err.println` on
  // transport errors. Both would corrupt the live screen mid-render. Redirect System.err to a
  // per-session log file so those writes land somewhere a user can `tail -f` instead of on the
  // TTY. Real failures we want the user to see surface via `LiveSession.state.lastError`.
  redirectStderrToLogFile(projectRoot)

  runMosaicMain { App(modules = modules, args = args) }
}

/**
 * Replace [System.err] with an append-mode log file under
 * `<projectRoot>/build/compose-preview-tui.log`. Best-effort: if we can't create the file we leave
 * System.err pointing at the terminal — better to have a corrupted display than to crash the
 * launcher because `build/` isn't writable.
 */
private fun redirectStderrToLogFile(projectRoot: File) {
  val logFile = File(projectRoot, "build/compose-preview-tui.log")
  try {
    logFile.parentFile?.mkdirs()
    val stream = PrintStream(FileOutputStream(logFile, /* append= */ true), /* autoFlush= */ true)
    stream.println("--- compose-preview-tui session at ${Instant.now()} ---")
    System.setErr(stream)
    Runtime.getRuntime()
      .addShutdownHook(
        Thread {
          // Best-effort flush; the JVM is exiting anyway so a failure here is invisible.
          runCatching { stream.flush() }
          runCatching { stream.close() }
        }
      )
  } catch (_: Throwable) {
    // Stay on the terminal. Garbled output is better than no TUI.
  }
}

/**
 * Convert a Gradle path (`:foo:bar`) plus a project root into a `PreviewModule` whose `projectDir`
 * points at the corresponding subdirectory. Used by `--no-discovery` for tests and by sandbox runs
 * against pre-rendered fixtures.
 *
 * The leading colon is stripped and every remaining colon turns into a path separator —
 * `:samples:android` → `<root>/samples/android`. A bare `:sample` becomes `<root>/sample`.
 */
private fun syntheticModule(projectRoot: File, gradlePath: String): PreviewModule {
  val relative = gradlePath.trimStart(':').replace(':', '/').ifEmpty { "." }
  return PreviewModule(gradlePath = gradlePath, projectDir = File(projectRoot, relative))
}

private fun resolveModulesViaGradle(projectRoot: File, args: TuiArgs): List<PreviewModule> {
  val driver =
    GradlePreviewDriver(
      projectRoot = projectRoot,
      options =
        DriverOptions(
          verbose = args.verbose,
          progress = false,
          timeoutSeconds = args.timeoutSeconds,
        ),
    )
  return try {
    val selectedPath = args.module
    if (selectedPath != null) {
      val one = driver.discoverModule(selectedPath)
      if (one == null) {
        System.err.println("error: module '$selectedPath' not found in $projectRoot")
        exitProcess(3)
      }
      listOf(one)
    } else {
      val all = driver.discoverModules()
      if (all.isEmpty()) {
        System.err.println("error: no preview-capable modules discovered in $projectRoot")
        exitProcess(3)
      }
      all
    }
  } catch (t: Throwable) {
    System.err.println("error: discovery failed: ${t.message}")
    if (args.verbose) t.printStackTrace(System.err)
    exitProcess(1)
  } finally {
    driver.close()
  }
}

private fun findProjectRoot(): File? {
  var dir: File? = File(".").absoluteFile
  while (dir != null) {
    if (File(dir, "gradlew").exists()) return dir
    dir = dir.parentFile
  }
  return null
}
