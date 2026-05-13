package ee.schimke.composeai.cli

import kotlin.system.exitProcess
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * `compose-preview extensions list` — enumerates the data extensions the CLI knows how to enable
 * + render canned reports for. Discoverability surface: agents and humans see what
 *   `--with-extension <id>` will accept without grepping the source.
 *
 * Pure read of [builtInExtensionReporters]; doesn't shell out to Gradle. JSON envelope pinned at
 * `compose-preview-extensions/v1` for agents.
 */
class ExtensionsCommand(private val args: List<String>) {
  fun run() {
    val sub = args.firstOrNull { !it.startsWith("-") } ?: "list"
    when (sub) {
      "list" -> list("--json" in args)
      "help" -> printUsage()
      else -> {
        System.err.println("Unknown extensions subcommand: $sub")
        printUsage()
        exitProcess(1)
      }
    }
  }

  private fun list(jsonOutput: Boolean) {
    val entries =
      builtInExtensionReporters()
        .map { (id, factory) ->
          val renderer = factory()
          ExtensionInfo(
            id = id,
            displayName = renderer.displayName,
            description = renderer.description,
            enableProperty = "composePreview.previewExtensions.$id.enableAllChecks",
          )
        }
        .sortedBy { it.id }

    if (jsonOutput) {
      val payload = ExtensionsListResponse(schema = EXTENSIONS_LIST_SCHEMA, extensions = entries)
      println(jsonEncoder.encodeToString(payload))
      return
    }

    if (entries.isEmpty()) {
      println("No extensions registered.")
      return
    }

    println("Available data extensions:")
    for (e in entries) {
      println("  ${e.id}  — ${e.displayName}")
      println("    ${e.description}")
      println("    enable: --with-extension ${e.id}  (or -P${e.enableProperty}=true)")
    }
    println()
    println(
      "Each id can be passed to `--with-extension <id>` on any render-driving command " +
        "(`show`, `render`, `a11y`, …). Per-extension canned reports — like the ATF findings " +
        "`compose-preview a11y` prints — are surfaced by binding the id to a `ReportCommand`."
    )
  }

  private fun printUsage() {
    println(
      """
      compose-preview extensions — data extension introspection

      Subcommands:
        list           List registered data extensions (default)
        help           Show this help message

      Options:
        --json         Emit JSON (schema: $EXTENSIONS_LIST_SCHEMA)
      """
        .trimIndent()
    )
  }
}

@Serializable
internal data class ExtensionInfo(
  val id: String,
  val displayName: String,
  val description: String,
  /** Gradle property the CLI's `--with-extension <id>` plumbing forwards as `=true`. */
  val enableProperty: String,
)

@Serializable
internal data class ExtensionsListResponse(val schema: String, val extensions: List<ExtensionInfo>)

internal const val EXTENSIONS_LIST_SCHEMA = "compose-preview-extensions/v1"

private val jsonEncoder = Json {
  prettyPrint = true
  encodeDefaults = true
}
