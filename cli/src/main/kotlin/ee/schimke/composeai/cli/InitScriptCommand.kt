package ee.schimke.composeai.cli

import ee.schimke.composeai.io.SystemFileSystem
import java.io.File
import kotlin.system.exitProcess
import okio.FileSystem

/**
 * Materialises the auto-inject init script so external tooling can drive Gradle with the same
 * `--init-script` the CLI uses, without vendoring a copy.
 * - `--path` (default) — write it to its cache location and print the path (idempotent).
 * - `--print` — print the script body instead.
 *
 * The baked version follows the usual precedence (`--plugin-version`, the project pin via
 * [resolveVersionPin], then [MAVEN_LINE_VERSION]), so `./gradlew --init-script "$(compose-preview
 * init-script --path)"` matches `compose-preview render`.
 */
class InitScriptCommand(
  private val args: List<String>,
  private val projectRoot: File? = findGradleProjectRoot(),
  private val fileSystem: FileSystem = SystemFileSystem,
  private val stdout: (String) -> Unit = ::print,
  private val stderr: (String) -> Unit = System.err::println,
  private val pluginVersion: String =
    resolvePluginVersion(
      projectRoot = projectRoot,
      args = args,
      fileSystem = fileSystem,
      stderr = stderr,
    ),
  private val storageDir: File = defaultInitScriptStorageDir(pluginVersion),
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
        materializeInitScript(storageDir, pluginVersion, fileSystem)
      } catch (e: Exception) {
        stderr(
          "compose-preview init-script: failed to materialise init script in $storageDir: ${e.message}"
        )
        exitProcess(1)
      }
    stdout(target.absolutePath + "\n")
  }
}
