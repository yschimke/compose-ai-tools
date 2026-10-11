package ee.schimke.composeai.cli

import ee.schimke.composeai.io.SystemFileSystem
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.exitProcess
import okio.FileSystem

/**
 * `compose-preview pin [VERSION] [--cli] [--remove] [--json]`: read and write the project version
 * pin, so the CLI, VS Code extension and `install` / `apply` actions drive one release.
 *
 * - `compose-preview pin` — report the resolved pin, its source, and whether the CLI matches. Exits
 *   0
 *   either way; no pin is legitimate.
 * - `compose-preview pin <version>` — write `composePreview.version=<version>` to
 *   `gradle.properties`.
 * - `compose-preview pin --cli` — the same, using this CLI's version (the common flow).
 * - `compose-preview pin --remove` — delete the pin line.
 *
 * `--json` prints the report machine-readably. Only `gradle.properties` is ever written: a version
 * catalog pin (`[versions] composePreviewCli`) is read but never rewritten (Renovate owns it), and
 * build scripts are never touched. See [resolveVersionPin].
 */
class PinCommand(
  private val args: List<String>,
  private val projectRoot: File? = findGradleProjectRoot(),
  private val cliVersion: String = BUNDLE_VERSION,
  // What `--cli` writes: [MAVEN_LINE_VERSION], not [cliVersion], because the pin becomes a plugin
  // coordinate and must exist on Central. [cliVersion] remains what this command reports.
  private val mavenLineVersion: String = MAVEN_LINE_VERSION,
  private val fileSystem: FileSystem = SystemFileSystem,
  private val env: (String) -> String? = System::getenv,
  private val stdout: (String) -> Unit = ::println,
  private val stderr: (String) -> Unit = System.err::println,
) {
  fun run() {
    val json = "--json" in args
    val remove = "--remove" in args || "--unset" in args
    val useCli = "--cli" in args
    val positional = args.firstOrNull { !it.startsWith("-") }

    if (remove && (useCli || positional != null)) {
      stderr("compose-preview pin: --remove takes no version.")
      exitProcess(1)
    }
    if (useCli && positional != null) {
      stderr("compose-preview pin: pass --cli or a version, not both.")
      exitProcess(1)
    }

    val root =
      projectRoot
        ?: run {
          stderr(
            "compose-preview pin: cannot find a Gradle project root " +
              "(no settings.gradle[.kts] and no gradlew at or above this directory)."
          )
          exitProcess(1)
        }

    when {
      remove -> {
        val removed = removeGradlePropertiesPin(root, fileSystem)
        if (removed) stderr("compose-preview: removed the version pin from gradle.properties.")
        else stderr("compose-preview: no $VERSION_PIN_PROPERTY pin in gradle.properties.")
        // A catalog `composePreviewCli` entry still pins the project after the line is removed; say
        // so.
        val remaining =
          resolveVersionPin(root, args = emptyList(), env = env, fileSystem = fileSystem)
        if (remaining != null) {
          stderr(
            "compose-preview: still pinned to ${remaining.version} via ${remaining.source.display}. " +
              (if (remaining.source == VersionPinSource.VERSION_CATALOG)
                "`pin` never edits a version catalog (Renovate owns it) — remove the " +
                  "$VERSION_PIN_CATALOG_KEY entry by hand to fully unpin."
              else "Unset it to fully unpin.")
          )
        }
        report(root, json, warnSkew = false)
      }
      useCli || positional != null -> {
        val version = (positional ?: mavenLineVersion).trim().removePrefix("v")
        if (version.isEmpty()) {
          stderr("compose-preview pin: version must not be empty.")
          exitProcess(1)
        }
        val file = writeGradlePropertiesPin(root, version, fileSystem)
        stderr("compose-preview: pinned compose-preview $version in ${file.path}")
        report(root, json, warnSkew = false)
      }
      else -> report(root, json, warnSkew = true)
    }
  }

  /**
   * Print the current pin state. [warnSkew] is off right after a write or remove, where a skew
   * warning would read as a failed write; the report still shows both versions.
   */
  private fun report(root: File, json: Boolean, warnSkew: Boolean) {
    val pin = resolveVersionPin(root, args = emptyList(), env = env, fileSystem = fileSystem)
    if (json) {
      stdout(
        buildString {
          append("{\n")
          append("  \"pinned\": ${pin != null},\n")
          append("  \"version\": ${pin?.version.jsonOrNull()},\n")
          append("  \"source\": ${pin?.source?.display.jsonOrNull()},\n")
          append("  \"cliVersion\": ${cliVersion.jsonOrNull()},\n")
          append("  \"matchesCli\": ${pin == null || pin.version == cliVersion}\n")
          append("}")
        }
      )
      return
    }
    if (pin == null) {
      stdout(
        "No version pin. Every entrypoint uses its own bundled version " +
          "(this CLI: $cliVersion).\n" +
          "Pin the project with `compose-preview pin --cli`."
      )
      return
    }
    stdout("pinned:  ${pin.version}   (${pin.source.display})")
    stdout("CLI:     $cliVersion")
    // Fresh latch: `pin` always reports skew, even if an earlier call warned.
    if (warnSkew) warnOnCliSkew(pin, cliVersion, stderr, once = AtomicBoolean(false))
  }

  private fun String?.jsonOrNull(): String =
    if (this == null) "null" else "\"" + replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}
