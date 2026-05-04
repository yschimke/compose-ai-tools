package ee.schimke.composeai.data.render.pipeline

import ee.schimke.composeai.data.render.RenderPreviewExtension

/**
 * Daemon-free command catalog for built-in preview extensions.
 *
 * Renderer modules still advertise their full descriptors, including concrete pipeline steps, at
 * daemon initialize time. This catalog is intentionally lighter: it gives CLI and MCP clients a
 * single shrinkwrapped command surface they can list or route without depending on Android-only
 * extension modules or starting a daemon.
 */
object PreviewExtensionCommandCatalog {
  val extensions: List<PreviewExtensionDescriptor> =
    listOf(
      RenderPreviewExtension.deviceClipDescriptor,
      RenderPreviewExtension.deviceBackgroundDescriptor,
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

  val commands: List<PreviewExtensionCliCommand> = extensions.flatMap { it.cliCommands }

  fun commandById(id: String): PreviewExtensionCliCommand? = commands.firstOrNull { it.id == id }

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
                "extensions",
                "run",
                "a11y.hierarchy.get",
                "--id",
                "<preview-id>",
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
            command = listOf("compose-preview", "extensions", "run", "atf-checks.run", "--json"),
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
                "extensions",
                "run",
                "atf-checks.get",
                "--id",
                "<preview-id>",
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
            command =
              listOf(
                "compose-preview",
                "extensions",
                "run",
                "a11y-annotated-preview.render",
                "--json",
              ),
            usageModes = setOf(PreviewExtensionUsageMode.SuggestedExtraPreview),
          ),
          PreviewExtensionCliCommand(
            id = "a11y-overlay.get",
            displayName = "Fetch accessibility overlay",
            summary = "Reads the rendered accessibility overlay artifact for one preview.",
            command =
              listOf(
                "compose-preview",
                "extensions",
                "run",
                "a11y-overlay.get",
                "--id",
                "<preview-id>",
                "--output",
                "<path>",
              ),
            productKinds = listOf("a11y/overlay"),
          ),
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
            command =
              listOf(
                "compose-preview",
                "extensions",
                "run",
                "scrolling-preview-annotation.render",
                "--json",
              ),
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
                "extensions",
                "run",
                "scroll-long.get",
                "--id",
                "<preview-id>",
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
                "extensions",
                "run",
                "scroll-gif.get",
                "--id",
                "<preview-id>",
                "--output",
                "<path>",
              ),
            productKinds = listOf("render/scroll/gif"),
          )
        ),
    )
}
