package ee.schimke.composeai.cli

import java.io.File
import kotlin.system.exitProcess

/**
 * Materialises the bundled auto-inject init script and prints metadata about it. Exists so external
 * tooling (CI workflows, scripts, agent harnesses) can drive Gradle directly with the same
 * `--init-script` body the CLI uses internally — without having to vendor a copy of the script and
 * keep it in lockstep with the bundled plugin version.
 *
 * Subcommands:
 * - `--path` (default) — write the script to its cache location and print the absolute path on
 *   stdout. Idempotent: re-running with the same bundle leaves the file untouched.
 * - `--print` — emit the rendered script body on stdout instead of a path. Useful for ad-hoc
 *   pipelines that prefer a pipe over a filesystem handoff.
 */
class InitScriptCommand(
  private val args: List<String>,
  private val pluginVersion: String = BUNDLE_VERSION,
  private val storageDir: File = defaultInitScriptStorageDir(pluginVersion),
  private val stdout: (String) -> Unit = ::print,
  private val stderr: (String) -> Unit = System.err::println,
) {
  fun run() {
    val printContent = "--print" in args
    val pathOnly = "--path" in args
    if (printContent && pathOnly) {
      stderr("compose-preview init-script: pass --path OR --print, not both.")
      exitProcess(1)
    }
    if (printContent) {
      stdout(renderInitScript(pluginVersion))
      return
    }
    val target: File =
      try {
        materializeInitScript(storageDir, pluginVersion)
      } catch (e: Exception) {
        stderr(
          "compose-preview init-script: failed to materialise init script in $storageDir: ${e.message}"
        )
        exitProcess(1)
      }
    stdout(target.absolutePath + "\n")
  }
}
