package ee.schimke.composeai.scripting

import ee.schimke.composeai.cli.A11Y_PAYLOAD_SCHEMA_V1
import ee.schimke.composeai.cli.AccessibilityEntry
import ee.schimke.composeai.cli.CaptureResult
import ee.schimke.composeai.cli.PreviewManifest
import ee.schimke.composeai.cli.PreviewModule
import ee.schimke.composeai.cli.PreviewResult
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.serialization.json.Json

/**
 * Coverage for `Main.kt`'s `annotateA11y` helper — the bit contrib will lift wholesale into its own
 * published binary. Reads a synthetic `accessibility.json` from disk and asserts that the
 * per-preview payloads land on the `dataExtensions["a11y"]` carrier with the right schema pin.
 */
class AnnotateA11yTest {

  private val tempDir: File = Files.createTempDirectory("annotate-a11y-test").toFile()
  private val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
  }

  @AfterTest
  fun cleanup() {
    tempDir.deleteRecursively()
  }

  private fun moduleAt(gradlePath: String, name: String): PreviewModule {
    val dir =
      tempDir.resolve(name).apply {
        mkdirs()
        resolve("build/compose-previews").mkdirs()
      }
    return PreviewModule(gradlePath = gradlePath, projectDir = dir)
  }

  private fun result(id: String, module: String) =
    PreviewResult(
      id = id,
      module = module,
      functionName = id,
      className = "com.app.${id}Kt",
      captures = listOf(CaptureResult(pngPath = "/tmp/$id.png")),
      pngPath = "/tmp/$id.png",
    )

  @Test
  fun `annotateA11y populates dataExtensions from the per-module accessibility json`() {
    val module = moduleAt(":app", "app")
    module.projectDir
      .resolve("build/compose-previews/accessibility.json")
      .writeText(
        """
        {
          "module": "app",
          "entries": [
            {
              "previewId": "HomeScreen",
              "findings": [
                {"level": "ERROR", "type": "TouchTargetSize", "message": "too small"}
              ]
            }
          ]
        }
        """
          .trimIndent()
      )
    val manifest =
      PreviewManifest(
        module = "app",
        variant = "debug",
        previews = emptyList(),
        dataExtensionReports = mapOf("a11y" to "accessibility.json"),
      )

    val annotated =
      annotateA11y(
        results = listOf(result("HomeScreen", ":app"), result("Profile", ":app")),
        manifests = listOf(module to manifest),
      )

    val home = annotated.single { it.id == "HomeScreen" }
    val payload = assertNotNull(home.dataExtensions["a11y"])
    assertEquals(A11Y_PAYLOAD_SCHEMA_V1, payload.schema)
    val entry = json.decodeFromJsonElement(AccessibilityEntry.serializer(), payload.payload)
    assertEquals("HomeScreen", entry.previewId)
    assertEquals(1, entry.findings.size)
    assertEquals("ERROR", entry.findings.single().level)

    // Profile had no entry in the accessibility.json → no `dataExtensions["a11y"]` added.
    val profile = annotated.single { it.id == "Profile" }
    assertNull(profile.dataExtensions["a11y"])
  }

  @Test
  fun `annotateA11y is a no-op when no accessibility json exists`() {
    val module = moduleAt(":app", "app")
    val manifest = PreviewManifest(module = "app", variant = "debug", previews = emptyList())

    val annotated =
      annotateA11y(
        results = listOf(result("HomeScreen", ":app")),
        manifests = listOf(module to manifest),
      )

    assertNull(annotated.single().dataExtensions["a11y"])
  }
}
