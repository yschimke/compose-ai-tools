package ee.schimke.composeai.cli

import ee.schimke.composeai.io.SystemFileSystem
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.exitProcess
import okio.FileSystem

/**
 * `compose-preview pin [VERSION] [--cli] [--remove] [--json]`
 *
 * Reads and writes the **project version pin** — the single place a project names the
 * compose-preview version, so the CLI, the VS Code extension and the `install` / `apply` GitHub
 * actions all drive the same release instead of each picking one independently (issue #3738).
 *
 * Forms:
 * - `compose-preview pin` — report the resolved pin, where it came from, and whether the CLI on
 *   `$PATH` matches it. Exits 0 whether or not a pin exists; "no pin" is a legitimate state (the
 *   zero-config path), not an error.
 * - `compose-preview pin <version>` — write `composePreview.version=<version>` into the project's
 *   `gradle.properties`.
 * - `compose-preview pin --cli` — the same, using the version of the CLI you are running. This is
 *   the common flow: install a CLI, then make the project agree with it.
 * - `compose-preview pin --remove` — delete the pin line, returning the project to "every
 *   entrypoint uses its own bundled version".
 *
 * `--json` prints the machine-readable form of the report, for agents and CI steps.
 *
 * Writes only ever touch `gradle.properties`. A pin already expressed in a version catalog
 * (`[versions] composePreviewCli`, the convention the composite actions document) is *read* as a
 * pin but never rewritten: catalogs are Renovate-managed, and silently editing one behind the bot's
 * back is how a pin and its update automation start fighting. Build scripts are never rewritten
 * either — the pin governs the **auto-injected** plugin, and a module that declares
 * `id("ee.schimke.composeai.preview") version "…"` itself is one auto-inject already skips, so its
 * own declaration stays the single source of truth for that module. Scope note:
 * [resolveVersionPin].
 */
class PinCommand(
  private val args: List<String>,
  private val projectRoot: File? = findGradleProjectRoot(),
  private val cliVersion: String = BUNDLE_VERSION,
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
          stderr("compose-preview pin: cannot find a Gradle project root (no gradlew found).")
          exitProcess(1)
        }

    when {
      remove -> {
        val removed = removeGradlePropertiesPin(root, fileSystem)
        if (removed) stderr("compose-preview: removed the version pin from gradle.properties.")
        else stderr("compose-preview: no $VERSION_PIN_PROPERTY pin in gradle.properties.")
        report(root, json, warnSkew = false)
      }
      useCli || positional != null -> {
        val version = (positional ?: cliVersion).trim().removePrefix("v")
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
   * Prints the current pin state. [warnSkew] is off right after a write or a remove: the user just
   * told us what they want, and echoing "…but the CLI is on X" as a warning in the same breath
   * reads as the write having failed. The report line still shows both versions.
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
    // Fresh latch: `pin` exists to answer this question, so it always reports skew even if an
    // earlier call in the same process already warned.
    if (warnSkew) warnOnCliSkew(pin, cliVersion, stderr, once = AtomicBoolean(false))
  }

  private fun String?.jsonOrNull(): String =
    if (this == null) "null" else "\"" + replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}
