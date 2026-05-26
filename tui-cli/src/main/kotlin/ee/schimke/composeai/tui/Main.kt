package ee.schimke.composeai.tui

import com.jakewharton.mosaic.runMosaicMain
import ee.schimke.composeai.cli.DriverOptions
import ee.schimke.composeai.cli.GradlePreviewDriver
import ee.schimke.composeai.cli.PreviewModule
import ee.schimke.composeai.tui.ui.App
import java.io.File
import kotlin.system.exitProcess

fun main(argv: Array<String>) {
  val args = TuiArgs.parse(argv)

  val projectRoot =
    args.projectRoot?.absoluteFile
      ?: findProjectRoot()
      ?: run {
        System.err.println("error: not in a Gradle project (no gradlew found walking up from cwd)")
        exitProcess(2)
      }

  // Resolve modules up front (cold path). The TUI then drives the live session against the
  // user's selection without going back through the Tooling API — this is just discovery.
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

  val modules: List<PreviewModule> =
    try {
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
      // The TUI doesn't need the Tooling API connection alive while it runs; the live session
      // talks to the daemon directly via `:render-session-subprocess`. Closing here keeps the
      // Tooling API daemon free for other invocations the user might fire from a sibling
      // terminal.
      driver.close()
    }

  // Mosaic owns stdin/stdout from this point. Any `println` inside the composition will land
  // on the live screen. `runMosaicMain` is the synchronous entry — it blocks the main thread
  // until the composition ends (we exitProcess from inside the App composable's q-handler).
  runMosaicMain { App(modules = modules, args = args) }
}

private fun findProjectRoot(): File? {
  var dir: File? = File(".").absoluteFile
  while (dir != null) {
    if (File(dir, "gradlew").exists()) return dir
    dir = dir.parentFile
  }
  return null
}
