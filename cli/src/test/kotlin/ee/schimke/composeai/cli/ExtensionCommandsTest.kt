package ee.schimke.composeai.cli

import ee.schimke.composeai.data.render.RenderPreviewExtension
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
}
