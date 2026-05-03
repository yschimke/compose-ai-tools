package ee.schimke.composeai.cli

import ee.schimke.composeai.data.render.RenderPreviewExtension
import ee.schimke.composeai.data.render.pipeline.PreviewExtensionCliCommand
import ee.schimke.composeai.data.render.pipeline.PreviewExtensionDescriptor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

class ExtensionCommandsTest {
  private val json = Json {
    prettyPrint = false
    encodeDefaults = true
    ignoreUnknownKeys = true
  }

  @Test
  fun `extension command response pins schema and exposes agent commands`() {
    val response =
      ExtensionCommandsResponse(extensions = listOf(RenderPreviewExtension.composeTraceDescriptor))

    val encoded = json.encodeToString(ExtensionCommandsResponse.serializer(), response)
    val parsed = json.decodeFromString(ExtensionCommandsResponse.serializer(), encoded)
    val command = parsed.extensions.single().cliCommands.single()

    assertEquals(EXTENSION_COMMANDS_SCHEMA, parsed.schema)
    assertEquals("compose-trace.get", command.id)
    assertTrue(command.agentRecommended)
    assertEquals(
      listOf(
        "compose-preview",
        "extensions",
        "run",
        "compose-trace.get",
        "--id",
        "<preview-id>",
        "--json",
      ),
      command.command,
    )
  }

  @Test
  fun `registered command routes data get from descriptor product kind`() {
    val invocation =
      RegisteredExtensionCommand(
          extension = PreviewExtensionDescriptor(id = "custom"),
          command =
            PreviewExtensionCliCommand(
              id = "custom.get",
              command =
                listOf(
                  "compose-preview",
                  "extensions",
                  "run",
                  "custom.get",
                  "--id",
                  "<id>",
                  "--json",
                ),
              productKinds = listOf("custom/kind"),
            ),
        )
        .toCliInvocation()

    assertEquals(ExtensionCommandInvocation.Data("custom/kind", defaultJson = true), invocation)
  }

  @Test
  fun `registered render command routes from extension context`() {
    val a11yInvocation =
      RegisteredExtensionCommand(
          extension = PreviewExtensionDescriptor(id = "a11y-extra"),
          command =
            PreviewExtensionCliCommand(
              id = "a11y-extra.render",
              command = listOf("compose-preview", "extensions", "run", "a11y-extra.render"),
            ),
        )
        .toCliInvocation()
    val renderInvocation =
      RegisteredExtensionCommand(
          extension = PreviewExtensionDescriptor(id = "scroll-extra"),
          command =
            PreviewExtensionCliCommand(
              id = "scroll-extra.render",
              command = listOf("compose-preview", "extensions", "run", "scroll-extra.render"),
            ),
        )
        .toCliInvocation()

    assertEquals(ExtensionCommandInvocation.A11yRender, a11yInvocation)
    assertEquals(ExtensionCommandInvocation.Show, renderInvocation)
  }
}
