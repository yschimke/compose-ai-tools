package ee.schimke.composeai.cli

import ee.schimke.composeai.data.render.RenderPreviewExtension
import ee.schimke.composeai.data.render.pipeline.PreviewExtensionCliCommand
import ee.schimke.composeai.data.render.pipeline.PreviewExtensionDescriptor
import ee.schimke.composeai.data.render.pipeline.PreviewExtensionUsageMode
import kotlin.system.exitProcess
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal const val EXTENSION_COMMANDS_SCHEMA = "compose-preview-extension-commands/v1"

private val extensionJson = Json {
  prettyPrint = true
  encodeDefaults = true
}

@Serializable
data class ExtensionCommandsResponse(
  val schema: String = EXTENSION_COMMANDS_SCHEMA,
  val extensions: List<PreviewExtensionDescriptor>,
) {
  val commandCount: Int = extensions.sumOf { it.cliCommands.size }
}

class ExtensionCommandsCommand(private val args: List<String>) {
  private val jsonOutput = "--json" in args
  private val onlyAgentRecommended = "--agent" in args || "--agent-recommended" in args

  fun run() {
    if ("--help" in args || "-h" in args) {
      printUsage()
      return
    }

    when (args.firstOrNull { !it.startsWith("-") } ?: "commands") {
      "commands",
      "list" -> printCommands()
      else -> {
        System.err.println("extensions supports: commands")
        printUsage()
        exitProcess(1)
      }
    }
  }

  private fun printCommands() {
    val response = ExtensionCommandsResponse(extensions = BuiltInExtensionCliCatalog.extensions)
    val filtered =
      if (onlyAgentRecommended) {
        response.copy(
          extensions =
            response.extensions
              .map { descriptor ->
                descriptor.copy(cliCommands = descriptor.cliCommands.filter { it.agentRecommended })
              }
              .filter { it.cliCommands.isNotEmpty() }
        )
      } else {
        response
      }

    if (jsonOutput) {
      println(extensionJson.encodeToString(ExtensionCommandsResponse.serializer(), filtered))
    } else {
      printText(filtered)
    }
  }

  private fun printText(response: ExtensionCommandsResponse) {
    response.extensions.forEach { extension ->
      if (extension.cliCommands.isEmpty()) return@forEach
      println("${extension.id} - ${extension.displayName}")
      extension.cliCommands.forEach { command ->
        val marker = if (command.agentRecommended) " [agent]" else ""
        println("  ${command.id}$marker")
        if (command.summary.isNotBlank()) {
          println("    ${command.summary}")
        }
        println("    ${command.command.joinToString(" ")}")
      }
    }
  }

  private fun printUsage() {
    println(
      """
      compose-preview extensions commands [--json] [--agent]

      Lists command metadata contributed by built-in preview extensions. This is
      daemon-free: clients can discover extension-oriented CLI entry points
      without starting a render daemon.
      """
        .trimIndent()
    )
  }
}

private object BuiltInExtensionCliCatalog {
  val extensions: List<PreviewExtensionDescriptor> =
    listOf(
      RenderPreviewExtension.deviceClipDescriptor,
      RenderPreviewExtension.renderTraceDescriptor,
      RenderPreviewExtension.composeTraceDescriptor,
      RenderPreviewExtension.overlayLegendDescriptor,
      accessibilityHierarchy(),
      atfChecks(),
      accessibilityOverlay(),
      accessibilityAnnotatedPreview(),
      scrollAnnotation(),
      scrollLong(),
      scrollGif(),
    )

  private fun accessibilityHierarchy(): PreviewExtensionDescriptor =
    PreviewExtensionDescriptor(
      id = "a11y",
      displayName = "Accessibility hierarchy",
      cliCommands =
        listOf(
          PreviewExtensionCliCommand(
            id = "a11y.hierarchy.get",
            displayName = "Fetch accessibility hierarchy",
            summary = "Reads the structured accessibility hierarchy for one preview.",
            command =
              listOf(
                "compose-preview",
                "data",
                "get",
                "--id",
                "<preview-id>",
                "--kind",
                "a11y/hierarchy",
                "--json",
              ),
            agentRecommended = true,
            productKinds = listOf("a11y/hierarchy"),
          )
        ),
    )

  private fun atfChecks(): PreviewExtensionDescriptor =
    PreviewExtensionDescriptor(
      id = "atf-checks",
      displayName = "ATF checks",
      cliCommands =
        listOf(
          PreviewExtensionCliCommand(
            id = "atf-checks.run",
            displayName = "Run ATF accessibility checks",
            summary = "Renders previews and returns ATF accessibility findings.",
            command = listOf("compose-preview", "a11y", "--json"),
            agentRecommended = true,
            productKinds = listOf("a11y/atf", "a11y/touchTargets"),
          ),
          PreviewExtensionCliCommand(
            id = "atf-checks.get",
            displayName = "Fetch ATF findings",
            summary = "Reads previously emitted ATF findings for one preview.",
            command =
              listOf(
                "compose-preview",
                "data",
                "get",
                "--id",
                "<preview-id>",
                "--kind",
                "a11y/atf",
                "--json",
              ),
            productKinds = listOf("a11y/atf"),
          ),
        ),
    )

  private fun accessibilityOverlay(): PreviewExtensionDescriptor =
    PreviewExtensionDescriptor(
      id = "a11y-overlay",
      displayName = "Accessibility overlay annotations",
      componentExtensionIds = listOf("a11y", "atf-checks"),
      cliCommands =
        listOf(
          PreviewExtensionCliCommand(
            id = "a11y-overlay.get",
            displayName = "Fetch accessibility overlay",
            summary = "Reads the rendered accessibility overlay artifact for one preview.",
            command =
              listOf(
                "compose-preview",
                "data",
                "get",
                "--id",
                "<preview-id>",
                "--kind",
                "a11y/overlay",
                "--output",
                "<path>",
              ),
            productKinds = listOf("a11y/overlay"),
          )
        ),
    )

  private fun accessibilityAnnotatedPreview(): PreviewExtensionDescriptor =
    PreviewExtensionDescriptor(
      id = "a11y-annotated-preview",
      displayName = "Accessibility annotated preview",
      usageModes = setOf(PreviewExtensionUsageMode.SuggestedExtraPreview),
      componentExtensionIds = listOf("a11y", "atf-checks", "a11y-overlay", "overlay-legend"),
      cliCommands =
        listOf(
          PreviewExtensionCliCommand(
            id = "a11y-annotated-preview.render",
            displayName = "Render accessibility annotated previews",
            summary = "Discovers and renders suggested accessibility annotated preview extras.",
            command = listOf("compose-preview", "show", "--json"),
            usageModes = setOf(PreviewExtensionUsageMode.SuggestedExtraPreview),
          )
        ),
    )

  private fun scrollAnnotation(): PreviewExtensionDescriptor =
    PreviewExtensionDescriptor(
      id = "scrolling-preview-annotation",
      displayName = "ScrollingPreview annotation",
      usageModes = setOf(PreviewExtensionUsageMode.SuggestedExtraPreview),
      cliCommands =
        listOf(
          PreviewExtensionCliCommand(
            id = "scrolling-preview-annotation.render",
            displayName = "Render scroll annotation suggestions",
            summary = "Discovers @ScrollingPreview annotations and renders their suggested extras.",
            command = listOf("compose-preview", "show", "--json"),
            agentRecommended = true,
            usageModes = setOf(PreviewExtensionUsageMode.SuggestedExtraPreview),
          )
        ),
    )

  private fun scrollLong(): PreviewExtensionDescriptor =
    PreviewExtensionDescriptor(
      id = "scroll-long",
      displayName = "Long scroll",
      usageModes =
        setOf(
          PreviewExtensionUsageMode.ExplicitEffect,
          PreviewExtensionUsageMode.SuggestedExtraPreview,
        ),
      cliCommands =
        listOf(
          PreviewExtensionCliCommand(
            id = "scroll-long.get",
            displayName = "Fetch long scroll image",
            summary = "Reads a stitched long-scroll image artifact for one preview.",
            command =
              listOf(
                "compose-preview",
                "data",
                "get",
                "--id",
                "<preview-id>",
                "--kind",
                "render/scroll/long",
                "--output",
                "<path>",
              ),
            productKinds = listOf("render/scroll/long"),
          )
        ),
    )

  private fun scrollGif(): PreviewExtensionDescriptor =
    PreviewExtensionDescriptor(
      id = "scroll-gif",
      displayName = "Scroll GIF",
      usageModes =
        setOf(
          PreviewExtensionUsageMode.ExplicitEffect,
          PreviewExtensionUsageMode.SuggestedExtraPreview,
        ),
      cliCommands =
        listOf(
          PreviewExtensionCliCommand(
            id = "scroll-gif.get",
            displayName = "Fetch scroll GIF",
            summary = "Reads an animated scroll GIF artifact for one preview.",
            command =
              listOf(
                "compose-preview",
                "data",
                "get",
                "--id",
                "<preview-id>",
                "--kind",
                "render/scroll/gif",
                "--output",
                "<path>",
              ),
            productKinds = listOf("render/scroll/gif"),
          )
        ),
    )
}
